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


## Hermes Commander v0.2 tool surface

The local plugin currently exposes 20 MCP tools covering system inspection, one-shot commands, filesystem operations, search, surgical text edits, OS processes and persistent command sessions. This is the machine-control core needed to replace Desktop Commander for normal development and administration workflows.
