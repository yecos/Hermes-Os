"""Local, deterministic, no-LLM Git and stdlib Python quality gate.

Only run against code that an authorized builder already produced. Uses an
argv-only subprocess and never installs dependencies or mutates the repository.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

SHA = re.compile(r"^[a-f0-9]{40}$")
TEST_COUNT = re.compile(r"Ran\s+(\d+)\s+tests?\s+in\s+", re.I)


def _run(argv: list[str], cwd: Path, timeout: int = 45,
         include_stderr: bool = False) -> str:
    try:
        completed = subprocess.run(
            argv, cwd=str(cwd), stdin=subprocess.DEVNULL,
            capture_output=True, text=True, encoding="utf-8",
            errors="replace", timeout=timeout, check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError("Quality gate exceeded its time limit") from exc
    if completed.returncode:
        evidence = (completed.stderr or completed.stdout)[-800:]
        raise RuntimeError(f"Quality gate failed (exit={completed.returncode}): {evidence}")
    return completed.stdout + (completed.stderr if include_stderr else "")


def significant_git_status(status: str) -> list[str]:
    """Ignore only non-tracked Python bytecode; preserve all source modifications."""
    lines = [line for line in status.splitlines() if line.strip()]
    return [
        line for line in lines
        if not (line.startswith("?? ") and re.fullmatch(
            r"(?:[^/]+/)*__pycache__/[^/]+\.pyc", line[3:]
        ))
    ]


def verify_commit(workspace: str | Path, base_sha: str, commit_sha: str) -> dict:
    """Verify immutable commit and run stdlib tests when changed Python tests exist."""
    workspace = Path(workspace).resolve(strict=True)
    if not SHA.fullmatch(base_sha) or not SHA.fullmatch(commit_sha):
        raise RuntimeError("Quality gate requires exact forty-character commit hashes")
    _run(["git", "cat-file", "-e", commit_sha + "^{commit}"], workspace)
    _run(["git", "merge-base", "--is-ancestor", base_sha, commit_sha], workspace)
    _run(["git", "diff", "--check", base_sha, commit_sha], workspace)
    raw = _run(["git", "diff", "--name-only", "-z", base_sha, commit_sha], workspace)
    files = [name for name in raw.split("\0") if name]
    if not files:
        raise RuntimeError("Quality gate found no changed files")
    if len(files) > 120:
        raise RuntimeError("Quality gate requires human review for changes above 120 files")
    for name in files:
        name_parts = name.replace("\\", "/").lower().split("/")
        leaf = name_parts[-1]
        if (leaf == ".env" or leaf.startswith(".env.") and leaf not in
                {".env.example", ".env.sample", ".env.template"}
                or leaf.endswith((".pem", ".p12", ".pfx", ".key"))
                or ".ssh" in name_parts or leaf in {"auth.json", "credentials.json"}):
            raise RuntimeError(f"Sensitive file in diff; manual review required: {name!r}")
    status = _run(["git", "status", "--porcelain", "--untracked-files=all"], workspace)
    if significant_git_status(status):
        raise RuntimeError("Quality gate requires a clean builder checkout")
    result = {
        "sha": commit_sha,
        "base_sha": base_sha,
        "changed_count": len(files),
        "changed_files": files[:40],
        "git_diff_check": "pass",
        "test_runner": "not_applicable",
        "tests_run": 0,
        "tests_passed": None,
    }
    if any(name.startswith("tests/test_") and name.endswith(".py") for name in files):
        result["test_runner"] = "python_stdlib_unittest"
        out = _run([sys.executable, "-B", "-m", "unittest",
                    "discover", "-s", "tests", "-v"], workspace,
                   include_stderr=True)
        count = TEST_COUNT.search(out)
        if not count or int(count.group(1)) == 0 or not re.search(r"(?m)^OK\s*$", out):
            raise RuntimeError("Python tests did not report a nonempty passing suite")
        result["tests_passed"] = True
        result["tests_run"] = int(count.group(1))
        # Tests may generate or alter files: their execution must not leave
        # the committed build in a different state from the verified SHA.
        if significant_git_status(_run(
                ["git", "status", "--porcelain", "--untracked-files=all"], workspace)):
            raise RuntimeError("Tests altered the Git worktree; human reconciliation required")
    return result


if __name__ == "__main__":
    import argparse
    import json
    parser = argparse.ArgumentParser(description="No-LLM quality gate for a Git commit")
    parser.add_argument("--workspace", required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    args = parser.parse_args()
    print(json.dumps(verify_commit(args.workspace, args.base, args.head),
                     indent=2, ensure_ascii=False))
