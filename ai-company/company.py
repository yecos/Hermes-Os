"""Hermes AI Company: persistent six-agent task/review/approval engine (MVP).

This module does not launch models, deploy software or connect Telegram.
Its primitives are intended for a future Hermes Agent gateway adapter.
"""
from __future__ import annotations

import argparse
import json
import re
import sqlite3
import uuid
from datetime import datetime, timezone

BUILDERS = frozenset({"frontend", "backend", "integrations"})
REVIEWERS = frozenset({"product", "architect"})
COMMIT_SHA = re.compile(r"^[0-9a-fA-F]{40}$")


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


class WorkflowError(ValueError):
    pass


class Company:
    def __init__(self, database: str = "ai-company.sqlite3"):
        self.db = sqlite3.connect(database)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.executescript("""
        CREATE TABLE IF NOT EXISTS projects (
            id TEXT PRIMARY KEY, name TEXT NOT NULL, brief TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'active', created_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS tasks (
            id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id),
            title TEXT NOT NULL, role TEXT NOT NULL, criteria TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'queued', attempts INTEGER NOT NULL DEFAULT 0,
            evidence TEXT, created_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS reviews (
            task_id TEXT NOT NULL REFERENCES tasks(id), reviewer TEXT NOT NULL,
            verdict TEXT NOT NULL, note TEXT NOT NULL, created_at TEXT NOT NULL,
            PRIMARY KEY (task_id, reviewer)
        );
        CREATE TABLE IF NOT EXISTS approvals (
            id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id),
            commit_sha TEXT NOT NULL, preview_url TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'pending', actor TEXT, created_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS events (
            id INTEGER PRIMARY KEY AUTOINCREMENT, project_id TEXT NOT NULL,
            actor TEXT NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL,
            created_at TEXT NOT NULL
        );
        """)
        self.db.commit()

    def close(self):
        self.db.close()

    def _row(self, table: str, item_id: str):
        if table not in {"projects", "tasks", "approvals"}:
            raise WorkflowError("Invalid table")
        row = self.db.execute(f"SELECT * FROM {table} WHERE id=?", (item_id,)).fetchone()
        if row is None:
            raise WorkflowError(f"Unknown {table} identifier")
        return row

    def _event(self, project_id: str, actor: str, kind: str, **payload):
        self.db.execute(
            "INSERT INTO events (project_id,actor,kind,payload,created_at) VALUES (?,?,?,?,?)",
            (project_id, actor, kind, json.dumps(payload, ensure_ascii=False), now()),
        )

    def create_project(self, name: str, brief: str) -> str:
        if not name.strip() or not brief.strip():
            raise WorkflowError("Project name and brief are required")
        project_id = uuid.uuid4().hex[:12]
        with self.db:
            self.db.execute("INSERT INTO projects VALUES (?,?,?,?,?)",
                            (project_id, name.strip(), brief.strip(), "active", now()))
            self._event(project_id, "director", "project_created", name=name.strip())
        return project_id

    def add_task(self, project_id: str, title: str, role: str, criteria: str) -> str:
        project = self._row("projects", project_id)
        if project["state"] != "active":
            raise WorkflowError("Project is not active")
        if role not in BUILDERS or not title.strip() or not criteria.strip():
            raise WorkflowError("Builder role, title and acceptance criteria required")
        task_id = uuid.uuid4().hex[:12]
        with self.db:
            self.db.execute("INSERT INTO tasks VALUES (?,?,?,?,?,?,?,?,?)",
                            (task_id, project_id, title.strip(), role,
                             criteria.strip(), "queued", 0, None, now()))
            self._event(project_id, "architect", "task_queued", task_id=task_id, role=role)
        return task_id

    def start_task(self, task_id: str, actor: str):
        task = self._row("tasks", task_id)
        if task["role"] != actor or task["state"] != "queued":
            raise WorkflowError("Only the assigned builder can start a queued task")
        if self._row("projects", task["project_id"])["state"] != "active":
            raise WorkflowError("Project is paused")
        with self.db:
            self.db.execute("UPDATE tasks SET state='working' WHERE id=?", (task_id,))
            self._event(task["project_id"], actor, "task_started", task_id=task_id)

    def submit_task(self, task_id: str, actor: str, evidence: str):
        task = self._row("tasks", task_id)
        if task["role"] != actor or task["state"] != "working":
            raise WorkflowError("Only the assigned builder can submit a working task")
        if not evidence.strip():
            raise WorkflowError("Test or review evidence is mandatory")
        with self.db:
            self.db.execute("UPDATE tasks SET state='review', evidence=? WHERE id=?",
                            (evidence.strip(), task_id))
            self.db.execute("DELETE FROM reviews WHERE task_id=?", (task_id,))
            self._event(task["project_id"], actor, "task_submitted", task_id=task_id,
                        evidence=evidence.strip())

    def review_task(self, task_id: str, reviewer: str, approved: bool, note: str):
        task = self._row("tasks", task_id)
        if reviewer not in REVIEWERS or task["state"] != "review" or not note.strip():
            raise WorkflowError("Review requires assigned reviewer, review state and note")
        with self.db:
            self.db.execute("INSERT INTO reviews VALUES (?,?,?,?,?) ON CONFLICT(task_id,reviewer) "
                            "DO UPDATE SET verdict=excluded.verdict,note=excluded.note,created_at=excluded.created_at",
                            (task_id, reviewer, "approved" if approved else "rejected", note.strip(), now()))
            self._event(task["project_id"], reviewer, "task_reviewed", task_id=task_id,
                        approved=approved, note=note.strip())
            if not approved:
                attempts = task["attempts"] + 1
                new_state = "blocked" if attempts >= 3 else "queued"
                self.db.execute("UPDATE tasks SET state=?,attempts=? WHERE id=?",
                                (new_state, attempts, task_id))
                self.db.execute("DELETE FROM reviews WHERE task_id=?", (task_id,))
                self._event(task["project_id"], "architect", "revision_requested",
                            task_id=task_id, attempts=attempts, state=new_state)
                if attempts == 2:
                    self._event(task["project_id"], "architect", "escalation_architect",
                                task_id=task_id, reason="two rejected attempts")
                if attempts >= 3:
                    self._event(task["project_id"], "director", "director_attention_required",
                                task_id=task_id, reason="three rejected attempts")
            else:
                count = self.db.execute("SELECT count(*) FROM reviews WHERE task_id=? "
                                        "AND verdict='approved'", (task_id,)).fetchone()[0]
                if count == 2:
                    self.db.execute("UPDATE tasks SET state='accepted' WHERE id=?", (task_id,))
                    self._event(task["project_id"], "director", "task_accepted", task_id=task_id)

    def propose_preview(self, project_id: str, commit_sha: str, url: str) -> str:
        project = self._row("projects", project_id)
        if project["state"] != "active":
            raise WorkflowError("Project must be active")
        tasks = self.db.execute("SELECT state FROM tasks WHERE project_id=?", (project_id,)).fetchall()
        if not tasks or any(t["state"] != "accepted" for t in tasks):
            raise WorkflowError("All project tasks must pass both independent reviews")
        if not COMMIT_SHA.fullmatch(commit_sha) or not url.startswith("https://"):
            raise WorkflowError("A 40-character commit SHA and HTTPS preview URL are required")
        approval_id = uuid.uuid4().hex[:12]
        with self.db:
            self.db.execute("UPDATE approvals SET state='superseded' WHERE project_id=? AND state='pending'",
                            (project_id,))
            self.db.execute("INSERT INTO approvals VALUES (?,?,?,?,?,?,?)",
                            (approval_id, project_id, commit_sha.lower(), url, "pending", None, now()))
            self._event(project_id, "director", "preview_proposed", approval_id=approval_id,
                        commit_sha=commit_sha.lower(), preview_url=url)
        return approval_id

    def approve_preview(self, approval_id: str, commit_sha: str, actor: str):
        item = self._row("approvals", approval_id)
        if actor != "authenticated_owner":
            raise WorkflowError("Authenticated human owner approval required")
        if item["state"] != "pending" or item["commit_sha"] != commit_sha.lower():
            raise WorkflowError("Approval expired or commit does not match")
        with self.db:
            self.db.execute("UPDATE approvals SET state='approved',actor=? WHERE id=?",
                            (actor, approval_id))
            self._event(item["project_id"], actor, "preview_approved",
                        approval_id=approval_id, commit_sha=commit_sha.lower())
        # Never deploy here: a separate controlled executor is required.

    def set_project_state(self, project_id: str, state: str, actor: str):
        project = self._row("projects", project_id)
        if actor != "director" or state not in {"active", "paused"}:
            raise WorkflowError("Only director can pause/resume projects")
        if project["state"] == state:
            return
        with self.db:
            self.db.execute("UPDATE projects SET state=? WHERE id=?", (state, project_id))
            self._event(project_id, actor, "project_state_changed", state=state)

    def status(self, project_id: str) -> dict:
        project = dict(self._row("projects", project_id))
        tasks = [dict(t) for t in self.db.execute(
            "SELECT id,title,role,state,attempts,evidence FROM tasks WHERE project_id=? ORDER BY created_at",
            (project_id,))]
        approvals = [dict(a) for a in self.db.execute(
            "SELECT id,commit_sha,preview_url,state FROM approvals WHERE project_id=? ORDER BY created_at DESC",
            (project_id,))]
        return {"project": project, "tasks": tasks, "approvals": approvals}

    def events(self, project_id: str) -> list[dict]:
        """Return the immutable audit trail for a project in creation order."""
        self._row("projects", project_id)
        return [
            dict(row)
            for row in self.db.execute(
                "SELECT id,actor,kind,payload,created_at FROM events "
                "WHERE project_id=? ORDER BY id",
                (project_id,),
            )
        ]


