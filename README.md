# HERMES OS

HERMES OS is the local orchestration layer around Hermes Agent.

## v0.1

This repository provides the infrastructure layer:

- Home Assistant for home/device automation
- Mosquitto MQTT as the local event bus
- n8n for API and business workflows
- Node-RED for event-driven and IoT workflows
- Hermes Agent running on the Linux host as the AI brain

Hermes already provides model/provider selection, memory, skills, MCP, cron and its messaging gateway. HERMES OS intentionally builds around those capabilities instead of duplicating them.

## Architecture

~~~text
You / Telegram / CLI / Phone
             |
         Hermes Agent
    memory · skills · MCP
             |
       Hermes Gateway
             |
   +---------+----------+
   |         |          |
  n8n     Node-RED   Home Assistant
   |         |          |
 APIs      MQTT      devices / ESPHome
             |
          Mosquitto
~~~

## Quick start

~~~bash
git clone https://github.com/yecos/Hermes-Os.git
cd Hermes-Os
cp .env.example .env
docker compose up -d
~~~

Then configure Hermes using its built-in gateway:

~~~bash
hermes gateway setup
hermes gateway install
hermes gateway start
hermes gateway status --deep
~~~

## Services

| Service | Default address | Purpose |
| --- | --- | --- |
| Home Assistant | http://localhost:8123 | Devices and home automation |
| n8n | http://localhost:5678 | API/business workflows |
| Node-RED | http://localhost:1880 | IoT/event workflows |
| MQTT | localhost:1883 | Local event bus |

n8n and Node-RED bind to localhost by default. Change HERMES_OS_BIND in .env only when LAN access is intentional.

## Security baseline

- .env is ignored by Git.
- MQTT is published only on 127.0.0.1 by default.
- n8n and Node-RED are local-only by default.
- Prefer Tailscale/VPN for remote access.
- Sensitive physical actions such as locks should use explicit approvals and allowlists.

See docs/ARCHITECTURE.md and docs/ROADMAP.md.
