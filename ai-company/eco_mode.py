"""Codex-only ECO policy, persistent execution guard and honest usage ledger.

This is a library and CLI for the future dispatcher, NOT a live Hermes hook.
The dispatcher must call reserve() before launching a model and finish() after
collecting real provider/Hermes telemetry. No usage is inferred from model text.
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import time
from pathlib import Path

from agent_models import REQUIRED_ROLES, load_agent_models

ROOT = Path(__file__).resolve().parent
POLICY_PATH = ROOT / "eco-policy.json"
MODEL_PATH = ROOT / "agent-models.json"
BUILDERS = frozenset({"frontend", "backend", "integrations"})
REVIEWERS = frozenset({"product", "architect"})
FINAL_STATES = frozenset({"ok", "failed", "rate_limited", "cancelled"})


class EcoError(ValueError):
    pass


def load_policy(path: str | Path = POLICY_PATH, models_path: str | Path = MODEL_PATH) -> dict:
    try:
        config = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise EcoError(f"Invalid ECO policy: {exc}") from exc

    if config.get("schema_version") != 1 or config.get("mode") != "codex_balanced":
        raise EcoError("Only ECO policy v1 codex_balanced is supported")
    if config.get("provider") != "openai-codex":
        raise EcoError("ECO mode must remain Codex-only")
    roles = config.get("roles")
    if not isinstance(roles, dict) or set(roles) != REQUIRED_ROLES:
        raise EcoError("ECO mode must define all six roles")
    model_roles = load_agent_models(models_path)["roles"]
    for role, values in roles.items():
        parent = model_roles[role]
        if not isinstance(values, dict):
            raise EcoError(f"Invalid role {role}")
        if parent["availability_status"] != "verified":
            raise EcoError(f"Model for {role} is not verified")
        if values.get("model") != parent["model"]:
            raise EcoError(f"Unauthorized model override for {role}")
        if values.get("profile") != "company" + role:
            raise EcoError(f"Unexpected profile for {role}")
        for key in ("max_iterations", "max_runtime_seconds", "max_output_tokens"):
            value = values.get(key)
            if type(value) is not int or not 1 <= value <= parent[key]:
                raise EcoError(f"Invalid {key} limit for {role}")

    limits = config.get("limits")
    if not isinstance(limits, dict):
        raise EcoError("Missing ECO execution limits")
    for key in (
        "max_active_directors", "max_active_builders", "max_active_reviewers",
        "daily_starts_per_project", "daily_starts_per_role_per_project"
    ):
        if type(limits.get(key)) is not int or limits[key] < 1:
            raise EcoError(f"Invalid {key}")
    pauses = limits.get("backoff_seconds_on_429")
    if not isinstance(pauses, list) or not pauses or any(
        type(v) is not int or v < 60 for v in pauses
    ):
        raise EcoError("Invalid 429 backoff schedule")
    if limits.get("require_idempotent_run_ids") is not True:
        raise EcoError("Idempotent run IDs are required")
    if limits.get("require_reconciliation_before_retry") is not True:
        raise EcoError("Crash reconciliation is required")
    if limits.get("nested_delegation_allowed") is not False:
        raise EcoError("Nested delegation must be disabled in ECO mode")
    return config


def _role_group(role: str) -> str:
    if role in BUILDERS:
        return "builders"
    if role in REVIEWERS:
        return "reviewers"
    return "directors"


class EcoUsageLedger:
    """Fail-closed reservation and metering for a single SQLite dispatcher host.

    Running leases are NOT cleared on a timer: after a crash a supervisor must
    reconcile real OS processes/session IDs before finishing or retrying them.
    """

    def __init__(self, db_path: str | Path, policy: dict | None = None):
        self.policy = policy if policy is not None else load_policy()
        self.db = sqlite3.connect(str(db_path), timeout=10, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA busy_timeout=10000")
        self.db.executescript("""
        CREATE TABLE IF NOT EXISTS eco_runs (
            run_id TEXT PRIMARY KEY, project_id TEXT NOT NULL,
            role TEXT NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL,
            status TEXT NOT NULL, started_at REAL NOT NULL,
            finished_at REAL, session_id TEXT,
            input_tokens INTEGER, output_tokens INTEGER,
            cached_input_tokens INTEGER, usage_source TEXT NOT NULL DEFAULT 'unknown'
        );
        CREATE INDEX IF NOT EXISTS idx_eco_runs_project_time
            ON eco_runs(project_id, started_at);
        CREATE TABLE IF NOT EXISTS eco_cooldowns (
            provider TEXT PRIMARY KEY, strikes INTEGER NOT NULL,
            until_ts REAL NOT NULL
        );
        """)

    def close(self) -> None:
        self.db.close()

    def reserve(self, *, run_id: str, project_id: str, role: str, at: float | None = None) -> str:
        if not all(isinstance(v, str) and v.strip() for v in (run_id, project_id, role)):
            raise EcoError("Nonblank run, project and role IDs are required")
        if role not in self.policy["roles"]:
            raise EcoError("Unknown ECO role")
        now = time.time() if at is None else at
        limits = self.policy["limits"]
        provider = self.policy["provider"]
        model = self.policy["roles"][role]["model"]

        self.db.execute("BEGIN IMMEDIATE")
        try:
            old = self.db.execute(
                "SELECT project_id,role,status FROM eco_runs WHERE run_id=?", (run_id,)
            ).fetchone()
            if old:
                if old["project_id"] != project_id or old["role"] != role:
                    raise EcoError("Run ID is already bound to a different task")
                result = "already_" + old["status"]
            else:
                cooldown = self.db.execute(
                    "SELECT until_ts FROM eco_cooldowns WHERE provider=?", (provider,)
                ).fetchone()
                if cooldown and cooldown["until_ts"] > now:
                    raise EcoError("Codex provider is cooling down after HTTP 429")

                day_start = now - (now % 86400)
                count = self.db.execute(
                    "SELECT COUNT(*) FROM eco_runs WHERE project_id=? AND started_at>=?",
                    (project_id, day_start)
                ).fetchone()[0]
                role_count = self.db.execute(
                    "SELECT COUNT(*) FROM eco_runs WHERE project_id=? AND role=? AND started_at>=?",
                    (project_id, role, day_start)
                ).fetchone()[0]
                if count >= limits["daily_starts_per_project"]:
                    raise EcoError("Daily project session-start budget exhausted")
                if role_count >= limits["daily_starts_per_role_per_project"]:
                    raise EcoError("Daily role session-start budget exhausted")

                group = _role_group(role)
                peers = (tuple(BUILDERS) if group == "builders" else
                         tuple(REVIEWERS) if group == "reviewers" else ("director",))
                markers = ",".join("?" for _ in peers)
                active = self.db.execute(
                    f"SELECT COUNT(*) FROM eco_runs WHERE status='running' "
                    f"AND role IN ({markers})", peers
                ).fetchone()[0]
                if active >= limits["max_active_" + group]:
                    raise EcoError(f"Concurrent {group} limit reached")
                self.db.execute(
                    "INSERT INTO eco_runs "
                    "(run_id,project_id,role,provider,model,status,started_at) "
                    "VALUES (?,?,?,?,?,'running',?)",
                    (run_id, project_id, role, provider, model, now)
                )
                result = "reserved"
            self.db.commit()
            return result
        except BaseException:
            self.db.rollback()
            raise

    def finish(
        self, *, run_id: str, status: str, session_id: str | None = None,
        input_tokens: int | None = None, output_tokens: int | None = None,
        cached_input_tokens: int | None = None, usage_source: str = "unknown",
        at: float | None = None
    ) -> None:
        if status not in FINAL_STATES:
            raise EcoError("Unsupported completion state")
        if usage_source not in {"unknown", "hermes", "provider"}:
            raise EcoError("Usage source must be provider, hermes or unknown")
        metrics = (input_tokens, output_tokens)
        if (metrics[0] is None) != (metrics[1] is None):
            raise EcoError("Supply both input/output tokens or leave both unknown")
        if any(x is not None and (type(x) is not int or x < 0) for x in
               (input_tokens, output_tokens, cached_input_tokens)):
            raise EcoError("Tokens must be nonnegative integers")
        if input_tokens is None:
            if cached_input_tokens is not None or usage_source != "unknown":
                raise EcoError("Unknown usage must not masquerade as measured tokens")
        elif usage_source == "unknown":
            raise EcoError("Measured tokens require a telemetry source")
        if cached_input_tokens is not None and cached_input_tokens > input_tokens:
            raise EcoError("Cached tokens cannot exceed input tokens")
        if session_id is not None and (not isinstance(session_id, str) or not session_id.strip()):
            raise EcoError("Invalid session ID")

        now = time.time() if at is None else at
        self.db.execute("BEGIN IMMEDIATE")
        try:
            run = self.db.execute(
                "SELECT status,provider FROM eco_runs WHERE run_id=?", (run_id,)
            ).fetchone()
            if run is None or run["status"] != "running":
                raise EcoError("Only existing running reservations can finish")
            self.db.execute(
                "UPDATE eco_runs SET status=?,finished_at=?,session_id=?,"
                "input_tokens=?,output_tokens=?,cached_input_tokens=?,usage_source=? "
                "WHERE run_id=?",
                (status, now, session_id, input_tokens, output_tokens,
                 cached_input_tokens, usage_source, run_id)
            )
            if status == "rate_limited":
                old = self.db.execute(
                    "SELECT strikes FROM eco_cooldowns WHERE provider=?", (run["provider"],)
                ).fetchone()
                strikes = (old["strikes"] if old else 0) + 1
                pauses = self.policy["limits"]["backoff_seconds_on_429"]
                until = now + pauses[min(strikes - 1, len(pauses) - 1)]
                self.db.execute(
                    "INSERT INTO eco_cooldowns(provider,strikes,until_ts) VALUES(?,?,?) "
                    "ON CONFLICT(provider) DO UPDATE SET strikes=excluded.strikes,"
                    "until_ts=excluded.until_ts",
                    (run["provider"], strikes, until)
                )
            elif status == "ok":
                existing = self.db.execute(
                    "SELECT until_ts FROM eco_cooldowns WHERE provider=?", (run["provider"],)
                ).fetchone()
                if existing and existing["until_ts"] <= now:
                    self.db.execute(
                        "DELETE FROM eco_cooldowns WHERE provider=?", (run["provider"],)
                    )
            self.db.commit()
        except BaseException:
            self.db.rollback()
            raise

    def report(self, project_id: str | None = None) -> dict:
        sql = "SELECT * FROM eco_runs"
        args: tuple = ()
        if project_id is not None:
            sql += " WHERE project_id=?"
            args = (project_id,)
        records = self.db.execute(sql, args).fetchall()
        roles: dict[str, dict] = {}
        for run in records:
            bucket = roles.setdefault(run["role"], {
                "starts": 0, "completed": 0, "rate_limited": 0,
                "sessions_with_token_telemetry": 0, "sessions_without_token_telemetry": 0,
                "input_tokens": 0, "output_tokens": 0, "cached_input_tokens": 0
            })
            bucket["starts"] += 1
            bucket["completed"] += run["status"] != "running"
            bucket["rate_limited"] += run["status"] == "rate_limited"
            if run["input_tokens"] is None:
                bucket["sessions_without_token_telemetry"] += 1
            else:
                bucket["sessions_with_token_telemetry"] += 1
                bucket["input_tokens"] += run["input_tokens"]
                bucket["output_tokens"] += run["output_tokens"]
                bucket["cached_input_tokens"] += run["cached_input_tokens"] or 0
        return {
            "measurement": "locally_recorded_sessions_only",
            "subscription_quota_observed": False,
            "tokens_are_not_subscription_credits": True,
            "project_id": project_id,
            "total_starts": len(records),
            "roles": roles
        }


def main() -> None:
    parser = argparse.ArgumentParser(description="Hermes Company ECO guard and usage ledger")
    parser.add_argument("--db", default=str(ROOT / "ai-company-eco.sqlite3"))
    actions = parser.add_subparsers(dest="action", required=True)
    start = actions.add_parser("reserve")
    start.add_argument("--run-id", required=True)
    start.add_argument("--project", required=True)
    start.add_argument("--role", required=True)
    finish = actions.add_parser("finish")
    finish.add_argument("--run-id", required=True)
    finish.add_argument("--status", required=True, choices=sorted(FINAL_STATES))
    finish.add_argument("--session-id")
    finish.add_argument("--input-tokens", type=int)
    finish.add_argument("--output-tokens", type=int)
    finish.add_argument("--cached-input-tokens", type=int)
    finish.add_argument("--usage-source", default="unknown", choices=["unknown", "provider", "hermes"])
    report = actions.add_parser("report")
    report.add_argument("--project")
    args = parser.parse_args()
    try:
        policy = load_policy()
        ledger = EcoUsageLedger(args.db, policy)
        try:
            if args.action == "reserve":
                result = {"ok": True, "reservation": ledger.reserve(
                    run_id=args.run_id, project_id=args.project, role=args.role)}
            elif args.action == "finish":
                ledger.finish(run_id=args.run_id, status=args.status, session_id=args.session_id,
                              input_tokens=args.input_tokens, output_tokens=args.output_tokens,
                              cached_input_tokens=args.cached_input_tokens,
                              usage_source=args.usage_source)
                result = {"ok": True}
            else:
                result = ledger.report(args.project)
        finally:
            ledger.close()
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (EcoError, ValueError, sqlite3.Error) as exc:
        parser.exit(2, json.dumps({"ok": False, "error": str(exc)}) + "\n")


if __name__ == "__main__":
    main()
