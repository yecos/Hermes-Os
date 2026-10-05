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

## Available v0.2 tools

- `system_status`
- `execute_command`
- `list_directory`
- `get_file_info`
- `create_directory`
- `read_file`
- `read_multiple_files`
- `write_file`
- `edit_block`
- `move_file`
- `delete_path`
- `search_files`
- `list_processes`
- `kill_process`
- `start_process`
- `read_process_output`
- `interact_with_process`
- `force_terminate`
- `list_sessions`
- `logs`

For long-running or interactive commands, prefer `start_process` and the session tools rather than repeatedly launching one-shot commands.
