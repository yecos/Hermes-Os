---
name: hermes-commander
description: Control the user's authorized local Windows computer through Hermes Commander MCP tools.
---

# Hermes Commander

Use Hermes Commander when the user asks to inspect, modify, build, run, diagnose, or manage the local computer.

## Operating rules

- Prefer dedicated file and process tools over shell commands when both can complete the task.
- Inspect relevant state before making destructive or difficult-to-reverse changes.
- Treat paths outside the node's allowed roots as unavailable unless the user intentionally expands the node policy.
- Never reveal authentication tokens or secrets from the Hermes Node environment.
- For commands, pass the executable as `command` and individual arguments in `args`; do not concatenate shell syntax unless shell execution is explicitly necessary and permitted.
- After writes or commands that change state, verify the result.
- Use audit logs when diagnosing what Hermes Commander previously changed.

## Available v0.1 tools

- `system_status`
- `execute_command`
- `list_directory`
- `read_file`
- `write_file`
- `list_processes`
- `logs`

The tool set will expand as Hermes Node gains Desktop Commander parity.
