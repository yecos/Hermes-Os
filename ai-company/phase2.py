"""Phase 2 bridge for authenticated Telegram requests and Hermes delegations.

The bridge deliberately does not launch models itself. A live Hermes Agent
session is the Director: it dispatches native ``delegate_task`` workers and
records their opaque delegation IDs here. Builder claims are never trusted;
this module verifies Git state and reruns the Director-approved test command
before a task can enter review.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import sqlite3
import subprocess
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

from company import BUILDERS, REVIEWERS, Company, WorkflowError, now

COMMIT_SHA = re.compile(r"^[0-9a-f]{40}$")
DELEGATION_ID = re.compile(r"^deleg_[0-9a-f]{8}$")
_GATEWAY_AUTHORITY = object()
_DELEGATION_AUTHORITY = object()


@dataclass(frozen=True)
class TelegramEnvelope:
    platform: str
    chat_type: str
    user_id: str
    chat_id: str
    message_id: str
    text: str
    _authority: object = field(default=None, repr=False, compare=False)

    @classmethod
    def from_gateway_environment(cls, text: str) -> "TelegramEnvelope":
        """Build an envelope only from gateway-bound session metadata."""
        values = {
            "platform": os.environ.get("HERMES_SESSION_PLATFORM", ""),
            "chat_type": os.environ.get("HERMES_SESSION_CHAT_TYPE", ""),
            "user_id": os.environ.get("HERMES_SESSION_USER_ID", ""),
            "chat_id": os.environ.get("HERMES_SESSION_CHAT_ID", ""),
            "message_id": os.environ.get("HERMES_SESSION_MESSAGE_ID", ""),
        }
        if any(not value.strip() for value in values.values()):
            raise WorkflowError("Gateway session metadata is incomplete")
        return cls(text=text, _authority=_GATEWAY_AUTHORITY, **values)


@dataclass(frozen=True)
class HermesDelegationReceipt:
    """Read-only proof that an ID exists in Hermes' durable delegation ledger."""

    delegation_id: str
    state: str
    registry_path: str
    _authority: object = field(default=None, repr=False, compare=False)

    @staticmethod
    def default_registry_path() -> Path:
        if os.environ.get("HERMES_HOME"):
            return Path(os.environ["HERMES_HOME"]) / "state.db"
        if os.environ.get("LOCALAPPDATA"):
            return Path(os.environ["LOCALAPPDATA"]) / "hermes" / "state.db"
        return Path.home() / ".hermes" / "state.db"

    @classmethod
    def from_registry(
        cls,
        delegation_id: str,
        registry_path: str | Path | None = None,
        *,
        require_completed: bool = False,
    ) -> "HermesDelegationReceipt":
        if not DELEGATION_ID.fullmatch(delegation_id):
            raise WorkflowError("Invalid Hermes delegation ID format")
        path = Path(registry_path or cls.default_registry_path()).resolve()
        if not path.is_file():
            raise WorkflowError("Hermes delegation registry does not exist")
        try:
            db = sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True)
            try:
                row = db.execute(
                    "SELECT state FROM async_delegations WHERE delegation_id=?",
                    (delegation_id,),
                ).fetchone()
            finally:
                db.close()
        except sqlite3.Error as exc:
            raise WorkflowError(f"Could not verify Hermes delegation receipt: {exc}") from exc
        if row is None:
            raise WorkflowError("Delegation ID is not present in Hermes' durable registry")
        state = str(row[0])
        if state not in {"running", "completed"}:
            raise WorkflowError(f"Hermes delegation is not usable (state={state})")
        if require_completed and state != "completed":
            raise WorkflowError("Hermes delegation has not completed")
        return cls(delegation_id, state, str(path), _DELEGATION_AUTHORITY)

    def assert_valid(self, *, require_completed: bool = False) -> None:
        if self._authority is not _DELEGATION_AUTHORITY:
            raise WorkflowError("Unverified Hermes delegation receipt")
        current = self.from_registry(
            self.delegation_id,
            self.registry_path,
            require_completed=require_completed,
        )
        if require_completed and current.state != "completed":
            raise WorkflowError("Hermes delegation has not completed")


