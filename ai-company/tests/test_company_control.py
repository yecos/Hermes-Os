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

    def test_metrics_are_local_and_require_no_model(self):
        job = self.submit()
        with patch("company_control.native", side_effect=AssertionError("No inference")):
            report = self.ctl.metrics()
        self.assertEqual(report["daily_starts"], 0)
        self.assertEqual(report["jobs_by_status"]["pending_approval"], 1)
        self.assertEqual(report["model_calls_from_this_command"], 0)
        self.assertEqual(report["tokens"], "unknown_from_controller")

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
        self.assertEqual(self.ctl.row(job)["stage"], "backend")
        with self.assertRaises(ValueError):
            self.ctl.approve(job)

    def test_one_active_project_must_finish_before_another_is_approved(self):
        first = self.submit()
        second = self.submit()
        self.ctl.approve(first)
        with self.assertRaisesRegex(EcoError, "active project"):
            self.ctl.approve(second)
        self.assertEqual(self.ctl.row(second)["status"], "pending_approval")

    def test_approval_requires_three_available_eco_starts(self):
        jobid = self.submit()
        for index in range(2):
            runid = f"used-start-{index}"
            self.ctl.guard.reserve(
                run_id=runid, project_id=f"old-{index}", role="backend")
            self.ctl.guard.finish(run_id=runid, status="failed")
        with self.assertRaisesRegex(EcoError, "Not enough daily Codex slots"):
            self.ctl.approve(jobid)
        self.assertEqual(self.ctl.row(jobid)["status"], "pending_approval")
        self.assertEqual(self.ctl.guard.report()["total_starts"], 2)

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
        # Kanban-managed nested Git worktrees must not count as source edits.
        nested = self.repo / ".worktrees" / "existing-task"
        nested.mkdir(parents=True)
        (nested / "scratch.txt").write_text("managed worktree artifact", encoding="utf-8")
        self.ctl._verify_review(row, "product", task, run)
        (self.repo / "unexpected.txt").write_text("unauthorized edit", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "Main review repository changed"):
            self.ctl._verify_review(row, "product", task, run)
        (self.repo / "unexpected.txt").unlink()
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

    def test_iteration_cap_blocks_without_spawning_reviewers(self):
        jobid = self.submit()
        self.ctl.approve(jobid)
        stageid = jobid + ":backend"
        self.ctl.guard.reserve(run_id=stageid, project_id=jobid, role="backend")
        native_task = {"id": "task-test", "status": "blocked",
                       "assignee": "companybackend",
                       "model_override": "gpt-5.6-luna",
                       "provider_override": "openai-codex"}
        native_run = {"profile": "companybackend", "status": "gave_up",
                      "error": "Iteration budget exhausted (12/12)"}
        step = {"id": stageid, "task_id": "task-test", "role": "backend",
                "status": "reserved"}
        with patch("company_control.read_native", return_value=(native_task, native_run)):
            self.ctl._reconcile(self.ctl.row(jobid), step)
        self.assertEqual(self.ctl.row(jobid)["status"], "blocked")
        self.assertIn("Iteration budget exhausted", self.ctl.row(jobid)["error"])
        self.assertEqual(self.ctl.stages(jobid), [])
        self.assertEqual(self.ctl.guard.report(jobid)["total_starts"], 1)


if __name__ == "__main__":
    unittest.main()
