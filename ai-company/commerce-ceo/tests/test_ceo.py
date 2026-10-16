import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from datetime import datetime, timezone

SOURCE = Path(__file__).resolve().parents[1] / "ceo.py"
spec = importlib.util.spec_from_file_location("hermes_commerce_ceo", SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
run_cycle = module.run_cycle
CycleError = module.CycleError

def snapshot():
    return {
        "site": "https://hermes-os-eligebien.vercel.app",
        "analytics": {"visits": None, "category_events": 0, "outbound_clicks": None},
        "partnerships": [{"name": "impact.com", "status": "research"}],
        "verified": {"approved_commissions_minor": None, "currency": "USD"}
    }

class FakeModel:
    def __init__(self, reject=False):
        self.calls = 0
        self.reject = reject
    def ask(self, role, system, payload):
        self.calls += 1
        if role == "director":
            return {"tasks": [{
                "role": "research_catalog", "title": "Research demand",
                "instruction": "Compare customer needs using verified public facts",
                "success_criteria": "Produce a sourced research draft"
            }]}
        if role == "research_catalog":
            return {"title": "Ideas", "draft": "Propose testing a specific user need. No sales verified.",
                    "assumptions": [], "checks_needed": ["Confirm demand with sources"]}
        return {"decision": "reject" if role == "risk" and self.reject else "approve",
                "reason": "Source verification is required before publication."}

class CEOCycleTests(unittest.TestCase):
    def test_runs_real_role_calls_but_never_publishes(self):
        agent = FakeModel()
        with tempfile.TemporaryDirectory() as directory:
            result = run_cycle(snapshot(), agent, "2026-10-16", directory)
            self.assertEqual(agent.calls, 4)
            self.assertEqual(result["tasks"][0]["state"], "reviewed_draft")
            self.assertFalse(result["production_publish_allowed"])
            self.assertFalse(result["automatic_spend_allowed"])
            self.assertIsNone(result["approved_commissions_minor"])
            self.assertEqual(result["telegram_delivery"], "not_configured")
    def test_idempotent_same_day(self):
        agent = FakeModel()
        with tempfile.TemporaryDirectory() as directory:
            run_cycle(snapshot(), agent, "2026-10-16", directory)
            second = run_cycle(snapshot(), agent, "2026-10-16", directory)
            self.assertEqual(agent.calls, 4)
            self.assertEqual(second["run_id"], "2026-10-16")
    def test_independent_rejection(self):
        with tempfile.TemporaryDirectory() as directory:
            result = run_cycle(snapshot(), FakeModel(reject=True), "2026-10-16", directory)
            self.assertEqual(result["tasks"][0]["state"], "revision_required")
    def test_non_aggregate_data_rejected(self):
        x = snapshot()
        x["analytics"]["visitor_email"] = "person@example.com"
        with self.assertRaises(CycleError):
            module.validate_snapshot(x)
    def test_unverified_commissions_are_not_zero(self):
        x = module.validate_snapshot(snapshot())
        self.assertIsNone(x["verified"]["approved_commissions_minor"])
    def test_unapproved_partnership_is_not_fabricated(self):
        x = module.validate_snapshot(snapshot())
        self.assertEqual(x["partnerships"][0]["status"], "research")
    def test_invalid_endpoints_rejected(self):
        with self.assertRaises(CycleError):
            module.OpenAICompatible("http://example.com/api", {}, None)
    def test_bounded_requests(self):
        client = module.OpenAICompatible("http://localhost:11434/v1/chat/completions",
               {"director": "qwen3.5"}, max_calls=0)
        with self.assertRaises(CycleError):
            client.ask("director", "test", {"a": 1})
    def test_no_duplicate_roles(self):
        class BadCEO(FakeModel):
            def ask(self, role, system, payload):
                if role == "director":
                    t={"role":"research_catalog","title":"Duplicate","instruction":"x","success_criteria":"x"}
                    return {"tasks":[t,t]}
                return super().ask(role,system,payload)
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(CycleError):
                run_cycle(snapshot(), BadCEO(), "2026-10-16", directory)
    def test_invalid_run_id(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(CycleError):
                run_cycle(snapshot(), FakeModel(), "../../", directory)

if __name__ == "__main__":
    unittest.main()
