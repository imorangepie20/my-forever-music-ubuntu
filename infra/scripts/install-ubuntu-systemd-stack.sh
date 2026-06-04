#!/usr/bin/env bash
set -euo pipefail

RUN_USER="${SUDO_USER:-$(id -un)}"
ENABLE=false
START=false
RESTART=false

usage() {
  cat <<'USAGE'
Usage: ./infra/scripts/install-ubuntu-systemd-stack.sh [options]

Options:
  --user USER     systemd service user. Default: current user
  --enable        enable my-forever-music@USER.target on boot
  --start         start my-forever-music@USER.target after install
  --restart       restart my-forever-music@USER.target after install
  -h, --help      show this help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --user)
      RUN_USER="${2:?--user requires a value}"
      shift 2
      ;;
    --enable)
      ENABLE=true
      shift
      ;;
    --start)
      START=true
      shift
      ;;
    --restart)
      RESTART=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown option: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ "$RUN_USER" == "root" ]]; then
  cat >&2 <<'EOF'
Refusing to install the Ubuntu application stack for root.
Run this script from the login account or pass --user USER explicitly.
EOF
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SYSTEMD_DIR="$REPO_ROOT/infra/systemd"
ENV_DIR="/etc/my-forever-music"
TARGET="my-forever-music@${RUN_USER}.target"

if ! id "$RUN_USER" >/dev/null 2>&1; then
  printf 'User does not exist: %s\n' "$RUN_USER" >&2
  exit 1
fi

sudo install -d -m 0755 /etc/systemd/system
sudo install -d -m 0750 "$ENV_DIR"

for unit in \
  my-forever-music@.target \
  my-forever-music-ai@.service \
  my-forever-music-api@.service \
  my-forever-music-web@.service
do
  sudo install -m 0644 "$SYSTEMD_DIR/$unit" "/etc/systemd/system/$unit"
done

for name in api ai web; do
  src="$SYSTEMD_DIR/env/${name}.env.example"
  dest="$ENV_DIR/${name}.env"
  if [[ -e "$dest" ]]; then
    printf '[skip] existing env file kept: %s\n' "$dest"
  else
    sudo install -m 0640 "$src" "$dest"
    printf '[env] created: %s\n' "$dest"
  fi
done

sudo systemctl daemon-reload

if [[ "$ENABLE" == true ]]; then
  sudo systemctl enable "$TARGET"
fi

if [[ "$RESTART" == true ]]; then
  sudo systemctl restart "$TARGET"
elif [[ "$START" == true ]]; then
  sudo systemctl start "$TARGET"
fi

printf '[ok] installed systemd units for %s\n' "$TARGET"
printf 'status: sudo systemctl status %q\n' "$TARGET"
printf 'logs:   sudo journalctl -u %q -f\n' "my-forever-music-api@${RUN_USER}.service"
