# HERMES OS

HERMES OS is the orchestration layer around Hermes Agent: home automation, workflows, MQTT, local services and a secure HTTP bridge that lets other services ask the currently configured Hermes agent to act.

## v0.1

The first working foundation contains:

- Hermes Agent stays installed on the Linux host and remains the AI brain.
- Hermes Bridge exposes a token-protected local API and invokes Hermes in one-shot mode.
- Home Assistant is the physical-world control plane.
- Mosquitto provides the local MQTT event bus.
- n8n handles API/business workflows.
- Node-RED handles event-driven and IoT workflows.

Hermes itself already provides models, memory, skills, MCP, cron and its messaging gateway. HERMES OS does not duplicate those features.

## Architecture

~~~text
You / Telegram / CLI / Phone
             |
         Hermes Agent
      memory · skills · MCP
             |
      HERMES OS Bridge
             |
   +---------+----------+
   |         |          |
  n8n     Node-RED   Home Assistant
   |         |          |
 APIs      MQTT      devices / ESPHome
             |
          Mosquitto
~~~

See docs/ARCHITECTURE.md for the detailed design.

## Quick start on Linux

Requirements:

- Docker + Docker Compose
- Python 3
- Hermes Agent already installed and configured
- systemd user services (recommended)

~~~bash
git clone https://github.com/yecos/Hermes-Os.git
cd Hermes-Os
chmod +x scripts/*.sh
./scripts/bootstrap.sh
~~~

The bootstrap script creates a local .env file, generates a bridge token, starts the Docker services and, when Hermes is installed, installs the bridge as a user service.

## Local services

| Service | Default address | Purpose |
| --- | --- | --- |
| Home Assistant | http://localhost:8123 | Devices and home automation |
| n8n | http://localhost:5678 | API and business workflows |
| Node-RED | http://localhost:1880 | IoT and event workflows |
| MQTT | localhost:1883 | Event bus |
| Hermes Bridge | http://localhost:8787 | Hermes API bridge |

n8n and Node-RED bind to localhost by default. Change HERMES_OS_BIND in .env to 0.0.0.0 only when you intentionally want LAN access.

## Test Hermes Bridge

~~~bash
set -a
source .env
set +a

curl -s http://127.0.0.1:8787/v1/hermes \
  -H "Content-Type: application/json" \
  -H "X-Hermes-Token: $HERMES_OS_TOKEN" \
  -d '{"prompt":"Responde exactamente: HERMES OS OK"}'
~~~

The bridge runs Hermes as:

~~~bash
hermes -z "your prompt"
~~~

No model is hardcoded. Hermes uses the provider/model currently selected in its own configuration.

Inside n8n, the host bridge is reachable at:

~~~text
http://host.docker.internal:8787/v1/hermes
~~~

Use the X-Hermes-Token header with the HERMES_OS_TOKEN value.

## Hermes gateway

Hermes already includes its own messaging gateway, Home Assistant support, cron, MCP and several messaging platforms. Configure it independently with:

~~~bash
hermes gateway setup
hermes gateway install
hermes gateway start
hermes gateway status --deep
~~~

## Commands

~~~bash
./scripts/status.sh
./scripts/install-bridge.sh
docker compose logs -f
docker compose down
docker compose up -d
~~~

## Security baseline

- .env is ignored by Git.
- The Hermes Bridge requires a long random token.
- MQTT is published only on 127.0.0.1 by default.
- n8n and Node-RED are local-only by default.
- Do not expose the bridge directly to the public internet.
- Prefer Tailscale/VPN for remote access.

## Next milestones

See docs/ROADMAP.md.

The intended direction is:

Hermes + Home Assistant + ESPHome + n8n/Node-RED + Browser Use + screen context + Frigate + S10/Termux nodes.
