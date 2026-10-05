# Hermes Commander for ChatGPT Desktop

Hermes Commander is the local ChatGPT Desktop plugin for Hermes OS.

It launches the native `hermes-node.exe mcp` process over MCP STDIO. ChatGPT Desktop therefore talks directly to the user's machine without exposing an inbound public port.

## Requirements

- Windows
- ChatGPT Desktop with Codex/Work local plugin support
- Go 1.23+ for the installer build step
- Codex CLI available as `codex`
- A local clone of `yecos/Hermes-Os`

## Install

From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install-hermes-commander-chatgpt.ps1
```

For Desktop Commander-style unrestricted command execution on a computer you own:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install-hermes-commander-chatgpt.ps1 -FullControl
```

Full control intentionally allows arbitrary local command execution. Use it only on a trusted personal machine.

Restart ChatGPT Desktop after installation. Open Plugins and install/enable **Hermes Commander** from the **Hermes OS** local marketplace if it is not already enabled.

Then start a new chat and test:

- "Use Hermes Commander and show me system status."
- "List my home directory."
- "Run git status in my Hermes-Os repository."

## Design

```text
ChatGPT Desktop
      |
      | MCP STDIO
      v
Hermes Commander plugin
      |
      v
hermes-node.exe mcp
      |
      +-- files / search / edit
      +-- commands
      +-- persistent terminal sessions
      +-- processes
      +-- audit
```

The launcher reads policy from `%USERPROFILE%\.hermes-node\hermes-node.env` and never places secrets in the Git repository.


## Hermes Commander v0.3 tool surface

The local plugin now exposes the machine-control core plus Windows visual control: desktop screenshots returned directly as MCP image content, display/window discovery, application opening, window focus/close, mouse movement/click/drag/scroll, keyboard input/hotkeys and clipboard access.


## Visual verification loop

For GUI work Hermes Commander should use: `screenshot -> inspect -> act -> screenshot -> verify`. This avoids assuming that a mouse click, window focus or application launch succeeded.


## Hermes Commander v0.4 semantic control

v0.4 adds two semantic control layers above raw pixel automation.

### Windows UI Automation

ChatGPT can inspect the active application's accessibility/UI Automation tree, find controls by name, AutomationId, type or class, and operate those controls through semantic element IDs. It can wait for dialogs and controls, set text through ValuePattern, invoke buttons without coordinates, and fall back to mouse/keyboard only when needed.

### Managed Chrome / CDP

Hermes Commander can launch a dedicated Chrome profile with DevTools bound only to localhost. ChatGPT can list tabs, read visible interactive DOM elements, click DOM elements by stable IDs, set form values, navigate tabs, read page text without OCR and capture the browser viewport directly through Chrome DevTools.

Preferred browser loop:

```text
browser_snapshot
      ↓
browser_click / browser_set_text / browser_navigate
      ↓
browser_snapshot or browser_page_text
      ↓
browser_screenshot for visual verification
```

This keeps browser automation independent of monitor resolution and window position.
