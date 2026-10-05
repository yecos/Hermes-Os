#!/data/data/com.termux/files/usr/bin/sh
set -eu

SOURCE_BINARY="\${1:-}"
if [ -z "$SOURCE_BINARY" ] || [ ! -f "$SOURCE_BINARY" ]; then
  echo "usage: $0 /path/to/hermes-node-android-arm64" >&2
  exit 2
fi

HOME_DIR="\${HOME:-/data/data/com.termux/files/home}"
INSTALL_DIR="$HOME_DIR/.local/bin"
CONFIG_DIR="$HOME_DIR/.hermes-node"
CONFIG_FILE="$CONFIG_DIR/hermes-node.env"
BOOT_DIR="$HOME_DIR/.termux/boot"
BOOT_SCRIPT="$BOOT_DIR/start-hermes-node.sh"
BIN="$INSTALL_DIR/hermes-node"
LOG="$CONFIG_DIR/server.log"
PIDFILE="$CONFIG_DIR/server.pid"

mkdir -p "$INSTALL_DIR" "$CONFIG_DIR" "$BOOT_DIR"
cp "$SOURCE_BINARY" "$BIN"
chmod 700 "$BIN"

if [ ! -f "$CONFIG_FILE" ]; then
  if command -v openssl >/dev/null 2>&1; then
    TOKEN="$(openssl rand -hex 32)"
  else
    TOKEN="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
  fi

  cat > "$CONFIG_FILE" <<EOF
# Hermes Node policy for Termux / Android
HERMES_NODE_NAME=S10
HERMES_NODE_LISTEN=0.0.0.0:9090
HERMES_NODE_TOKEN=$TOKEN
HERMES_NODE_ALLOWED_DIRS=$HOME_DIR
HERMES_NODE_EXEC_MODE=admin
HERMES_NODE_ALLOWED_COMMANDS=git,python,python3,node,npm,ssh,curl,wget,termux-info,am,pm
HERMES_NODE_COMMAND_TIMEOUT=120s
HERMES_NODE_MAX_READ_BYTES=4194304
HERMES_NODE_MAX_WRITE_BYTES=4194304
HERMES_NODE_ALLOWED_REMOTE_CIDRS=100.64.0.0/10
EOF
  chmod 600 "$CONFIG_FILE"
else
  if grep -q '^HERMES_NODE_LISTEN=' "$CONFIG_FILE"; then
    sed -i 's/^HERMES_NODE_LISTEN=.*/HERMES_NODE_LISTEN=0.0.0.0:9090/' "$CONFIG_FILE"
  else
    echo 'HERMES_NODE_LISTEN=0.0.0.0:9090' >> "$CONFIG_FILE"
  fi
  if grep -q '^HERMES_NODE_ALLOWED_REMOTE_CIDRS=' "$CONFIG_FILE"; then
    sed -i 's#^HERMES_NODE_ALLOWED_REMOTE_CIDRS=.*#HERMES_NODE_ALLOWED_REMOTE_CIDRS=100.64.0.0/10#' "$CONFIG_FILE"
  else
    echo 'HERMES_NODE_ALLOWED_REMOTE_CIDRS=100.64.0.0/10' >> "$CONFIG_FILE"
  fi
fi

cat > "$BOOT_SCRIPT" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
HOME_DIR="\${HOME:-/data/data/com.termux/files/home}"
CONFIG_FILE="$HOME_DIR/.hermes-node/hermes-node.env"
BIN="$HOME_DIR/.local/bin/hermes-node"
LOG="$HOME_DIR/.hermes-node/server.log"
PIDFILE="$HOME_DIR/.hermes-node/server.pid"

[ -f "$CONFIG_FILE" ] || exit 0
[ -x "$BIN" ] || exit 0

set -a
. "$CONFIG_FILE"
set +a

command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock >/dev/null 2>&1 || true

if [ -f "$PIDFILE" ]; then
  oldpid="$(cat "$PIDFILE" 2>/dev/null || true)"
  if [ -n "$oldpid" ] && kill -0 "$oldpid" 2>/dev/null; then
    exit 0
  fi
fi

nohup "$BIN" serve >> "$LOG" 2>&1 &
echo $! > "$PIDFILE"
EOF
chmod 700 "$BOOT_SCRIPT"

if [ -f "$PIDFILE" ]; then
  oldpid="$(cat "$PIDFILE" 2>/dev/null || true)"
  if [ -n "$oldpid" ] && kill -0 "$oldpid" 2>/dev/null; then
    kill "$oldpid" 2>/dev/null || true
    sleep 1
  fi
fi

"$BOOT_SCRIPT"
sleep 1

if command -v curl >/dev/null 2>&1; then
  curl -fsS --max-time 3 http://127.0.0.1:9090/v1/health >/dev/null
fi

echo "Hermes Node installed for Termux."
echo "Binary: $BIN"
echo "Config: $CONFIG_FILE"
echo "Boot: $BOOT_SCRIPT"
echo "Listen: 0.0.0.0:9090 (requests restricted to 100.64.0.0/10; loopback allowed)"
echo "Token was not printed."
