"""Phase 2 tests: authenticated Telegram ingress and real native-agent evidence."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from company import Company, WorkflowError
from phase2 import NativeDelegationBridge, TelegramDirectorBridge, TelegramEnvelope


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

    def tearDown(self):
        self.company.close()
        self.tmp.cleanup()

    def _ingest(self):
        director = TelegramDirectorBridge(self.company, allowed_owner_ids={"owner-123"})
        envelope = TelegramEnvelope(
            platform="telegram",
            chat_type="dm",
            user_id="owner-123",
            chat_id="owner-123",
            message_id="msg-001",
            text="Crea una mejora real del demo",
        )
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

        for changed in (
            {"user_id": "attacker"},
            {"platform": "cli"},
            {"chat_type": "group", "chat_id": "group-1"},
        ):
            bad = TelegramEnvelope(**{**envelope.__dict__, "message_id": changed.get("message_id", "msg-bad-" + str(len(changed))), **changed})
            with self.assertRaises(WorkflowError):
                director.ingest(
                    bad,
                    project_name="Unauthorized",
                    task_title="Unauthorized",
                    builder_role="backend",
                    acceptance_criteria="Must be rejected",
                )

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
        bridge.bind_delegation(run_id, "deleg-builder-001")

        (self.repo / "README.md").write_text("# Calculator\n\nVerified by the real builder.\n", encoding="utf-8")
        git(self.repo, "add", "README.md")
        git(self.repo, "commit", "-m", "docs: document calculator")
        head_sha = git(self.repo, "rev-parse", "HEAD")

        with self.assertRaises(WorkflowError):
            bridge.complete_job(run_id, "deleg-builder-001", "f" * 40)

        evidence = bridge.complete_job(run_id, "deleg-builder-001", head_sha)
        self.assertEqual(evidence["head_sha"], head_sha)
        self.assertEqual(evidence["base_sha"], self.base_sha)
        self.assertEqual(evidence["verification"]["returncode"], 0)
        self.assertTrue(evidence["changed_files"])
        self.assertEqual(self.company.status(project_id)["tasks"][0]["state"], "review")

        with self.assertRaises(WorkflowError):
            bridge.record_review(
                run_id=run_id,
                reviewer="product",
                delegation_id="deleg-product-001",
                head_sha="e" * 40,
                approved=True,
                note="Wrong revision",
            )
        with self.assertRaises(WorkflowError):
            bridge.record_review(
                run_id=run_id,
                reviewer="product",
                delegation_id="deleg-builder-001",
                head_sha=head_sha,
                approved=True,
                note="Builder cannot review itself",
            )

        bridge.record_review(
            run_id=run_id,
            reviewer="product",
            delegation_id="deleg-product-001",
            head_sha=head_sha,
            approved=True,
            note="Acceptance criteria verified",
        )
        self.assertEqual(self.company.status(project_id)["tasks"][0]["state"], "review")
        bridge.record_review(
            run_id=run_id,
            reviewer="architect",
            delegation_id="deleg-architect-001",
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
