"""Tests for the versioned six-agent model and authority configuration."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from agent_models import REQUIRED_ROLES, load_agent_models
from company import WorkflowError


class AgentModelConfigTests(unittest.TestCase):
    def setUp(self):
        self.path = Path(__file__).resolve().parents[1] / "agent-models.json"

    def test_all_six_roles_have_explicit_provider_model_and_budget(self):
        config = load_agent_models(self.path)
        self.assertEqual(set(config["roles"]), REQUIRED_ROLES)
        for role, item in config["roles"].items():
            self.assertTrue(item["provider"], role)
            self.assertTrue(item["model"], role)
            self.assertIn(item["tier"], {"high", "medium", "economy"})
            self.assertGreater(item["max_iterations"], 0)
            self.assertGreater(item["max_runtime_seconds"], 0)

    def test_authority_matches_one_director_two_reviewers_three_builders(self):
        config = load_agent_models(self.path)
        roles = config["roles"]
        self.assertEqual(roles["director"]["authority"], "orchestrator")
        for reviewer in ("product", "architect"):
            self.assertEqual(roles[reviewer]["authority"], "reviewer")
            self.assertTrue(roles[reviewer]["read_only"])
        for builder in ("frontend", "backend", "integrations"):
            self.assertEqual(roles[builder]["authority"], "builder")
            self.assertFalse(roles[builder]["read_only"])

    def test_configuration_contains_no_secret_fields_or_values(self):
        config = load_agent_models(self.path)
        forbidden = {"api_key", "token", "password", "secret", "oauth"}
        for role, item in config["roles"].items():
            self.assertFalse(forbidden.intersection({key.lower() for key in item}), role)
            for value in item.values():
                if isinstance(value, str):
                    self.assertNotIn("sk-", value.lower())
                    self.assertNotIn("nvapi-", value.lower())

    def test_unknown_or_missing_roles_are_rejected(self):
        with self.assertRaises(WorkflowError):
            load_agent_models({"version": 1, "roles": {"director": {}}})


if __name__ == "__main__":
    unittest.main()
