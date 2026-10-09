"""Deterministic Company controller tests; never invoke Hermes models."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from company_control import Controller, BOARD, EcoError


class CompanyControlTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.folder = Path(self.tmp.name)
        self.repo = self.folder / "repo"
        self.repo.mkdir()
        def run(*args):
            cp = subprocess.run(["git", "-C", str(self.repo), *args], check=True,
                                capture_output=True, encoding="utf-8")
            return cp.stdout.strip()
        self.git_run = run
        run("init", "-q")
        (self.repo / "README.md").write_text("initial\n", encoding="utf-8")
        run("add", "README.md")
        run("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
            "commit", "-qm", "initial")
        self.base = run("rev-parse", "HEAD")
        self.ctl = Controller(self.folder / "state.sqlite3",
                              self.folder / "fake-kanban.sqlite3")

    def tearDown(self):
        self.ctl.close()
        self.tmp.cleanup()

    def submit(self):
        return self.ctl.submit(title="Demo", spec="Create one simple function with test",
                               repository=str(self.repo), role="backend")

    def test_submission_does_not_spawn_models(self):
        with patch("company_control.native", side_effect=AssertionError("no native calls")):
            job = self.submit()
            self.assertEqual(self.ctl.row(job)["status"], "pending_approval")
            self.assertEqual(self.ctl.stages(job), [])
            self.assertEqual(self.ctl.guard.report(job)["total_starts"], 0)

    def test_only_explicit_owner_approval_queues(self):
        job = self.submit()
        with self.assertRaises(ValueError):
            self.ctl.finish_approval(job)
        self.ctl.approve(job)
        self.assertEqual(self.ctl.row(job)["status"], "queued")
        with self.assertRaises(ValueError):
            self.ctl.approve(job)

    def test_rejects_dirty_repository(self):
        (self.repo / "scratch.txt").write_text("dirty", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "clean"):
            self.submit()

    def test_builder_requires_observed_git_commit(self):
        job = self.submit()
        row = self.ctl.row(job)
        task = {"workspace_path": str(self.repo), "result": self.base}
        run = {"profile": "companybackend", "outcome": "completed", "summary": self.base}
        with self.assertRaisesRegex(RuntimeError, "new commit"):
            self.ctl._verify_builder(row, task, run)

    def test_builder_verifies_git_head_and_clean_tree(self):
        job = self.submit()
        (self.repo / "demo.py").write_text("def answer(): return 42\n", encoding="utf-8")
        self.git_run("add", "demo.py")
        self.git_run("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                     "commit", "-qm", "demo")
        head = self.git_run("rev-parse", "HEAD")
        task = {"workspace_path": str(self.repo), "result": head}
        run = {"profile": "companybackend", "outcome": "completed",
               "summary": head, "metadata": "{}"}
        self.assertEqual(self.ctl._verify_builder(self.ctl.row(job), task, run), head)
        (self.repo / "demo.py").write_text("dirty\n", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "dirty"):
            self.ctl._verify_builder(self.ctl.row(job), task, run)

    def test_reviews_reject_wrong_sha_identity_or_ambiguous_approval(self):
        job = self.submit()
        self.ctl.update_job(job, "stage_queued", stage="product", commit=self.base)
        row = self.ctl.row(job)
        task = {"result": "APPROVED:" + self.base}
        run = {"profile": "companyproduct", "outcome": "completed", "summary": ""}
        self.ctl._verify_review(row, "product", task, run)
        with self.assertRaisesRegex(RuntimeError, "mismatch"):
            self.ctl._verify_review(row, "architect", task, run)
        task["result"] = "Looks fine"
        with self.assertRaisesRegex(RuntimeError, "no explicit"):
            self.ctl._verify_review(row, "product", task, run)
        task["result"] = "REJECTED:" + self.base
        with self.assertRaisesRegex(RuntimeError, "rejected"):
            self.ctl._verify_review(row, "product", task, run)

    def test_fails_closed_if_gateway_autodispatch_is_enabled(self):
        with patch("company_control.invoke", return_value="true"):
            with self.assertRaisesRegex(RuntimeError, "gateway Kanban"):
                self.ctl.tick()

    def test_idle_tick_has_zero_model_calls(self):
        with patch("company_control.invoke", return_value="false"):
            with patch("company_control.native", side_effect=AssertionError("no models")):
                self.assertEqual(self.ctl.tick()["checked"], 0)

    def test_daily_budget_rejects_fifth_across_jobs(self):
        for i in range(self.ctl.policy["limits"]["daily_global_starts"]):
            run = f"r-{i}"
            self.ctl.guard.reserve(run_id=run, project_id=f"job-{i}",
                                   role="backend")
            self.ctl.guard.finish(run_id=run, status="ok")
        with self.assertRaisesRegex(EcoError, "Global daily"):
            self.ctl.guard.reserve(run_id="r-extra", project_id="different",
                                   role="product")


if __name__ == "__main__":
    unittest.main()
