#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required."
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose v2 is required."
  exit 1
fi

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "Created .env from .env.example"
fi

echo "Pulling HERMES OS containers..."
docker compose pull

echo "Starting HERMES OS..."
docker compose up -d

echo
echo "Docker services:"
docker compose ps

echo
if command -v hermes >/dev/null 2>&1; then
  echo "Hermes Agent detected."
  hermes gateway status --deep || true
  echo
  echo "If the gateway is not configured yet, run:"
  echo "  hermes gateway setup"
  echo "  hermes gateway install"
  echo "  hermes gateway start"
else
  echo "Hermes Agent was not found in PATH."
fi

echo
echo "HERMES OS v0.1 started."
echo "Home Assistant: http://localhost:8123"
echo "n8n:            http://localhost:5678"
echo "Node-RED:       http://localhost:1880"
