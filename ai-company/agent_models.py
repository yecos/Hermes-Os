"""Load and validate the versioned six-agent model configuration."""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from company import WorkflowError

REQUIRED_ROLES = frozenset(
    {"director", "product", "architect", "frontend", "backend", "integrations"}
)
_REQUIRED_FIELDS = frozenset(
    {
        "provider",
        "model",
        "maximum_model",
        "availability_status",
        "tier",
        "authority",
        "read_only",
        "max_iterations",
        "max_runtime_seconds",
        "max_output_tokens",
        "toolsets",
    }
)
_FORBIDDEN_KEYS = frozenset({"api_key", "token", "password", "secret", "oauth"})


def _read(source: str | Path | dict[str, Any]) -> dict[str, Any]:
    if isinstance(source, dict):
        return source
    path = Path(source)
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise WorkflowError(f"Could not load agent model configuration: {exc}") from exc


def load_agent_models(source: str | Path | dict[str, Any]) -> dict[str, Any]:
    config = _read(source)
    if (
        config.get("version") != 2
        or config.get("selection_policy") != "strict_maximum"
        or not isinstance(config.get("roles"), dict)
    ):
        raise WorkflowError("Agent model configuration version 2 with strict maxima is required")
    roles = config["roles"]
    if set(roles) != REQUIRED_ROLES:
        raise WorkflowError("Agent model configuration must define exactly the six company roles")

    for role, item in roles.items():
        if not isinstance(item, dict) or not _REQUIRED_FIELDS.issubset(item):
            raise WorkflowError(f"Role {role} is missing required model or budget fields")
        if _FORBIDDEN_KEYS.intersection({key.lower() for key in item}):
            raise WorkflowError(f"Role {role} contains a forbidden secret field")
        if item["tier"] not in {"high", "medium", "economy"}:
            raise WorkflowError(f"Role {role} has an invalid tier")
        if not isinstance(item["provider"], str) or not item["provider"].strip():
            raise WorkflowError(f"Role {role} requires a provider")
        availability = item["availability_status"]
        if availability == "verified":
            if not isinstance(item["model"], str) or not item["model"].strip():
                raise WorkflowError(f"Role {role} requires a verified model")
        elif availability == "pending_authorization":
            if item["model"] is not None:
                raise WorkflowError(f"Role {role} must remain unset while authorization is pending")
        else:
            raise WorkflowError(f"Role {role} has an invalid availability status")
        for field in ("max_iterations", "max_runtime_seconds", "max_output_tokens"):
            if not isinstance(item[field], int) or item[field] <= 0:
                raise WorkflowError(f"Role {role} requires a positive {field}")
        if not isinstance(item["toolsets"], list) or not all(
            isinstance(value, str) and value for value in item["toolsets"]
        ):
            raise WorkflowError(f"Role {role} requires explicit toolsets")

    expected_authority = {
        "director": ("orchestrator", False),
        "product": ("reviewer", True),
        "architect": ("reviewer", True),
        "frontend": ("builder", False),
        "backend": ("builder", False),
        "integrations": ("builder", False),
    }
    for role, (authority, read_only) in expected_authority.items():
        if roles[role]["authority"] != authority or roles[role]["read_only"] is not read_only:
            raise WorkflowError(f"Role {role} has invalid authority boundaries")

    strict_maxima = {
        "director": "gpt-5.6-sol",
        "product": "gpt-6-luna",
        "architect": "gpt-6-luna",
        "frontend": "gpt-5.6-luna",
        "backend": "gpt-5.6-luna",
        "integrations": "gpt-5.6-luna",
    }
    for role, maximum in strict_maxima.items():
        item = roles[role]
        if item["maximum_model"] != maximum:
            raise WorkflowError(f"Role {role} has an invalid maximum model")
        if item["availability_status"] not in {"verified", "pending_authorization"}:
            raise WorkflowError(f"Role {role} has an invalid availability status")
        if item["availability_status"] == "verified" and item["model"] != maximum:
            raise WorkflowError(f"Role {role} must use its verified strict maximum")
        if item["availability_status"] == "pending_authorization" and item["model"] is not None:
            raise WorkflowError(f"Role {role} must remain unset while authorization is pending")
    return config
