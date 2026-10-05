# Hermes Node v0.1

Hermes Node is the remote-computer agent for Hermes OS. It is designed to replace the basic machine-control layer currently delegated to third-party remote command tools.

## Capabilities

- Device status and capability discovery
- Authenticated local HTTP API
- MCP stdio server for Hermes Agent
- Restricted command execution with timeouts
- File listing, read and write inside explicit allowed roots
- Process listing
- In-memory audit log
- Windows, Linux and experimental Android/Termux builds from the same codebase

## Security defaults

Hermes Node binds to `127.0.0.1:9090`, requires a bearer token for every control endpoint, restricts filesystem access to the user's home directory, and only permits `git,docker,hermes,adb,ping` in command mode by default.

Do not expose port 9090 directly to the public internet. For remote access use Tailscale or another authenticated private network. Prefer an SSH/Tailscale tunnel while the control plane is still v0.x.

## Run

```bash
cd hermes-node
go build -o hermes-node ./cmd/hermes-node
export HERMES_NODE_TOKEN='replace-with-a-long-random-secret'
./hermes-node serve
```

Windows PowerShell:

```powershell
cd hermes-node
go build -o hermes-node.exe ./cmd/hermes-node
$env:HERMES_NODE_TOKEN = 'replace-with-a-long-random-secret'
.\hermes-node.exe serve
```

Health does not require authentication:

```bash
curl http://127.0.0.1:9090/v1/health
```

Authenticated status:

```bash
curl -H "Authorization: Bearer $HERMES_NODE_TOKEN" http://127.0.0.1:9090/v1/device
```

## MCP mode

Hermes Node v0.1 supports the 2025-era MCP stdio handshake and exposes these tools:

- `system_status`
- `execute_command`
- `list_directory`
- `read_file`
- `write_file`
- `list_processes`
- `logs`

Example MCP command:

```json
{
  "command": "/absolute/path/to/hermes-node",
  "args": ["mcp"],
  "env": {
    "HERMES_NODE_ALLOWED_DIRS": "/home/yeco",
    "HERMES_NODE_ALLOWED_COMMANDS": "git,docker,hermes,adb,ping",
    "HERMES_NODE_EXEC_MODE": "restricted"
  }
}
```

The current MCP standard also has a newer 2026 protocol era. Native 2026-07-28 negotiation/Streamable HTTP is intentionally left for v0.2; the v0.1 stdio transport targets broad compatibility with existing Hermes/MCP clients.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `HERMES_NODE_NAME` | hostname | Friendly node name |
| `HERMES_NODE_LISTEN` | `127.0.0.1:9090` | HTTP bind |
| `HERMES_NODE_TOKEN` | generated for current run | Bearer token |
| `HERMES_NODE_ALLOWED_DIRS` | user home | Allowed filesystem roots (`PATH` list syntax) |
| `HERMES_NODE_EXEC_MODE` | `restricted` | `disabled`, `restricted`, or `admin` |
| `HERMES_NODE_ALLOWED_COMMANDS` | `git,docker,hermes,adb,ping` | Comma-separated command allowlist |
| `HERMES_NODE_COMMAND_TIMEOUT` | `30s` | Command timeout |
| `HERMES_NODE_MAX_READ_BYTES` | `1048576` | Maximum text read |
| `HERMES_NODE_MAX_WRITE_BYTES` | `1048576` | Maximum text write |

## Why native, not only Docker

A Desktop Commander replacement must see the actual host filesystem, processes, apps and devices. Run Hermes Node natively for real machine control. A containerized node would only see its container unless granted unsafe host access.