def main():
    p = argparse.ArgumentParser(description="Hermes AI Company task engine (no AI execution yet)")
    p.add_argument("--db", default="ai-company.sqlite3")
    sub = p.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("new"); a.add_argument("name"); a.add_argument("brief")
    a = sub.add_parser("task"); a.add_argument("project_id"); a.add_argument("role", choices=sorted(BUILDERS)); a.add_argument("title"); a.add_argument("criteria")
    a = sub.add_parser("start"); a.add_argument("task_id"); a.add_argument("role", choices=sorted(BUILDERS))
    a = sub.add_parser("submit"); a.add_argument("task_id"); a.add_argument("role", choices=sorted(BUILDERS)); a.add_argument("evidence")
    a = sub.add_parser("review"); a.add_argument("task_id"); a.add_argument("reviewer", choices=sorted(REVIEWERS)); a.add_argument("verdict", choices=["approve", "reject"]); a.add_argument("note")
    a = sub.add_parser("preview"); a.add_argument("project_id"); a.add_argument("commit_sha"); a.add_argument("url")
    a = sub.add_parser("project-state"); a.add_argument("project_id"); a.add_argument("state", choices=["active", "paused"])
    a = sub.add_parser("status"); a.add_argument("project_id")
    args = p.parse_args()
    company = Company(args.db)
    try:
        if args.cmd == "new": result = company.create_project(args.name, args.brief)
        elif args.cmd == "task": result = company.add_task(args.project_id, args.title, args.role, args.criteria)
        elif args.cmd == "start": result = company.start_task(args.task_id, args.role)
        elif args.cmd == "submit": result = company.submit_task(args.task_id, args.role, args.evidence)
        elif args.cmd == "review": result = company.review_task(args.task_id, args.reviewer, args.verdict == "approve", args.note)
        elif args.cmd == "preview": result = company.propose_preview(args.project_id, args.commit_sha, args.url)
        elif args.cmd == "project-state": result = company.set_project_state(args.project_id, args.state, "director")
        elif args.cmd == "status": result = company.status(args.project_id)
        print(json.dumps({"ok": True, "result": result}, ensure_ascii=False))
    except WorkflowError as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False))
        raise SystemExit(2)
    finally:
        company.close()


if __name__ == "__main__":
    main()
