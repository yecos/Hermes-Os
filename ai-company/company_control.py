"""Hermes AI Company: bounded, durable native-Kanban orchestrator (no LLM in controller).

Requires Hermes Agent 0.20.2, six existing profiles, one existing gateway,
and 'kanban.dispatch_in_gateway=false'. CLI commands only run locally.
No deployment or merging. No worker starts before 'approve JOB_ID'.
"""
from __future__ import annotations
import argparse
import json
import os
import re
import sqlite3
import subprocess
import sys
import time
import uuid
from pathlib import Path
from eco_mode import EcoError, EcoUsageLedger, load_policy

ROOT = Path(__file__).resolve().parent
HERMES_LOCAL = Path(os.environ.get("LOCALAPPDATA") or (Path.home() / "AppData" / "Local")) / "hermes"
STATE = HERMES_LOCAL / "company-control" / "company-control.sqlite3"
BOARD = HERMES_LOCAL / "kanban" / "boards" / "hermes-ai-company" / "kanban.db"
BOARD_NAME = "hermes-ai-company"
SHA = re.compile(r"\b[a-f0-9]{40}\b")
BUILDER_ROLES = frozenset({"frontend", "backend", "integrations"})
REVIEWERS = ("product", "architect")
TERMINAL = {"done", "blocked", "archived"}

def invoke(*args: str) -> str:
    # Absolutely never shell=True: text from Telegram must not become a command.
    cp = subprocess.run(list(args), capture_output=True, text=True, encoding="utf-8",
                        errors="replace", timeout=55, stdin=subprocess.DEVNULL)
    if cp.returncode:
        raise RuntimeError(f"Command failed ({args[0]} / {args[1] if len(args)>1 else ''}), exit={cp.returncode}: {cp.stderr[-400:]}")
    return cp.stdout

def native(*args: str) -> str:
    return invoke("hermes", "kanban", "--board", BOARD_NAME, *args)

def git(repo: str, *args: str) -> str:
    return invoke("git", "-C", repo, *args).strip()

def db_connect(path: Path) -> sqlite3.Connection:
    db = sqlite3.connect(str(path), timeout=10, isolation_level=None)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA busy_timeout=10000")
    return db

def read_native(dbpath: Path, task_id: str | None = None, key: str | None = None):
    if not dbpath.is_file():
        raise RuntimeError("Native Kanban board database is unavailable")
    with sqlite3.connect(f"file:{dbpath.as_posix()}?mode=ro", uri=True) as conn:
        conn.row_factory = sqlite3.Row
        if key:
            row = conn.execute("SELECT * FROM tasks WHERE idempotency_key=?", (key,)).fetchone()
        else:
            row = conn.execute("SELECT * FROM tasks WHERE id=?", (task_id,)).fetchone()
        if row is None:
            return None
        run = conn.execute("SELECT * FROM task_runs WHERE task_id=? ORDER BY id DESC LIMIT 1", (row["id"],)).fetchone()
        return dict(row), dict(run) if run else None

