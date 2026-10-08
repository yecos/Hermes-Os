"""Unit tests for workflow authority and approval safety."""
import tempfile
import unittest
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from company import Company, WorkflowError


class CompanyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.c = Company(str(Path(self.tmp.name) / "state.db"))
        self.pid = self.c.create_project("TEMPLO", "Cotizaciones de arquitectura")
        self.tid = self.c.add_task(self.pid, "Formulario de cotizacion", "frontend", "Envio valido")

    def tearDown(self):
        self.c.close()
        self.tmp.cleanup()

    def submit(self):
        self.c.start_task(self.tid, "frontend")
        self.c.submit_task(self.tid, "frontend", "Tests OK commit aabbcc")

    def test_two_distinct_reviewers_required(self):
        self.submit()
        self.c.review_task(self.tid, "product", True, "UX OK")
        self.assertEqual(self.c.status(self.pid)["tasks"][0]["state"], "review")
        self.c.review_task(self.tid, "architect", True, "Security OK")
        self.assertEqual(self.c.status(self.pid)["tasks"][0]["state"], "accepted")

    def test_builder_cannot_approve_and_preview_is_gated(self):
        self.submit()
        with self.assertRaises(WorkflowError):
            self.c.review_task(self.tid, "frontend", True, "I did it")
        with self.assertRaises(WorkflowError):
            self.c.propose_preview(self.pid, "a" * 40, "https://example.com")

    def test_three_rejections_block(self):
        for i in range(3):
            self.submit()
            self.c.review_task(self.tid, "architect", False, "Breaks contract")
            self.assertEqual(self.c.status(self.pid)["tasks"][0]["state"],
                             "blocked" if i == 2 else "queued")
        with self.assertRaises(WorkflowError):
            self.c.start_task(self.tid, "frontend")

    def test_preview_is_bound_to_exact_commit_and_owner(self):
        self.submit()
        self.c.review_task(self.tid, "product", True, "UX OK")
        self.c.review_task(self.tid, "architect", True, "Tech OK")
        req = self.c.propose_preview(self.pid, "a" * 40, "https://preview.example.com")
        with self.assertRaises(WorkflowError):
            self.c.approve_preview(req, "b" * 40, "authenticated_owner")
        with self.assertRaises(WorkflowError):
            self.c.approve_preview(req, "a" * 40, "director")
        self.c.approve_preview(req, "a" * 40, "authenticated_owner")
        self.assertEqual(self.c.status(self.pid)["approvals"][0]["state"], "approved")

    def test_pending_approval_invalidated_by_new_preview(self):
        self.submit()
        self.c.review_task(self.tid, "product", True, "UX OK")
        self.c.review_task(self.tid, "architect", True, "Tech OK")
        old = self.c.propose_preview(self.pid, "a" * 40, "https://preview.example.com")
        self.c.propose_preview(self.pid, "b" * 40, "https://preview2.example.com")
        with self.assertRaises(WorkflowError):
            self.c.approve_preview(old, "a" * 40, "authenticated_owner")

    def test_pause_blocks_new_work(self):
        self.c.set_project_state(self.pid, "paused", "director")
        with self.assertRaises(WorkflowError):
            self.c.start_task(self.tid, "frontend")
        self.c.set_project_state(self.pid, "active", "director")
        self.c.start_task(self.tid, "frontend")


if __name__ == "__main__":
    unittest.main()
