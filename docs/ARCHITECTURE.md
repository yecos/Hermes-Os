# HERMES OS Architecture

## Core principle

Hermes Agent remains the brain. HERMES OS is an orchestration and infrastructure layer around Hermes, not a fork and not a replacement.

Hermes already owns:

- model/provider selection
- memory and user context
- skills
- MCP tools
- cron
- messaging gateway
- tool execution

HERMES OS owns:

- local service lifecycle
- home and IoT event routing
- workflow automation
- device/service integration
- future computer-vision, browser and edge-node modules

## v0.1 components

### Hermes Agent

Runs directly on the Linux host and keeps using its active model/provider.

### Hermes Gateway

The native Hermes gateway is the preferred integration path. It is configured with:

~~~bash
hermes gateway setup
hermes gateway install
hermes gateway start
hermes gateway status --deep
~~~

### Home Assistant

Physical-world control plane.

Future integrations include ESPHome, lights, locks, cameras, sensors and voice entry points.

### Mosquitto

Local MQTT event bus.

The broker is exposed only on 127.0.0.1 by default. Containers on the hermes-os network can use mqtt:1883. Because Home Assistant uses host networking it can use 127.0.0.1:1883.

### Node-RED

Use Node-RED for low-latency event and IoT workflows:

- MQTT
- Home Assistant events
- sensors
- device orchestration

### n8n

Use n8n for higher-level workflows:

- APIs
- webhooks
- external services
- scheduled business processes
- structured workflow coordination

## Trust boundaries

Physical and computer-control actions need explicit security boundaries.

1. Do not expose internal automation services directly to the public internet.
2. Keep MQTT local unless authentication and TLS are configured.
3. Prefer Tailscale or another authenticated VPN for remote access.
4. Sensitive actions such as door locks should require explicit approvals and allowlists.
5. Treat camera and screen data as private by default.
6. Add audit logging before enabling autonomous physical actions.

## Planned modules

- ESPHome
- Browser/computer use
- screen context and searchable activity memory
- Frigate camera events
- Scrypted camera/device normalization
- S10/Termux edge node
- RAG/document memory
- network topology and health
- unified dashboard
