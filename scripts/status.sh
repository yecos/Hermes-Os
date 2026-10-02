#!/usr/bin/env bash
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "== HERMES OS Docker services =="
docker compose ps || true

echo
echo "== Hermes Agent =="
if command -v hermes >/dev/null 2>&1; then
  hermes status || true
  echo
  echo "== Hermes Gateway =="
  hermes gateway status --deep || true
else
  echo "Hermes Agent not found in PATH."
fi
