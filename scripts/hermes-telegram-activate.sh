#!/usr/bin/env bash
# Read-only by default. Interactive setup of the EXISTING Telegram bot.
set -euo pipefail
umask 077
mode="--check"
if (( $# > 0 )); then mode="$1"; fi
case "$mode" in
--check|--setup) ;;
--help|-h)
  printf 'Usage: bash scripts/hermes-telegram-activate.sh [--check|--setup]\n'
  printf ' --check: read-only, no secrets shown or changes made\n'
  printf ' --setup: private .env backup, Hermes wizard and gateway restart\n'
  exit 0 ;;
*) printf 'Unknown mode, see --help.\n' >&2; exit 2 ;;
esac
command -v hermes >/dev/null || { echo 'Hermes CLI not found.' >&2; exit 1; }
command -v systemctl >/dev/null || { echo 'Systemd unavailable.' >&2; exit 1; }
envfile="$HOME/.hermes/.env"
if [[ -L "$envfile" ]]; then
  echo 'Hermes .env is a symlink: manual inspection required.' >&2
  exit 1
fi
read_presence() {
  python3 - "$envfile" <<'PY'
import re,sys
token=False; allowed=False
try:
    with open(sys.argv[1],encoding="utf-8") as src:
        for line in src:
            m=re.match(r"^\s*(?:export\s+)?(TELEGRAM_BOT_TOKEN|TELEGRAM_ALLOWED_USERS)\s*=\s*(.*?)\s*$",line)
            if not m: continue
            raw=m.group(2).split(" #",1)[0].strip().strip("'\"")
            present=bool(raw) and raw.lower() not in {"none","null","replace_me","your_token"}
            if m.group(1)=="TELEGRAM_BOT_TOKEN":token=present
            if m.group(1)=="TELEGRAM_ALLOWED_USERS":allowed=present
except FileNotFoundError: pass
except PermissionError:
    print("unreadable unreadable")
    sys.exit(0)
print(("present" if token else "missing")+" "+("present" if allowed else "missing"))
PY
}
show_status() {
  local token allowed gateway commander
  read -r token allowed < <(read_presence)
  gateway="inactive"; commander="inactive"
  systemctl --user is-active --quiet hermes-gateway.service && gateway="active"
  systemctl --user is-active --quiet hermes-commander.service && commander="active"
  printf 'Hermes Gateway: %s | Hermes Commander: %s\n' "$gateway" "$commander"
  printf 'Telegram token in private Hermes environment: %s\n' "$token"
  printf 'Allowed Telegram users in private Hermes environment: %s\n' "$allowed"
  printf 'Token and user ID values intentionally hidden.\n'
}
show_status
if [[ "$mode" == "--check" ]]; then
  printf 'Read-only diagnostic done. Run --setup for interactive configuration.\n'
  exit 0
fi
if [[ ! -t 0 || ! -t 1 ]]; then
  echo 'Setup requires a real interactive terminal.' >&2
  exit 2
fi
if [[ ! -d "$HOME/.hermes" ]]; then
  echo 'The Hermes profile directory does not exist.' >&2
  exit 1
fi
if [[ -f "$envfile" ]]; then
  backup="$envfile.before-telegram-$(date -u +%Y%m%dT%H%M%SZ).bak"
  cp -p -- "$envfile" "$backup"
  chmod 600 -- "$backup"
  printf 'Created a private backup of the current Hermes .env (0600 permissions).\n'
fi
printf '\nOpening Hermes official setup wizard. Select Telegram.\n'
printf 'Reuse the existing bot and your numeric user ID; do not paste credentials into chat.\n'
hermes gateway setup
printf 'Restarting only Hermes Gateway...\n'
hermes gateway restart
show_status
recent="$(journalctl --user -u hermes-gateway.service --since '-2 min' --no-pager -o cat 2>/dev/null || true)"
if grep -qF 'No messaging platforms enabled' <<<"$recent"; then
  printf 'WARNING: Hermes reports no messaging platform. Revisit Telegram setup.\n'
elif grep -Eqi 'telegram.*(connected|started|polling|ready|running)' <<<"$recent"; then
  printf 'Telegram startup indication found. Test a private message to your existing bot.\n'
else
  printf 'Gateway restart requested. Confirm via an actual Telegram bot reply.\n'
fi
printf 'No CEO agents, advertising or spending were enabled by this script.\n'
