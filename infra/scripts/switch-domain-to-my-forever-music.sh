#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

if [[ "$(uname -s)" != "Darwin" ]]; then
  cat >&2 <<'EOF'
switch-domain-to-my-forever-music.sh is for the MacBook Docker domain proxy only.

On Ubuntu, the domain is served by host Nginx and systemd services:
  sudo systemctl status nginx
  sudo systemctl status "my-forever-music@$USER.target"
  BASE_URL=https://imapplepie20.tplinkdns.com ./infra/scripts/check-ubuntu-stack.sh
EOF
  exit 1
fi

OLD_COMPOSE_FILE="${REPO_ROOT}/../humamAppleTeamPreject001/docker-compose.fullstack-local.yml"
NEW_COMPOSE_FILE="${REPO_ROOT}/infra/docker/docker-compose.macbook-domain-proxy.yml"

echo "[1/4] Stopping old domain frontend container from humamAppleTeamPreject001..."
docker compose -f "${OLD_COMPOSE_FILE}" stop frontend || true
docker compose -f "${OLD_COMPOSE_FILE}" rm -f -s frontend || true

echo "[2/4] Starting my-forever-music HTTPS domain proxy..."
docker compose -f "${NEW_COMPOSE_FILE}" up -d

echo "[3/4] Checking local app ports..."
if ! lsof -nP -iTCP:5173 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "WARN: Vite dev server is not listening on 5173."
fi

if ! lsof -nP -iTCP:8081 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "WARN: Spring Boot API is not listening on 8081."
fi

if ! lsof -nP -iTCP:8000 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "INFO: FastAPI is not listening on 8000. This is fine unless you test /gms-preview."
fi

echo "[4/4] Done."
echo "Open: https://imapplepie20.tplinkdns.com"
echo "API docs: https://imapplepie20.tplinkdns.com/docs"