class _Phase2Store:
    def __init__(self, company: Company):
        self.company = company
        self.db = company.db
        self.db.executescript(
            """
            CREATE TABLE IF NOT EXISTS inbound_requests (
                platform TEXT NOT NULL,
                chat_id TEXT NOT NULL,
                message_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                project_id TEXT,
                task_id TEXT,
                received_at TEXT NOT NULL,
                PRIMARY KEY (platform, chat_id, message_id)
            );
            CREATE TABLE IF NOT EXISTS native_runs (
                id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL REFERENCES tasks(id),
                builder_role TEXT NOT NULL,
                delegation_id TEXT NOT NULL UNIQUE,
                repository TEXT NOT NULL,
                base_sha TEXT NOT NULL,
                verification_command TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'working',
                head_sha TEXT,
                evidence TEXT,
                created_at TEXT NOT NULL,
                completed_at TEXT
            );
            CREATE TABLE IF NOT EXISTS native_reviews (
                run_id TEXT NOT NULL REFERENCES native_runs(id),
                reviewer TEXT NOT NULL,
                delegation_id TEXT NOT NULL UNIQUE,
                head_sha TEXT NOT NULL,
                verdict TEXT NOT NULL,
                note TEXT NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY (run_id, reviewer)
            );
            CREATE UNIQUE INDEX IF NOT EXISTS one_active_native_run_per_task
                ON native_runs(task_id) WHERE status='working';
            """
        )
        self.db.commit()


class TelegramDirectorBridge(_Phase2Store):
    """Authenticate a gateway envelope before creating workflow state."""

    def __init__(self, company: Company, allowed_owner_ids: Iterable[str]):
        super().__init__(company)
        self.allowed_owner_ids = {str(item).strip() for item in allowed_owner_ids if str(item).strip()}
        if not self.allowed_owner_ids:
            raise WorkflowError("At least one Telegram owner ID is required")

    def ingest(
        self,
        envelope: TelegramEnvelope,
        *,
        project_name: str,
        task_title: str,
        builder_role: str,
        acceptance_criteria: str,
    ) -> tuple[str, str]:
        if envelope._authority is not _GATEWAY_AUTHORITY:
            raise WorkflowError("Telegram envelope was not created from gateway session context")
        if envelope.platform != "telegram" or envelope.chat_type != "dm":
            raise WorkflowError("Only authenticated Telegram direct messages are accepted")
        if envelope.user_id not in self.allowed_owner_ids or envelope.chat_id != envelope.user_id:
            raise WorkflowError("Telegram sender is not the authenticated owner")
        if not envelope.message_id.strip() or not envelope.text.strip():
            raise WorkflowError("Telegram message ID and text are required")
        if builder_role not in BUILDERS:
            raise WorkflowError("A valid builder role is required")
        if not project_name.strip() or not task_title.strip() or not acceptance_criteria.strip():
            raise WorkflowError("Project, task and acceptance criteria are required")

        project_id = uuid.uuid4().hex[:12]
        task_id = uuid.uuid4().hex[:12]
        try:
            with self.db:
                self.db.execute(
                    "INSERT INTO inbound_requests VALUES (?,?,?,?,?,?,?)",
                    (
                        envelope.platform,
                        envelope.chat_id,
                        envelope.message_id,
                        envelope.user_id,
                        project_id,
                        task_id,
                        now(),
                    ),
                )
                self.db.execute(
                    "INSERT INTO projects VALUES (?,?,?,?,?)",
                    (project_id, project_name.strip(), envelope.text.strip(), "active", now()),
                )
                self.company._event(
                    project_id,
                    "authenticated_owner",
                    "telegram_request_received",
                    chat_id=envelope.chat_id,
                    message_id=envelope.message_id,
                )
                self.db.execute(
                    "INSERT INTO tasks VALUES (?,?,?,?,?,?,?,?,?)",
                    (
                        task_id,
                        project_id,
                        task_title.strip(),
                        builder_role,
                        acceptance_criteria.strip(),
                        "queued",
                        0,
                        None,
                        now(),
                    ),
                )
                self.company._event(
                    project_id,
                    "director",
                    "task_queued",
                    task_id=task_id,
                    role=builder_role,
                )
        except sqlite3.IntegrityError as exc:
            if "inbound_requests" in str(exc) or "UNIQUE" in str(exc):
                raise WorkflowError("Telegram message was already processed") from exc
            raise
        return project_id, task_id


