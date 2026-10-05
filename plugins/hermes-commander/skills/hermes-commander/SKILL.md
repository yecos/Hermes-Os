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

## Available v0.3 tools

### System, files and terminal

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

### Visual Windows control

- `screenshot`
- `list_displays`
- `list_windows`
- `get_active_window`
- `open_application`
- `focus_window`
- `close_window`
- `mouse_move`
- `mouse_click`
- `mouse_drag`
- `mouse_scroll`
- `key_press`
- `hotkey`
- `type_text`
- `clipboard_read`
- `clipboard_write`

## Visual operating loop

When the user asks to interact with the Windows GUI:

1. Call `list_displays` if monitor layout is unknown.
2. Call `screenshot` before interacting when visual state matters.
3. Prefer `list_windows` / `focus_window` / `open_application` over blind clicking when possible.
4. Use absolute coordinates from the latest screenshot for mouse operations.
5. After meaningful GUI actions, call `screenshot` again and verify the visible result.
6. Prefer keyboard shortcuts and direct window operations over coordinate clicks when they are more reliable.
7. Do not claim an application opened, a click worked, or navigation succeeded unless it was verified through window state, process state, or a fresh screenshot.

For long-running or interactive terminal commands, prefer `start_process` and the session tools.
