"""Phase 2 tests: authenticated Telegram ingress and real native-agent evidence."""
from __future__ import annotations

import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from company import Company, WorkflowError
from phase2 import (
    HermesDelegationReceipt,
    NativeDelegationBridge,
    TelegramDirectorBridge,
    TelegramEnvelope,
)


def git(repo: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(repo), *args],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


class Phase2Tests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.repo = self.root / "demo"
        self.repo.mkdir()
        git(self.repo, "init")
        git(self.repo, "config", "user.email", "phase2@example.invalid")
        git(self.repo, "config", "user.name", "Phase 2 Test")
        (self.repo / "calculator.py").write_text("def add(a, b):\n    return a + b\n", encoding="utf-8")
        (self.repo / ".gitignore").write_text("__pycache__/\n*.pyc\n", encoding="utf-8")
        tests = self.repo / "tests"
        tests.mkdir()
        (tests / "test_calculator.py").write_text(
            "import unittest\nfrom calculator import add\n\n"
            "class CalculatorTests(unittest.TestCase):\n"
            "    def test_adds(self):\n        self.assertEqual(add(2, 3), 5)\n",
            encoding="utf-8",
        )
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-m", "seed demo")
        self.base_sha = git(self.repo, "rev-parse", "HEAD")
        self.company = Company(str(self.root / "company.db"))
        self.registry = self.root / "hermes-state.db"
        db = sqlite3.connect(self.registry)
        try:
            db.execute(
                "CREATE TABLE async_delegations (delegation_id TEXT PRIMARY KEY, state TEXT NOT NULL)"
            )
            db.commit()
        finally:
            db.close()

    def tearDown(self):
        self.company.close()
        self.tmp.cleanup()

    def _gateway_envelope(self, **changes):
        values = {
            "platform": "telegram",
            "chat_type": "dm",
            "user_id": "owner-123",
            "chat_id": "owner-123",
            "message_id": "msg-001",
        }
        values.update(changes)
        with patch.dict(
            os.environ,
            {
                "HERMES_SESSION_PLATFORM": values["platform"],
                "HERMES_SESSION_CHAT_TYPE": values["chat_type"],
                "HERMES_SESSION_USER_ID": values["user_id"],
                "HERMES_SESSION_CHAT_ID": values["chat_id"],
                "HERMES_SESSION_MESSAGE_ID": values["message_id"],
            },
            clear=False,
        ):
            return TelegramEnvelope.from_gateway_environment("Crea una mejora real del demo")

    def _receipt(self, delegation_id: str, state: str = "completed"):
        db = sqlite3.connect(self.registry)
        try:
            db.execute(
                "INSERT OR REPLACE INTO async_delegations VALUES (?,?)",
                (delegation_id, state),
            )
            db.commit()
        finally:
            db.close()
        return HermesDelegationReceipt.from_registry(delegation_id, self.registry)

    def _ingest(self):
        director = TelegramDirectorBridge(self.company, allowed_owner_ids={"owner-123"})
        envelope = self._gateway_envelope()
        return director.ingest(
            envelope,
            project_name="Phase 2 demo",
            task_title="Documentar la suma",
            builder_role="backend",
            acceptance_criteria="Pruebas unitarias pasan y existe un commit nuevo",
        ), director, envelope

    def test_telegram_ingress_requires_owner_dm_and_rejects_replay(self):
        (project_id, task_id), director, envelope = self._ingest()
        self.assertTrue(project_id)
        self.assertTrue(task_id)
        events = self.company.events(project_id)
        self.assertEqual(events[0]["actor"], "authenticated_owner")
        self.assertEqual(events[0]["kind"], "telegram_request_received")

        with self.assertRaises(WorkflowError):
            director.ingest(
                envelope,
                project_name="Replay",
                task_title="Replay",
                builder_role="backend",
                acceptance_criteria="Must be rejected",
            )

        for index, changed in enumerate((
            {"user_id": "attacker"},
            {"platform": "cli"},
            {"chat_type": "group", "chat_id": "group-1"},
        )):
            bad = self._gateway_envelope(message_id=f"msg-bad-{index}", **changed)
            with self.assertRaises(WorkflowError):
                director.ingest(
                    bad,
                    project_name="Unauthorized",
                    task_title="Unauthorized",
                    builder_role="backend",
                    acceptance_criteria="Must be rejected",
                )

        forged = TelegramEnvelope(
            platform="telegram",
            chat_type="dm",
            user_id="owner-123",
            chat_id="owner-123",
            message_id="forged-001",
            text="Forged DTO",
        )
        with self.assertRaises(WorkflowError):
            director.ingest(
                forged,
                project_name="Forged",
                task_title="Forged",
                builder_role="backend",
                acceptance_criteria="Must be rejected",
            )

    def test_gateway_envelope_comes_from_bound_session_environment(self):
        with patch.dict(
            os.environ,
            {
                "HERMES_SESSION_PLATFORM": "telegram",
                "HERMES_SESSION_CHAT_TYPE": "dm",
                "HERMES_SESSION_USER_ID": "owner-123",
                "HERMES_SESSION_CHAT_ID": "owner-123",
                "HERMES_SESSION_MESSAGE_ID": "gateway-msg-001",
            },
            clear=False,
        ):
            envelope = TelegramEnvelope.from_gateway_environment("trusted text")
        self.assertEqual(envelope.platform, "telegram")
        self.assertEqual(envelope.user_id, "owner-123")
        self.assertEqual(envelope.message_id, "gateway-msg-001")

    def test_paused_project_cannot_prepare_native_job(self):
        (project_id, task_id), _, _ = self._ingest()
        self.company.set_project_state(project_id, "paused", "director")
        bridge = NativeDelegationBridge(self.company)
        with self.assertRaises(WorkflowError):
            bridge.prepare_job(
                task_id=task_id,
                builder_role="backend",
                repository=str(self.repo),
                base_sha=self.base_sha,
                verification_command=[sys.executable, "-m", "unittest"],
            )

    def test_dirty_worktree_cannot_be_used_as_commit_evidence(self):
        (_, task_id), _, _ = self._ingest()
        bridge = NativeDelegationBridge(self.company)
        run_id = bridge.prepare_job(
            task_id=task_id,
            builder_role="backend",
            repository=str(self.repo),
            base_sha=self.base_sha,
            verification_command=[sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"],
        )
        receipt = self._receipt("deleg_deadbeef")
        bridge.bind_delegation(run_id, receipt)
        (self.repo / "README.md").write_text("Committed change\n", encoding="utf-8")
        git(self.repo, "add", "README.md")
        git(self.repo, "commit", "-m", "docs: committed evidence")
        head_sha = git(self.repo, "rev-parse", "HEAD")
        (self.repo / "quote.py").write_text(
            "def quote_total(unit_price, quantity):\n    return 999\n",
            encoding="utf-8",
        )
        with self.assertRaises(WorkflowError):
            bridge.complete_job(run_id, receipt, head_sha)

    def test_native_task_rejects_legacy_submission_path(self):
        (_, task_id), _, _ = self._ingest()
        bridge = NativeDelegationBridge(self.company)
        bridge.prepare_job(
            task_id=task_id,
            builder_role="backend",
            repository=str(self.repo),
            base_sha=self.base_sha,
            verification_command=[sys.executable, "-m", "unittest"],
        )
        with self.assertRaises(WorkflowError):
            self.company.submit_task(task_id, "backend", "unverified text evidence")

    def test_unregistered_delegation_id_cannot_create_receipt(self):
        with self.assertRaises(WorkflowError):
            HermesDelegationReceipt.from_registry("deleg_bad0bad0", self.registry)
        with self.assertRaises(WorkflowError):
            HermesDelegationReceipt.from_registry("totally-unverified-id", self.registry)

    def test_native_job_claim_is_atomic_across_sqlite_connections(self):
        (_, task_id), _, _ = self._ingest()
        barrier = threading.Barrier(2)
        results = []
        results_lock = threading.Lock()
        database = str(self.root / "company.db")

        class RacingCompany(Company):
            def __init__(self, path):
                super().__init__(path)
                self.waited = False

            def _row(self, table, item_id):
                row = super()._row(table, item_id)
                if table == "tasks" and row["state"] == "queued" and not self.waited:
                    self.waited = True
                    barrier.wait(timeout=5)
                return row

        def claim(label):
            company = RacingCompany(database)
            try:
                bridge = NativeDelegationBridge(company)
                run_id = bridge.prepare_job(
                    task_id=task_id,
                    builder_role="backend",
                    repository=str(self.repo),
                    base_sha=self.base_sha,
                    verification_command=[sys.executable, "-m", "unittest"],
                )
                outcome = (label, "ok", run_id)
            except (WorkflowError, sqlite3.OperationalError) as exc:
                outcome = (label, "rejected", str(exc))
            finally:
                company.close()
            with results_lock:
                results.append(outcome)

        threads = [threading.Thread(target=claim, args=(label,)) for label in ("a", "b")]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=10)

        self.assertEqual(sum(item[1] == "ok" for item in results), 1, results)
        run_count = self.company.db.execute(
            "SELECT count(*) FROM native_runs WHERE task_id=?", (task_id,)
        ).fetchone()[0]
        self.assertEqual(run_count, 1)

    def test_native_builder_and_two_independent_reviews_are_commit_bound(self):
        (project_id, task_id), _, _ = self._ingest()
        bridge = NativeDelegationBridge(self.company)
        run_id = bridge.prepare_job(
            task_id=task_id,
            builder_role="backend",
            repository=str(self.repo),
            base_sha=self.base_sha,
            verification_command=[sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"],
        )
        builder_receipt = self._receipt("deleg_0000b001")
        bridge.bind_delegation(run_id, builder_receipt)

        (self.repo / "README.md").write_text("# Calculator\n\nVerified by the real builder.\n", encoding="utf-8")
        git(self.repo, "add", "README.md")
        git(self.repo, "commit", "-m", "docs: document calculator")
        head_sha = git(self.repo, "rev-parse", "HEAD")

        with self.assertRaises(WorkflowError):
            bridge.complete_job(run_id, builder_receipt, "f" * 40)

        evidence = bridge.complete_job(run_id, builder_receipt, head_sha)
        self.assertEqual(evidence["head_sha"], head_sha)
        self.assertEqual(evidence["base_sha"], self.base_sha)
        self.assertEqual(evidence["verification"]["returncode"], 0)
        self.assertTrue(evidence["changed_files"])
        self.assertEqual(self.company.status(project_id)["tasks"][0]["state"], "review")

        with self.assertRaises(WorkflowError):
            self.company.review_task(task_id, "product", True, "legacy bypass")

        with self.assertRaises(WorkflowError):
            bridge.record_review(
                run_id=run_id,
                reviewer="product",
                receipt=self._receipt("deleg_0000c001"),
                head_sha="e" * 40,
                approved=True,
                note="Wrong revision",
            )
        with self.assertRaises(WorkflowError):
            bridge.record_review(
                run_id=run_id,
                reviewer="product",
                receipt=builder_receipt,
                head_sha=head_sha,
                approved=True,
                note="Builder cannot review itself",
            )

        bridge.record_review(
            run_id=run_id,
            reviewer="product",
            receipt=self._receipt("deleg_0000c002"),
            head_sha=head_sha,
            approved=True,
            note="Acceptance criteria verified",
        )
        self.assertEqual(self.company.status(project_id)["tasks"][0]["state"], "review")
        bridge.record_review(
            run_id=run_id,
            reviewer="architect",
            receipt=self._receipt("deleg_0000a001"),
            head_sha=head_sha,
            approved=True,
            note="Commit and verification evidence are valid",
        )
        self.assertEqual(self.company.status(project_id)["tasks"][0]["state"], "accepted")

        phase2 = bridge.run_status(run_id)
        self.assertEqual(phase2["status"], "completed")
        self.assertEqual(phase2["head_sha"], head_sha)
        self.assertEqual({r["reviewer"] for r in phase2["reviews"]}, {"product", "architect"})
        self.assertEqual(json.loads(phase2["evidence"])["head_sha"], head_sha)


if __name__ == "__main__":
    unittest.main()