class Controller:
    def __init__(self, path: Path = STATE, board_path: Path = BOARD):
        self.path, self.board_path = Path(path), Path(board_path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.db = db_connect(self.path)
        self.db.executescript("""
        CREATE TABLE IF NOT EXISTS jobs (
          id TEXT PRIMARY KEY, title TEXT NOT NULL, spec TEXT NOT NULL,
          repository TEXT NOT NULL, base_sha TEXT NOT NULL,
          role TEXT NOT NULL, status TEXT NOT NULL, stage TEXT NOT NULL,
          commit_sha TEXT, submitted_at INTEGER NOT NULL,
          updated_at INTEGER NOT NULL, error TEXT
        );
        CREATE TABLE IF NOT EXISTS stages (
          id TEXT PRIMARY KEY, job_id TEXT NOT NULL, role TEXT NOT NULL,
          task_id TEXT, status TEXT NOT NULL, created_at INTEGER NOT NULL,
          updated_at INTEGER NOT NULL, evidence TEXT,
          FOREIGN KEY(job_id) REFERENCES jobs(id)
        );
        CREATE INDEX IF NOT EXISTS idx_stage_job ON stages(job_id);
        """)
        self.policy = load_policy()
        self.guard = EcoUsageLedger(self.path.parent / "company-control-usage.sqlite3", self.policy)

    def close(self):
        self.guard.close()
        self.db.close()

    def submit(self, *, title: str, spec: str, repository: str, role: str) -> str:
        if role not in BUILDER_ROLES:
            raise ValueError("Only a builder can start a company project")
        if not title.strip() or not spec.strip() or len(spec) > 5000:
            raise ValueError("Provide a title and a specific brief <= 5000 characters")
        repo = str(Path(repository).resolve(strict=True))
        if not (Path(repo) / ".git").exists() and git(repo, "rev-parse", "--is-inside-work-tree") != "true":
            raise ValueError("Repository must be a Git worktree")
        if git(repo, "status", "--porcelain"):
            raise ValueError("Repository must be clean before submission")
        base = git(repo, "rev-parse", "HEAD")
        if not SHA.fullmatch(base):
            raise ValueError("Cannot resolve base commit")
        job_id = "job-" + uuid.uuid4().hex[:12]
        ts = int(time.time())
        self.db.execute(
            "INSERT INTO jobs VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            (job_id, title.strip(), spec.strip(), repo, base, role,
             "pending_approval", "builder", None, ts, ts, None)
        )
        return job_id

    def row(self, jobid: str):
        row = self.db.execute("SELECT * FROM jobs WHERE id=?", (jobid,)).fetchone()
        if not row:
            raise ValueError("Unknown job")
        return dict(row)

    def stages(self, jobid: str):
        return [dict(x) for x in self.db.execute(
            "SELECT * FROM stages WHERE job_id=? ORDER BY created_at,id", (jobid,))]

    def update_job(self, jobid: str, status: str, *, stage: str | None = None,
                   commit: str | None = None, error: str | None = None):
        job = self.row(jobid)
        self.db.execute(
            "UPDATE jobs SET status=?,stage=?,commit_sha=?,error=?,updated_at=? WHERE id=?",
            (status, stage or job["stage"], commit or job["commit_sha"],
             error, int(time.time()), jobid)
        )

    def approve(self, jobid: str):
        row = self.row(jobid)
        if row["status"] != "pending_approval":
            raise ValueError("Only a pending request can be approved")
        self.update_job(jobid, "queued")

    def finish_approval(self, jobid: str):
        row = self.row(jobid)
        if row["status"] != "awaiting_owner":
            raise ValueError("Both reviews must succeed before owner acceptance")
        self.update_job(jobid, "accepted")

    def job_report(self, jobid: str):
        job = self.row(jobid)
        stages = self.stages(jobid)
        for step in stages:
            if step["task_id"]:
                raw = read_native(self.board_path, task_id=step["task_id"])
                step["native_status"] = raw[0]["status"] if raw else "missing"
        return {"job": job, "steps": stages,
                "usage": self.guard.report(jobid),
                "note": "Usage missing from Hermes is unknown, not zero; no production deployment"}

    def _create_task(self, job: dict, role: str):
        if role not in BUILDER_ROLES | set(REVIEWERS):
            raise RuntimeError("Unrecognized role")
        stage_id = job["id"] + ":" + role
        if self.db.execute("SELECT 1 FROM stages WHERE id=?", (stage_id,)).fetchone():
            return
        conf = self.policy["roles"][role]
        if role in BUILDER_ROLES:
            body = (
                "HERMES AI COMPANY ECO. Implement the following bounded specification "
                "in your ISOLATED Git worktree. No deployment, no merge, no new Kanban "
                "cards, no nested delegation. Run minimal deterministic tests, "
                "create ONE Git commit and complete this task using kanban_complete. "
                "In the completion summary provide the exact FULL 40-char HEAD commit "
                "SHA and test result. Do not report success without a real commit.\n"
                f"BASE SHA: {job['base_sha']}\nSPEC:\n{job['spec']}"
            )
            workspace = "worktree:" + job["repository"]
        else:
            if not job["commit_sha"]:
                raise RuntimeError("Reviewer cannot run without verified builder commit SHA")
            body = (
                "HERMES AI COMPANY ECO INDEPENDENT READ-ONLY REVIEW. Review the "
                "EXACT commit SHA below. Do not modify any files, create commits, "
                "launch other models or change Kanban cards. Inspect "
                "the Git diff and test results; return your real independent "
                "verdict. If acceptable, call kanban_complete with a result "
                f"containing precisely APPROVED:{job['commit_sha']} "
                f"(and explanation). Otherwise return REJECTED:{job['commit_sha']} "
                "with blocking findings; NEVER auto-approve.\n"
                f"REPOSITORY: {job['repository']}\nBASE_SHA: {job['base_sha']}\n"
                f"REVIEW_SHA: {job['commit_sha']}\n"
                f"ORIGINAL REQUIREMENTS: {job['spec']}"
            )
            workspace = "dir:" + job["repository"]
        key = stage_id
        existing = read_native(self.board_path, key=key)
        if not existing:
            native(
                "create", f"{job['title']} [{role}]", "--body", body,
                "--assignee", conf["profile"], "--workspace", workspace,
                "--max-retries", "1", "--max-runtime", str(conf["max_runtime_seconds"]),
                "--model", conf["model"], "--provider", self.policy["provider"],
                "--idempotency-key", key, "--initial-status", "blocked", "--json"
            )
            existing = read_native(self.board_path, key=key)
        if not existing or existing[0]["status"] not in {"blocked", "ready", "todo"}:
            raise RuntimeError("Native Kanban task was not safely created as blocked")
        ts = int(time.time())
        self.db.execute(
            "INSERT OR IGNORE INTO stages VALUES (?,?,?,?,?,?,?,?)",
            (stage_id, job["id"], role, existing[0]["id"], "created",
             ts, ts, None)
        )
        self.update_job(job["id"], "stage_queued", stage=role)

    def _stop(self, job: dict, explanation: str):
        self.update_job(job["id"], "blocked", error=explanation[:1200])

    def _verify_builder(self, job: dict, task: dict, run: dict | None) -> str:
        if not run or run.get("profile") != self.policy["roles"][job["role"]]["profile"]:
            raise RuntimeError("Builder native run profile did not match the approved model profile")
        if run.get("outcome") not in {"completed", None}:
            raise RuntimeError("Builder native run did not complete successfully")
        workspace = str(task.get("workspace_path") or "").strip()
        if not workspace or not Path(workspace).is_dir():
            raise RuntimeError("Builder native worktree does not exist")
        head = git(workspace, "rev-parse", "HEAD").lower()
        if not SHA.fullmatch(head) or head == job["base_sha"]:
            raise RuntimeError("Builder did not create a valid new commit")
        git(workspace, "cat-file", "-e", head + "^{commit}")
        # Git exit status is authoritative for the ancestry check.
        git(workspace, "merge-base", "--is-ancestor", job["base_sha"], head)
        if git(workspace, "status", "--porcelain"):
            raise RuntimeError("Builder worktree is dirty: preserve changes and inspect")
        touched = git(workspace, "diff", "--name-only", job["base_sha"], head)
        if not touched.strip():
            raise RuntimeError("No files changed against base commit")
        # Recorded text is NOT authoritative but must corroborate the observed commit.
        descriptions = " ".join(str(x or "") for x in (
            task.get("result"), run.get("summary"), run.get("metadata")
        ))
        if head not in descriptions.lower():
            raise RuntimeError("Native builder completion did not report its actual HEAD SHA")
        return head

    def _verify_review(self, job: dict, role: str, task: dict, run: dict | None):
        if not run or run.get("profile") != self.policy["roles"][role]["profile"]:
            raise RuntimeError("Reviewer native run profile mismatch")
        if run.get("outcome") not in {"completed", None}:
            raise RuntimeError("Reviewer did not complete")
        sha = job["commit_sha"]
        if not sha or not SHA.fullmatch(sha):
            raise RuntimeError("No verified SHA to review")
        # Exact SHA proof plus a declared verdict. Never interpret an
        # unstructured generic 'looks good' as approval.
        text = " ".join(str(x or "") for x in (
            task.get("result"), run.get("summary"), run.get("metadata")
        ))
        if f"REJECTED:{sha}" in text:
            raise RuntimeError(f"{role} rejected the build")
        if f"APPROVED:{sha}" not in text:
            raise RuntimeError(f"{role} has no explicit SHA-bound approval")
        git(job["repository"], "cat-file", "-e", sha + "^{commit}")
        if git(job["repository"], "rev-parse", "HEAD") != job["base_sha"]:
            raise RuntimeError("Main review checkout changed its commit; approval is unsafe")
        if git(job["repository"], "status", "--porcelain"):
            raise RuntimeError("Main review repository changed; approval is unsafe")

    def _reconcile(self, job: dict, step: dict):
        row = read_native(self.board_path, task_id=step["task_id"])
        if row is None:
            self._stop(job, "Native task disappeared; manual reconciliation required")
            return
        task, run = row
        if task.get("assignee") != self.policy["roles"][step["role"]]["profile"]:
            self._stop(job, "Native task assignee was changed unexpectedly")
            return
        expected = self.policy["roles"][step["role"]]["model"]
        if task.get("model_override") != expected or task.get("provider_override") != "openai-codex":
            self._stop(job, "Task model/provider was altered; manual inspection required")
            return
        native_status = task["status"]
        if native_status == "done":
            if step["status"] != "reserved":
                self._stop(job, "Native task completed without authorized budget reservation")
                return
            try:
                if step["role"] in BUILDER_ROLES:
                    sha = self._verify_builder(job, task, run)
                else:
                    self._verify_review(job, step["role"], task, run)
                    sha = job["commit_sha"]
                if step["status"] == "reserved":
                    self.guard.finish(run_id=step["id"], status="ok")
                self.db.execute(
                    "UPDATE stages SET status='verified', evidence=?, updated_at=? WHERE id=?",
                    (json.dumps({"sha": sha, "native_task": task["id"],
                                 "native_run_id": run["id"] if run else None,
                                 "profile": run["profile"] if run else None}),
                     int(time.time()), step["id"])
                )
                if step["role"] in BUILDER_ROLES:
                    self.update_job(job["id"], "queued", stage="product", commit=sha)
                elif step["role"] == "product":
                    self.update_job(job["id"], "queued", stage="architect")
                else:
                    self.update_job(job["id"], "awaiting_owner", stage="owner")
            except (RuntimeError, EcoError, ValueError) as exc:
                # Retain original native card and proof for investigation.
                if step["status"] == "reserved":
                    try:
                        self.guard.finish(run_id=step["id"], status="failed")
                    except EcoError:
                        pass
                self._stop(job, str(exc))
            return
        if native_status in {"blocked", "archived"} and step["status"] == "reserved":
            message = str(task.get("last_failure_error") or (run or {}).get("error") or "")
            rate_limited = "429" in message or "rate limit" in message.lower()
            try:
                self.guard.finish(run_id=step["id"],
                                  status="rate_limited" if rate_limited else "failed")
            except EcoError:
                pass
            self._stop(job, "Worker rate-limited (429); no auto retry" if rate_limited
                       else "Native task blocked; manual examination required")
            return
        # A native blocked card in status 'created' is intentional; dispatch once.
        if step["status"] != "created":
            return
        if native_status not in {"blocked", "ready", "todo"}:
            self._stop(job, f"Unsafe native state before dispatch: {native_status}")
            return
        if native_status == "todo":
            self._stop(job, "Dependency not ready; manual review required")
            return
        try:
            reservation = self.guard.reserve(
                run_id=step["id"], project_id=job["id"], role=step["role"])
            if reservation not in {"reserved", "already_running"}:
                raise EcoError("Reservation already finished; refusing duplicate dispatch")
            if native_status == "blocked":
                native("unblock", task["id"])
            # Only our card may be READY, so --max 1 must not dispatch unrelated work.
            with sqlite3.connect(f"file:{self.board_path.as_posix()}?mode=ro",
                                 uri=True) as conn:
                ready = conn.execute(
                    "SELECT id FROM tasks WHERE status='ready'").fetchall()
                if len(ready) != 1 or ready[0][0] != task["id"]:
                    raise RuntimeError("Unexpected ready tasks on board; refusing to dispatch")
            output = native("dispatch", "--max", "1", "--json")
            parsed = json.loads(output)
            if not parsed.get("spawned"):
                # Native may have failed; never repeat without reconciliation.
                raise RuntimeError("Native dispatcher did not confirm worker launch")
            self.db.execute(
                "UPDATE stages SET status='reserved',updated_at=? WHERE id=?",
                (int(time.time()), step["id"])
            )
            self.update_job(job["id"], "running")
        except (EcoError, RuntimeError, ValueError) as exc:
            # A prior crash may have launched a worker already; do NOT release
            # reservations automatically. Require operator investigation.
            self._stop(job, "Dispatch needs reconciliation: " + str(exc))

    def tick(self) -> dict:
        # Explicitly refuse to run while the gateway can dispatch outside
        # the ECO ledger. Native model starts must have only ONE owner.
        val = invoke("hermes", "config", "get", "kanban.dispatch_in_gateway").strip().lower()
        if val != "false":
            raise RuntimeError("Unsafe: gateway Kanban autodispatch is enabled")
        # One controller may drive the native dispatcher at a time.
        # A process crash rolls back this transaction; native task reservations
        # are still reconciled before any subsequent launch.
        self.db.execute("BEGIN IMMEDIATE")
        try:
            result = self._tick_locked()
            self.db.commit()
            return result
        except BaseException:
            self.db.rollback()
            raise

    def _tick_locked(self) -> dict:
        rows = [dict(r) for r in self.db.execute(
            "SELECT * FROM jobs WHERE status IN ('queued','stage_queued','running') ORDER BY submitted_at")]
        if not rows:
            return {"checked": 0, "message": "no billable work"}
        for job in rows:
            if job["status"] == "queued":
                self._create_task(job, job["stage"])
                job = self.row(job["id"])
            if job["status"] in {"stage_queued", "running"}:
                stageid = job["id"] + ":" + job["stage"]
                step = self.db.execute(
                    "SELECT * FROM stages WHERE id=?", (stageid,)
                ).fetchone()
                if not step:
                    self._stop(job, "Missing stage journal")
                    continue
                self._reconcile(job, dict(step))
                # Strictly one transition or dispatch per tick.
                return {"checked": 1, "job": job["id"], "status": self.row(job["id"])["status"]}
        return {"checked": len(rows), "message": "nothing dispatched"}

