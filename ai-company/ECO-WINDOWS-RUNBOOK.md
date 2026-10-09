# Hermes AI Company ECO — Windows live setup and operational runbook

State: Windows Hermes 0.20.2, existing Telegram gateway in **default**, six role profiles. This guide installs a **Codex-only balanced mode** without a second Telegram gateway or a custom model runner.

## What is actually enforced after running the setup

- `agent.max_turns`: 12 Director/default and builders; 8 Product/Architect, **per conversation turn**, NOT a subscription-wide budget.
- `delegation.max_iterations=8` and `delegation.max_concurrent_children=1` for all seven profiles.
- Default `agent.reasoning_effort=low`, verifier nudges 1, API retries 1; builders/director use low and reviewers medium.
- Native Kanban: `max_in_progress=1`, `max_in_progress_per_profile=1`, `failure_limit=1`, `auto_decompose=false`. This avoids silent fan-out from automatic triage and worker retries.
- Director/default context: compaction threshold 0.35, no Codex auto raise, deterministic proactive pruning after 48,000 tokens. Micro compaction remains disabled to preserve prompt-cache prefixes.
- All exact models remain `gpt-5.6-sol`, `gpt-6-luna`, `gpt-5.6-luna` via openai-codex. They are NOT changed or replaced.
- The native Kanban board `hermes-ai-company` has its own durable SQLite DB and defaults to the isolated worktree `C:\Dev\Hermes-AI-Company-ECO`.
- The existing `Hermes_Gateway` service continues to own Telegram. **No additional gateways**.
- None of these commands launches a billable Codex chat.

## Install, verify, recover

In Windows PowerShell, from the dedicated Git worktree:

```powershell
cd C:\Dev\Hermes-AI-Company-ECO
.\ai-company\apply-eco-windows.ps1                 # dry run
.\ai-company\apply-eco-windows.ps1 -Apply          # makes another config backup
hermes gateway restart                              # reload existing default gateway
.\ai-company\eco-health-windows.ps1                # read-only health check
```

Backups live ONLY on Windows in `$env:LOCALAPPDATA\hermes\eco-backup-YYYYMMDD-HHMMSS` and may contain private config. **Never add them to Git**. To roll back, stop the default gateway, restore all seven `*.yaml` files to their corresponding profile paths using the backup and restart the existing gateway. Inspect any intervening config changes before restoring.

## How to create one real controlled task (consumes Codex usage)

The board is initially empty and does **not** spawn background model sessions merely because the gateway is running. When the owner authorizes a task:

```powershell
hermes kanban --board hermes-ai-company create "Implement bounded demo" --body "Small isolated task; no deploy or merge; return commit SHA and test results" --assignee companybackend --workspace worktree --max-retries 1 --max-runtime 600 --model gpt-5.6-luna --provider openai-codex
```

The existing gateway dispatches the ready card at its interval. Do **not** add `--goal`, `--triage`, `--yolo`, change provider, or create a large set of cards at once. The Product and Architect reviews must be **separate** cards against the **same exact commit SHA** and must each use `gpt-6-luna`. Record independent reviewer verdicts before any approval. No preview, merge, or deploy.

Check results (no inference calls):

```powershell
hermes kanban --board hermes-ai-company stats
hermes kanban --board hermes-ai-company list
hermes kanban --board hermes-ai-company dispatch --dry-run --max 1 --json
hermes insights --days 1
```

## Measurement and limitations

Before changes, `hermes insights --days 1` on 2026-10-08 reported **19 sessions, 991 messages, 574 tool calls, 60,388,632 total tokens**, including 1,167,301 input and 170,451 output. This is **Hermes' historical aggregate**, not proof of subscription-credit costs; total tokens likely include cache-related traffic and other token categories. Telegram accounted for 51,336,189 reported tokens, while the gpt-5.6-sol model accounted for 57,126,981. The baseline is not a controlled before/after comparison.

`eco_mode.py` and `eco-policy.json` additionally implement a **reference budget guard and local usage ledger**, with tests. **Their daily session-start budgets and token ledger are NOT currently wired into native Kanban's worker launches.** Do not claim those controls are automatically enforced or that real session usage is being attributed. The hard runtime controls currently come from Hermes native CLI configuration above. No percentage of subscription savings has been demonstrated.

The older `phase2.py` workflow for task/commit/two-reviewer evidence is not automatically linked to these Kanban cards. The next integration is a commit-SHA-based acceptance adapter; until then, perform/record both reviews explicitly. Future real E2E tests consume actual Codex usage and must be explicitly budgeted.

## Safe operating defaults

Keep Telegram in default only. Use max one worker at a time, no automatic task decomposition, no loop-based retries, short scopes and narrow file diffs. Keep backups and usage captures local. Never publish secrets, credentials, session content or private usage logs. Changes belong to PR #10 until formally reviewed and authorized; never merge or deploy from this board automatically.

## Local scheduled metering

Daily usage snapshot (no model calls): Windows Scheduled Task `Hermes-ECO-Usage-Daily` at 23:55 local runs `eco-usage-snapshot-windows.ps1` and writes local-only aggregates to `$env:LOCALAPPDATA\hermes\eco-metrics\YYYY-MM-DD.txt`. It runs when Windows/user session permits; it does not wake a powered-off PC.
