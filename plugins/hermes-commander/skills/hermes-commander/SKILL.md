---
name: hermes-commander
description: Control the user's authorized local Windows computer through Hermes Commander MCP tools.
---

# Hermes Commander

Use Hermes Commander when the user asks to inspect, modify, build, run, diagnose, browse, or operate the authorized local computer.

## Operating rules

- Prefer dedicated semantic, file, process, window and browser tools over generic shell commands when possible.
- Inspect relevant state before making destructive or difficult-to-reverse changes.
- Treat paths outside the node's allowed roots as unavailable unless the user intentionally expands the node policy.
- Never reveal authentication tokens or secrets from the Hermes Node environment.
- After state-changing actions, verify the result.
- Do not claim an action succeeded solely because a tool returned without error when visible state can be checked.

## Interaction priority

For Windows GUI tasks, use this order:

1. **Semantic UI Automation**: `ui_snapshot`, `find_elements`, `wait_for_element`, then semantic actions.
2. **Direct window/application operations**: `list_windows`, `focus_window`, `open_application`.
3. **Visual verification**: `window_screenshot`, `screen_region`, or `screenshot`.
4. **Coordinates**: raw mouse operations only when semantic element control is unavailable.

This priority makes automation resilient to window movement, resolution changes and small layout changes.

## Semantic Windows tools

- `ui_snapshot` — inspect the UI Automation tree for the active or named window.
- `find_elements` — search by name, AutomationId, control type or class.
- `wait_for_element` — wait for dialogs/buttons/fields to appear.
- `click_element` — click the center of a semantic element.
- `invoke_element` — invoke a button/control through InvokePattern without pixel coordinates.
- `set_element_text` — set text through ValuePattern with keyboard fallback.
- `focus_element` — focus a control.
- `scroll_element` — bring a control into view and scroll around it.
- `window_screenshot` — capture only one application window.
- `screen_region` — capture a focused rectangle instead of the full desktop.

When `invoke_element` is supported, prefer it over `click_element`.

## Managed browser tools

- `browser_open_managed` — launch a dedicated Chrome instance with local DevTools enabled.
- `browser_tabs` — inspect its current page tabs.
- `browser_open_tab` — open a URL as a new managed tab.
- `browser_snapshot` — inspect visible interactive DOM elements and receive stable browser element IDs.
- `browser_click` — click a DOM element by browser element ID.
- `browser_set_text` — set text/value and dispatch input/change events.
- `browser_navigate` — navigate a selected tab through Chrome DevTools.
- `browser_page_text` — read visible page text directly from the DOM, without OCR.
- `browser_screenshot` — capture the browser viewport directly through DevTools.

For managed Chrome workflows use this priority:

1. `browser_tabs` / `browser_snapshot`.
2. DOM actions such as `browser_click` and `browser_set_text`.
3. `browser_page_text` for page understanding when visual layout is not required.
4. `browser_screenshot` for visual verification.
5. Windows UI Automation or raw mouse only when the page does not expose the needed action through the browser semantic layer.

After browser navigation or a click that changes the page, take a fresh browser snapshot before reusing element IDs.

## Existing system / development tools

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

## Existing visual tools

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

## Reliable GUI loop

For an application task:

1. Inspect `get_active_window` / `list_windows`.
2. Use `ui_snapshot` or `find_elements`.
3. Act semantically.
4. Use `wait_for_element` when the app needs time.
5. Verify using a new UI snapshot or a focused screenshot.
6. Fall back to coordinates only if the app does not expose the required control through UI Automation.

For long-running terminal work, prefer persistent session tools rather than repeatedly launching one-shot commands.


## Multi-device control (v0.5)

Hermes Commander can target the local machine or registered Hermes Nodes over an authenticated private HTTP transport.

Management tools:

- `devices_list` — list local and registered nodes with online state, OS, architecture and capabilities.
- `device_current` — show the default target.
- `device_select` — change the default target for subsequent tools.
- `device_ping` — verify one node.
- `device_add` — register a node using its private/Tailscale URL and bearer token.
- `device_remove` — remove a remote node.

Every normal Hermes tool also accepts an optional `device_id`. If omitted, the currently selected device is used.

Examples of preferred behavior:

```text
devices_list
device_select(device_id="s10")
system_status
execute_command(command="uname", args=["-a"])
```

or target a single action without changing the default:

```text
system_status(device_id="laptop")
```

Never print or repeat a node bearer token. The registry keeps tokens locally under `~/.hermes-node/devices.json`. Remote node URLs are restricted to loopback, private LAN, or Tailscale addresses.

When a tool is unsupported by the selected platform, report that capability difference and choose an appropriate supported tool rather than silently switching devices.