def execute():
    parser = argparse.ArgumentParser(description="Hermes AI Company controlled Kanban")
    parser.add_argument("--db", type=Path, default=STATE)
    parser.add_argument("--board-db", type=Path, default=BOARD)
    cmds = parser.add_subparsers(dest="command", required=True)
    submit = cmds.add_parser("submit")
    submit.add_argument("--title", required=True)
    submit.add_argument("--spec", required=True)
    submit.add_argument("--repo", required=True)
    submit.add_argument("--role", default="backend", choices=sorted(BUILDER_ROLES))
    for name in ("approve", "accept", "status"):
        c = cmds.add_parser(name)
        c.add_argument("jobid")
    cmds.add_parser("tick")
    cmds.add_parser("list")
    args = parser.parse_args()
    ctl = Controller(args.db, args.board_db)
    try:
        if args.command == "submit":
            result = {"job_id": ctl.submit(title=args.title, spec=args.spec,
                                          repository=args.repo, role=args.role),
                      "status": "pending_approval", "model_calls": 0}
        elif args.command == "approve":
            ctl.approve(args.jobid)
            result = {"job_id": args.jobid, "status": "queued"}
        elif args.command == "accept":
            ctl.finish_approval(args.jobid)
            result = {"job_id": args.jobid, "status": "accepted", "deployed": False}
        elif args.command == "status":
            result = ctl.job_report(args.jobid)
        elif args.command == "list":
            result = [dict(r) for r in ctl.db.execute(
                "SELECT id,title,role,status,stage,commit_sha FROM jobs ORDER BY submitted_at DESC LIMIT 50")]
        else:
            result = ctl.tick()
        print(json.dumps(result, ensure_ascii=False, indent=2))
    finally:
        ctl.close()

if __name__ == "__main__":
    try:
        execute()
    except (EcoError, RuntimeError, ValueError, OSError, sqlite3.Error) as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        sys.exit(2)