class NativeDelegationBridge(_Phase2Store):
    """Bind native Hermes delegation IDs to independently verified Git work."""

    @staticmethod
    def _git(repository: Path, *args: str) -> str:
        try:
            result = subprocess.run(
                ["git", "-C", str(repository), *args],
                check=True,
                capture_output=True,
                text=True,
                timeout=30,
            )
        except (OSError, subprocess.SubprocessError) as exc:
            raise WorkflowError(f"Git verification failed: {exc}") from exc
        return result.stdout.strip()

    @staticmethod
    def _require_sha(value: str, label: str) -> str:
        normalized = value.lower()
        if not COMMIT_SHA.fullmatch(normalized):
            raise WorkflowError(f"{label} must be a full 40-character commit SHA")
        return normalized

    def prepare_job(
        self,
        *,
        task_id: str,
        builder_role: str,
        repository: str,
        base_sha: str,
        verification_command: list[str],
    ) -> str:
        task = self.company._row("tasks", task_id)
        if task["role"] != builder_role or builder_role not in BUILDERS:
            raise WorkflowError("Builder role does not match the task")
        if task["state"] != "queued":
            raise WorkflowError("Only a queued task can be delegated")
        project = self.company._row("projects", task["project_id"])
        if project["state"] != "active":
            raise WorkflowError("Project is paused")

        if not verification_command or any(not isinstance(part, str) or not part for part in verification_command):
            raise WorkflowError("A Director-approved verification command is required")

        repo = Path(repository).resolve()
        if not repo.is_dir():
            raise WorkflowError("Repository workspace does not exist")
        base = self._require_sha(base_sha, "Base SHA")
        if self._git(repo, "rev-parse", "HEAD").lower() != base:
            raise WorkflowError("Workspace HEAD does not match the delegated base SHA")
        self._git(repo, "cat-file", "-e", f"{base}^{{commit}}")

        run_id = uuid.uuid4().hex[:12]
        stored_delegation_id = f"pending:{run_id}"
        try:
            with self.db:
                self.db.execute(
                    "INSERT INTO native_runs VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    (
                        run_id,
                        task_id,
                        builder_role,
                        stored_delegation_id,
                        str(repo),
                        base,
                        json.dumps(verification_command),
                        "working",
                        None,
                        None,
                        now(),
                        None,
                    ),
                )
                claimed = self.db.execute(
                    "UPDATE tasks SET state='working' WHERE id=? AND state='queued'",
                    (task_id,),
                )
                if claimed.rowcount != 1:
                    raise WorkflowError("Task was claimed by another native run")
                self.company._event(
                    task["project_id"],
                    "director",
                    "native_job_prepared",
                    task_id=task_id,
                    run_id=run_id,
                    delegation_id=stored_delegation_id,
                    base_sha=base,
                )
        except sqlite3.IntegrityError as exc:
            raise WorkflowError("Task already has an active native run") from exc
        return run_id

    def bind_delegation(self, run_id: str, receipt: HermesDelegationReceipt) -> None:
        """Bind the ID returned by ``delegate_task`` to a prepared run."""
        if not isinstance(receipt, HermesDelegationReceipt):
            raise WorkflowError("A verified Hermes delegation receipt is required")
        receipt.assert_valid()
        delegation_id = receipt.delegation_id
        run = self._run_row(run_id)
        if run["status"] != "working" or not run["delegation_id"].startswith("pending:"):
            raise WorkflowError("Native run is not awaiting a delegation ID")
        task = self.company._row("tasks", run["task_id"])
        try:
            with self.db:
                self.db.execute(
                    "UPDATE native_runs SET delegation_id=? WHERE id=?",
                    (delegation_id, run_id),
                )
                self.company._event(
                    task["project_id"],
                    "director",
                    "native_delegation_started",
                    task_id=run["task_id"],
                    run_id=run_id,
                    delegation_id=delegation_id,
                    base_sha=run["base_sha"],
                )
        except sqlite3.IntegrityError as exc:
            raise WorkflowError("Delegation ID has already been used") from exc

    def _run_row(self, run_id: str):
        row = self.db.execute("SELECT * FROM native_runs WHERE id=?", (run_id,)).fetchone()
        if row is None:
            raise WorkflowError("Unknown native run identifier")
        return row

    def complete_job(
        self,
        run_id: str,
        receipt: HermesDelegationReceipt,
        head_sha: str,
    ) -> dict:
        if not isinstance(receipt, HermesDelegationReceipt):
            raise WorkflowError("A verified Hermes delegation receipt is required")
        receipt.assert_valid(require_completed=True)
        delegation_id = receipt.delegation_id
        run = self._run_row(run_id)
        if run["status"] != "working" or run["delegation_id"] != delegation_id:
            raise WorkflowError("Native run is not active or delegation ID does not match")
        head = self._require_sha(head_sha, "Head SHA")
        repo = Path(run["repository"])
        actual_head = self._git(repo, "rev-parse", "HEAD").lower()
        if actual_head != head:
            raise WorkflowError("Reported head SHA does not match the workspace")
        if head == run["base_sha"]:
            raise WorkflowError("Builder did not produce a new commit")
        self._git(repo, "merge-base", "--is-ancestor", run["base_sha"], head)
        changed_files = [
            item for item in self._git(repo, "diff", "--name-only", f"{run['base_sha']}..{head}").splitlines()
            if item.strip()
        ]
        if not changed_files:
            raise WorkflowError("Builder commit contains no changed files")
        if self._git(repo, "status", "--porcelain", "--untracked-files=all"):
            raise WorkflowError("Workspace must be clean before verification")

        command = json.loads(run["verification_command"])
        try:
            verified = subprocess.run(
                command,
                cwd=str(repo),
                capture_output=True,
                text=True,
                timeout=300,
            )
        except (OSError, subprocess.SubprocessError) as exc:
            raise WorkflowError(f"Verification command could not run: {exc}") from exc
        output = (verified.stdout or "") + (verified.stderr or "")
        verification = {
            "command": command,
            "returncode": verified.returncode,
            "output_sha256": hashlib.sha256(output.encode("utf-8")).hexdigest(),
            "output_excerpt": output[-8000:],
        }
        if verified.returncode != 0:
            raise WorkflowError("Director verification command failed")
        if self._git(repo, "status", "--porcelain", "--untracked-files=all"):
            raise WorkflowError("Verification command modified the workspace")

        evidence = {
            "kind": "verified_native_delegation",
            "delegation_id": delegation_id,
            "repository": str(repo),
            "base_sha": run["base_sha"],
            "head_sha": head,
            "changed_files": changed_files,
            "verification": verification,
        }
        evidence_json = json.dumps(evidence, ensure_ascii=False, sort_keys=True)
        task = self.company._row("tasks", run["task_id"])
        with self.db:
            self.db.execute(
                "UPDATE tasks SET state='review',evidence=? WHERE id=? AND state='working'",
                (evidence_json, run["task_id"]),
            )
            self.db.execute("DELETE FROM reviews WHERE task_id=?", (run["task_id"],))
            self.db.execute(
                "UPDATE native_runs SET status='completed',head_sha=?,evidence=?,completed_at=? WHERE id=?",
                (head, evidence_json, now(), run_id),
            )
            self.company._event(
                task["project_id"],
                run["builder_role"],
                "native_delegation_verified",
                task_id=run["task_id"],
                run_id=run_id,
                delegation_id=delegation_id,
                head_sha=head,
                changed_files=changed_files,
                output_sha256=verification["output_sha256"],
            )
        return evidence

    def record_review(
        self,
        *,
        run_id: str,
        reviewer: str,
        receipt: HermesDelegationReceipt,
        head_sha: str,
        approved: bool,
        note: str,
    ) -> None:
        if not isinstance(receipt, HermesDelegationReceipt):
            raise WorkflowError("A verified Hermes delegation receipt is required")
        receipt.assert_valid(require_completed=True)
        delegation_id = receipt.delegation_id
        run = self._run_row(run_id)
        if run["status"] != "completed" or not run["head_sha"]:
            raise WorkflowError("Only a completed native run can be reviewed")
        if reviewer not in REVIEWERS or not note.strip():
            raise WorkflowError("A valid independent reviewer and note are required")
        head = self._require_sha(head_sha, "Review SHA")
        if head != run["head_sha"]:
            raise WorkflowError("Review is not bound to the builder's exact commit")
        if delegation_id == run["delegation_id"]:
            raise WorkflowError("Reviewer must use an independent native delegation")
        exists = self.db.execute(
            "SELECT 1 FROM native_reviews WHERE run_id=? AND reviewer=?",
            (run_id, reviewer),
        ).fetchone()
        if exists:
            raise WorkflowError("Reviewer has already submitted a verdict for this run")
        used = self.db.execute(
            "SELECT 1 FROM native_reviews WHERE delegation_id=?", (delegation_id,)
        ).fetchone()
        if used:
            raise WorkflowError("Each supervisor must use a distinct native delegation")

        task = self.company._row("tasks", run["task_id"])
        if task["state"] != "review":
            raise WorkflowError("Task is not awaiting review")
        with self.db:
            self.db.execute(
                "INSERT INTO native_reviews VALUES (?,?,?,?,?,?,?)",
                (
                    run_id,
                    reviewer,
                    delegation_id,
                    head,
                    "approved" if approved else "rejected",
                    note.strip(),
                    now(),
                ),
            )
            self.db.execute(
                "INSERT INTO reviews VALUES (?,?,?,?,?) ON CONFLICT(task_id,reviewer) "
                "DO UPDATE SET verdict=excluded.verdict,note=excluded.note,created_at=excluded.created_at",
                (
                    run["task_id"],
                    reviewer,
                    "approved" if approved else "rejected",
                    note.strip(),
                    now(),
                ),
            )
            self.company._event(
                task["project_id"],
                reviewer,
                "task_reviewed",
                task_id=run["task_id"],
                approved=approved,
                note=note.strip(),
            )
            if not approved:
                attempts = task["attempts"] + 1
                new_state = "blocked" if attempts >= 3 else "queued"
                self.db.execute(
                    "UPDATE tasks SET state=?,attempts=? WHERE id=?",
                    (new_state, attempts, run["task_id"]),
                )
                self.db.execute("DELETE FROM reviews WHERE task_id=?", (run["task_id"],))
                self.company._event(
                    task["project_id"],
                    "architect",
                    "revision_requested",
                    task_id=run["task_id"],
                    attempts=attempts,
                    state=new_state,
                )
                if attempts == 2:
                    self.company._event(
                        task["project_id"],
                        "architect",
                        "escalation_architect",
                        task_id=run["task_id"],
                        reason="two rejected attempts",
                    )
                if attempts >= 3:
                    self.company._event(
                        task["project_id"],
                        "director",
                        "director_attention_required",
                        task_id=run["task_id"],
                        reason="three rejected attempts",
                    )
            else:
                count = self.db.execute(
                    "SELECT count(*) FROM reviews WHERE task_id=? AND verdict='approved'",
                    (run["task_id"],),
                ).fetchone()[0]
                if count == 2:
                    self.db.execute(
                        "UPDATE tasks SET state='accepted' WHERE id=?",
                        (run["task_id"],),
                    )
                    self.company._event(
                        task["project_id"],
                        "director",
                        "task_accepted",
                        task_id=run["task_id"],
                    )
            self.company._event(
                task["project_id"],
                reviewer,
                "native_review_recorded",
                task_id=run["task_id"],
                run_id=run_id,
                delegation_id=delegation_id,
                head_sha=head,
                approved=approved,
            )

    def run_status(self, run_id: str) -> dict:
        run = dict(self._run_row(run_id))
        run["reviews"] = [
            dict(row)
            for row in self.db.execute(
                "SELECT reviewer,delegation_id,head_sha,verdict,note,created_at "
                "FROM native_reviews WHERE run_id=? ORDER BY reviewer",
                (run_id,),
            )
        ]
        return run
