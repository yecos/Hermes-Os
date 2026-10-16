"""Smoke test: validate the end-to-end demonstration without external services."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from smoke_demo import run_demo


class DemoFlowTests(unittest.TestCase):
    def test_complete_temporal_demo(self):
        result = run_demo()
        self.assertEqual(result["kind"], "simulation_only")
        self.assertEqual(result["tasks_total"], 3)
        self.assertEqual(result["tasks_accepted"], 3)
        self.assertEqual(result["revisions_requested"], 1)
        self.assertTrue(result["dual_reviews"])
        self.assertTrue(result["preview_before_review_blocked"])
        self.assertTrue(result["director_approval_blocked"])
        self.assertTrue(result["mock_approval_recorded"])
        self.assertFalse(result["models_connected"])
        self.assertFalse(result["telegram_connected"])
        self.assertFalse(result["hermes_commander_connected"])
        self.assertFalse(result["real_preview_created"])
        self.assertFalse(result["production_deployed"])


if __name__ == "__main__":
    unittest.main()
