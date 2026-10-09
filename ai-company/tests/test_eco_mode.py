"""Unit tests for ECO budget enforcement and measured (not invented) usage."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from eco_mode import EcoError, EcoUsageLedger, load_policy


class EcoModeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.dbpath = str(Path(self.temp.name) / "eco.sqlite3")
        self.policy = load_policy()
        self.ledger = EcoUsageLedger(self.dbpath, self.policy)
        self.now = 1_700_000_000.0

    def tearDown(self):
        self.ledger.close()
        self.temp.cleanup()

    def reserve(self, run_id, role="backend", project_id="pilot", at=None):
        return self.ledger.reserve(run_id=run_id, project_id=project_id,
                                   role=role, at=self.now if at is None else at)

    def complete(self, run_id, status="ok", at=None, **kwargs):
        self.ledger.finish(run_id=run_id, status=status,
                           at=(self.now + 2 if at is None else at), **kwargs)

    def test_policy_preserves_models_and_reduces_limits(self):
        self.assertEqual(self.policy["provider"], "openai-codex")
        self.assertEqual(self.policy["roles"]["director"]["model"], "gpt-5.6-sol")
        self.assertEqual(self.policy["roles"]["product"]["model"], "gpt-6-luna")
        self.assertEqual(self.policy["roles"]["backend"]["model"], "gpt-5.6-luna")
        self.assertEqual(self.policy["roles"]["director"]["max_iterations"], 12)
        self.assertEqual(self.policy["roles"]["architect"]["max_iterations"], 8)
        self.assertEqual(self.policy["roles"]["frontend"]["max_iterations"], 12)

    def test_no_model_override_or_excess_budget(self):
        original = json.loads((Path(__file__).resolve().parents[1] /
                               "eco-policy.json").read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "policy.json"
            original["roles"]["frontend"]["model"] = "gpt-5.6-sol"
            path.write_text(json.dumps(original), encoding="utf-8")
            with self.assertRaises(EcoError):
                load_policy(path)
            original["roles"]["frontend"]["model"] = "gpt-5.6-luna"
            original["roles"]["frontend"]["max_iterations"] = 1000
            path.write_text(json.dumps(original), encoding="utf-8")
            with self.assertRaises(EcoError):
                load_policy(path)

    def test_idempotent_reservation_and_no_conflicting_reuse(self):
        self.assertEqual(self.reserve("r1"), "reserved")
        self.assertEqual(self.reserve("r1"), "already_running")
        with self.assertRaises(EcoError):
            self.ledger.reserve(run_id="r1", project_id="other", role="backend", at=self.now)
        self.complete("r1")
        self.assertEqual(self.reserve("r1"), "already_ok")

    def test_one_builder_and_one_reviewer_at_a_time(self):
        self.reserve("b1", "backend")
        with self.assertRaises(EcoError):
            self.reserve("b2", "frontend")
        self.reserve("p1", "product")
        with self.assertRaises(EcoError):
            self.reserve("a1", "architect")
        self.complete("b1")
        self.complete("p1")
        self.assertEqual(self.reserve("b2", "frontend", project_id="pilot2"), "reserved")
        self.assertEqual(self.reserve("a1", "architect", project_id="pilot2"), "reserved")

    def test_daily_role_limit_blocks_extra_sessions(self):
        count = self.policy["limits"]["daily_starts_per_role_per_project"]
        for n in range(count):
            run = f"role-{n}"
            self.reserve(run, "backend")
            self.complete(run)
        with self.assertRaisesRegex(EcoError, "role session-start"):
            self.reserve("extra", "backend")

    def test_global_daily_budget_cannot_be_bypassed_with_new_projects(self):
        for i in range(self.policy["limits"]["daily_global_starts"]):
            job = f"job-global-{i}"
            self.reserve(job, "backend", project_id=job)
            self.complete(job)
        with self.assertRaisesRegex(EcoError, "Global daily Codex"):
            self.reserve("global-extra", "product", project_id="fresh-project")

    def test_429_pauses_all_codex_roles_without_spinning(self):
        self.reserve("b1")
        self.complete("b1", status="rate_limited")
        with self.assertRaisesRegex(EcoError, "cooling down"):
            self.reserve("dir1", "director", at=self.now + 100)
        self.assertEqual(self.reserve("dir1", "director", at=self.now + 303),
                         "reserved")

    def test_backoff_increases_after_repeated_429(self):
        self.reserve("b1")
        self.complete("b1", status="rate_limited")
        self.reserve("b2", project_id="pilot2", at=self.now + 303)
        self.complete("b2", status="rate_limited", at=self.now + 304)
        with self.assertRaisesRegex(EcoError, "cooling down"):
            self.reserve("b3", project_id="pilot3", at=self.now + 500)
        self.assertEqual(self.reserve("b3", project_id="pilot3", at=self.now + 1205), "reserved")

    def test_token_telemetry_counts_only_reported_values(self):
        self.reserve("b1")
        self.complete("b1", input_tokens=900, output_tokens=100,
                      cached_input_tokens=400, usage_source="hermes",
                      session_id="example-session-1")
        self.reserve("p1", "product")
        self.complete("p1")
        result = self.ledger.report("pilot")
        self.assertEqual(result["total_starts"], 2)
        self.assertFalse(result["subscription_quota_observed"])
        self.assertEqual(result["roles"]["backend"]["input_tokens"], 900)
        self.assertEqual(result["roles"]["backend"]["output_tokens"], 100)
        self.assertEqual(result["roles"]["backend"]["cached_input_tokens"], 400)
        self.assertEqual(result["roles"]["product"]["sessions_without_token_telemetry"], 1)
        with self.assertRaises(EcoError):
            self.ledger.finish(run_id="b1", status="ok")

    def test_rejects_fake_zero_usage_and_cache_exceeding_input(self):
        self.reserve("b1")
        with self.assertRaises(EcoError):
            self.complete("b1", input_tokens=0, output_tokens=0, usage_source="unknown")
        with self.assertRaises(EcoError):
            self.complete("b1", input_tokens=10, output_tokens=2,
                          cached_input_tokens=11, usage_source="provider")
        self.complete("b1", status="failed")
        self.assertEqual(self.ledger.report()["roles"]["backend"]
                         ["sessions_without_token_telemetry"], 1)

    def test_restart_does_not_release_unreconciled_running_job(self):
        self.reserve("b1")
        self.ledger.close()
        self.ledger = EcoUsageLedger(self.dbpath, self.policy)
        self.assertEqual(self.reserve("b1"), "already_running")
        with self.assertRaises(EcoError):
            self.reserve("b2")
        self.complete("b1", status="cancelled")
        self.assertEqual(self.reserve("b2", project_id="pilot2"), "reserved")

    def test_one_owner_authorized_retry_keeps_global_budget(self):
        self.reserve("initial", "backend")
        self.complete("initial", status="failed")
        self.ledger.authorize_single_retry(
            project_id="pilot", role="backend", original_run_id="initial",
            at=self.now + 5,
        )
        with self.assertRaisesRegex(EcoError, "already has"):
            self.ledger.authorize_single_retry(
                project_id="pilot", role="backend", original_run_id="initial")
        self.assertEqual(self.reserve("second", "backend"), "reserved")
        self.complete("second")
        with self.assertRaisesRegex(EcoError, "role session-start"):
            self.reserve("third", "backend")
        self.assertEqual(self.reserve("product", "product"), "reserved")
        self.complete("product")
        self.assertEqual(self.reserve("architect", "architect"), "reserved")
        self.complete("architect")
        with self.assertRaisesRegex(EcoError, "Global daily"):
            self.reserve("fifth", "frontend", project_id="new-project")
        # The special second attempt is authorized only for its original UTC day.
        tomorrow = self.now + 86400
        self.assertEqual(self.reserve("tomorrow1", "backend", at=tomorrow), "reserved")
        self.complete("tomorrow1", at=tomorrow + 1)
        with self.assertRaisesRegex(EcoError, "role session-start"):
            self.reserve("tomorrow2", "backend", at=tomorrow + 2)

    def test_retry_requires_real_failed_run(self):
        self.reserve("first")
        with self.assertRaisesRegex(EcoError, "completed failed run"):
            self.ledger.authorize_single_retry(
                project_id="pilot", role="backend", original_run_id="first")
        self.complete("first")
        with self.assertRaisesRegex(EcoError, "completed failed run"):
            self.ledger.authorize_single_retry(
                project_id="pilot", role="backend", original_run_id="first")

    def test_verified_review_reconciliation_does_not_consume_new_start(self):
        self.reserve("product1", "product")
        self.complete("product1", "failed")
        self.ledger.reconcile_verified_review(
            run_id="product1", reviewer_role="product",
            task_id="t_test123", commit_sha="a" * 40)
        self.assertEqual(self.ledger.report("pilot")["total_starts"], 1)
        self.assertEqual(
            self.ledger.db.execute(
                "SELECT status FROM eco_runs WHERE run_id='product1'"
            ).fetchone()[0], "ok"
        )
        with self.assertRaises(EcoError):
            self.ledger.reconcile_verified_review(
                run_id="product1", reviewer_role="product",
                task_id="t_test123", commit_sha="a" * 40)


if __name__ == "__main__":
    unittest.main()
