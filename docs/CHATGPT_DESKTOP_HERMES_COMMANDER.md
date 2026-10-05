# Hermes Commander + ChatGPT Desktop

Hermes Commander is the ChatGPT Desktop surface for Hermes Node.

## Why this architecture

ChatGPT Desktop and Codex support local MCP servers over STDIO. Hermes Node already implements an MCP STDIO server, so ChatGPT can launch it locally instead of routing machine-control requests through a public internet service.

```text
ChatGPT Desktop
       |
       | local MCP / STDIO
       v
Hermes Commander
       |
       v
Hermes Node
       |
       +-- system
       +-- commands
       +-- filesystem
       +-- processes
       +-- audit
```

This is intentionally separate from the HTTP `serve` mode. Local ChatGPT control does not require opening port 9090.

## Repository layout

```text
.agents/plugins/marketplace.json
plugins/hermes-commander/
  .codex-plugin/plugin.json
  .mcp.json
  scripts/launch-windows.ps1
  skills/hermes-commander/SKILL.md
scripts/install-hermes-commander-chatgpt.ps1
```

## Installation on YECO

From a fresh/up-to-date repository checkout:

```powershell
git checkout main
git pull
powershell -ExecutionPolicy Bypass -File .\scripts\install-hermes-commander-chatgpt.ps1 -FullControl
```

The installer:

1. runs Hermes Node tests;
2. builds `hermes-node.exe`;
3. installs it under `%LOCALAPPDATA%\HermesCommander`;
4. creates a local policy under `%USERPROFILE%\.hermes-node`;
5. registers the repository marketplace with Codex;
6. installs the Hermes Commander plugin.

Restart ChatGPT Desktop after the install.

## Validation

Inside ChatGPT Desktop:

1. Open Plugins and confirm **Hermes Commander** is enabled.
2. Start a new chat.
3. Ask: `Use Hermes Commander and show system status.`
4. Ask it to list a directory inside the allowed root.
5. Ask it to create and read back a harmless test file.
6. Ask it to run a harmless command such as `git --version`.
7. Inspect the `logs` tool.

If MCP tools are not visible, use `/mcp verbose` in the desktop Codex surface to inspect server startup diagnostics.

## Security

`-FullControl` changes Hermes Node command policy to `admin`, which is intentionally equivalent to giving the local plugin arbitrary command execution rights. Only use it on a personal computer you control.

File access remains bounded by `HERMES_NODE_ALLOWED_DIRS` until that setting is expanded.
