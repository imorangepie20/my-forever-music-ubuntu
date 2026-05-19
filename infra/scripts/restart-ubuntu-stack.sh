#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [[ "$(uname -s)" != "Linux" ]]; then
  cat >&2 <<'EOF'
restart-ubuntu-stack.sh is for the Ubuntu host stack only.

On MacBook, use:
  ./infra/scripts/restart-macbook-stack.sh
EOF
  exit 1
fi

RUN_USER="${RUN_USER:-$(id -un)}"
BASE_URL="${BASE_URL:-https://imapplepie20.tplinkdns.com}"
SMOKE_USER_ID="${SMOKE_USER_ID:-}"
RESTART_DB=true
RESTART_NGINX=true
RUN_SMOKE=true
SYNC_SYSTEMD_UNITS=true
TAIL_LOGS=false

DOCKER_ENV_FILE="$REPO_ROOT/infra/docker/.env.ubuntu-dev"
COMPOSE_FILE="$REPO_ROOT/infra/docker/docker-compose.ubuntu-dev.yml"
SYSTEMD_INSTALLER="$REPO_ROOT/infra/scripts/install-ubuntu-systemd-stack.sh"
SMOKE_SCRIPT="$REPO_ROOT/infra/scripts/check-ubuntu-stack.sh"

usage() {
  cat <<USAGE
Usage: ./infra/scripts/restart-ubuntu-stack.sh [options]

Restart the Ubuntu host stack:
  1. PostgreSQL + Redis Docker containers
  2. my-forever-music AI/API/Web systemd services
  3. host Nginx
  4. HTTPS smoke test

Options:
  --user USER       systemd service user. Default: current user (${RUN_USER})
  --base-url URL    smoke test base URL. Default: ${BASE_URL}
  --smoke-user ID   optional PMS workspace smoke-test user id
  --skip-db         do not restart PostgreSQL/Redis containers
  --skip-nginx      do not restart Nginx
  --no-smoke        skip final smoke test
  --no-sync-units   do not reinstall repo systemd unit templates before restart
  --tail            tail API/AI/Web journals after restart
  -h, --help        show this help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --user)
      RUN_USER="${2:?--user requires a value}"
      shift 2
      ;;
    --base-url)
      BASE_URL="${2:?--base-url requires a value}"
      shift 2
      ;;
    --smoke-user)
      SMOKE_USER_ID="${2:?--smoke-user requires a value}"
      shift 2
      ;;
    --skip-db)
      RESTART_DB=false
      shift
      ;;
    --skip-nginx)
      RESTART_NGINX=false
      shift
      ;;
    --no-smoke)
      RUN_SMOKE=false
      shift
      ;;
    --no-sync-units)
      SYNC_SYSTEMD_UNITS=false
      shift
      ;;
    --tail)
      TAIL_LOGS=true
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

log() {
  printf '[ubuntu-restart] %s\n' "$*"
}

require_file() {
  local path="$1"
  if [[ ! -f "$path" ]]; then
    printf 'Missing required file: %s\n' "$path" >&2
    exit 1
  fi
}

require_command() {
  local name="$1"
  if ! command -v "$name" >/dev/null 2>&1; then
    printf 'Missing required command: %s\n' "$name" >&2
    exit 1
  fi
}

choose_docker_cmd() {
  if docker ps >/dev/null 2>&1; then
    DOCKER_CMD=(docker)
  else
    DOCKER_CMD=(sudo docker)
  fi
}

docker_compose() {
  "${DOCKER_CMD[@]}" compose --env-file "$DOCKER_ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

wait_for_postgres() {
  local db_user="${POSTGRES_USER:-postgres}"
  local db_name="${POSTGRES_DB:-my_forever_music}"

  for _ in {1..60}; do
    if "${DOCKER_CMD[@]}" exec my-forever-music-postgres pg_isready -U "$db_user" -d "$db_name" >/dev/null 2>&1; then
      log "PostgreSQL ready"
      return
    fi
    sleep 1
  done

  printf 'PostgreSQL did not become ready.\n' >&2
  "${DOCKER_CMD[@]}" logs --tail 80 my-forever-music-postgres >&2 || true
  exit 1
}

wait_for_redis() {
  for _ in {1..60}; do
    if "${DOCKER_CMD[@]}" exec my-forever-music-redis redis-cli ping 2>/dev/null | grep -q '^PONG$'; then
      log "Redis ready"
      return
    fi
    sleep 1
  done

  printf 'Redis did not become ready.\n' >&2
  "${DOCKER_CMD[@]}" logs --tail 80 my-forever-music-redis >&2 || true
  exit 1
}

wait_for_http() {
  local url="$1"
  local label="$2"

  for _ in {1..90}; do
    if curl -fsS "$url" >/dev/null 2>&1; then
      log "$label ready: $url"
      return
    fi
    sleep 1
  done

  printf '%s did not become ready: %s\n' "$label" "$url" >&2
  exit 1
}

restart_docker_services() {
  require_file "$DOCKER_ENV_FILE"
  require_file "$COMPOSE_FILE"
  require_command docker
  choose_docker_cmd

  set -a
  # shellcheck source=/dev/null
  source "$DOCKER_ENV_FILE"
  set +a

  log "Ensuring PostgreSQL/Redis compose services exist"
  docker_compose up -d

  log "Restarting PostgreSQL/Redis containers"
  docker_compose restart postgres redis

  wait_for_postgres
  wait_for_redis
}

sync_systemd_units() {
  require_file "$SYSTEMD_INSTALLER"
  log "Syncing systemd unit templates from repo"
  "$SYSTEMD_INSTALLER" --user "$RUN_USER" --enable
}

restart_systemd_services() {
  log "Restarting AI/API/Web systemd services for user ${RUN_USER}"
  sudo systemctl daemon-reload
  sudo systemctl restart "my-forever-music-ai@${RUN_USER}.service"
  sudo systemctl restart "my-forever-music-api@${RUN_USER}.service"
  sudo systemctl restart "my-forever-music-web@${RUN_USER}.service"
  sudo systemctl start "my-forever-music@${RUN_USER}.target"

  wait_for_http "http://127.0.0.1:8000/health" "AI"
  wait_for_http "http://127.0.0.1:8081/actuator/health" "API"
  wait_for_http "http://127.0.0.1:5173/" "Web"
}

restart_nginx() {
  log "Testing and restarting Nginx"
  sudo nginx -t
  sudo systemctl restart nginx
  wait_for_http "http://127.0.0.1/" "Nginx"
}

run_smoke_test() {
  require_file "$SMOKE_SCRIPT"
  log "Running smoke test against ${BASE_URL}"
  BASE_URL="$BASE_URL" SMOKE_USER_ID="$SMOKE_USER_ID" "$SMOKE_SCRIPT"
}

tail_logs() {
  log "Tailing service logs. Press Ctrl+C to stop."
  sudo journalctl \
    -u "my-forever-music-ai@${RUN_USER}.service" \
    -u "my-forever-music-api@${RUN_USER}.service" \
    -u "my-forever-music-web@${RUN_USER}.service" \
    -f
}

cd "$REPO_ROOT"

if [[ "$RESTART_DB" == true ]]; then
  restart_docker_services
else
  log "Skipping PostgreSQL/Redis restart"
fi

if [[ "$SYNC_SYSTEMD_UNITS" == true ]]; then
  sync_systemd_units
else
  log "Skipping systemd unit sync"
fi

restart_systemd_services

if [[ "$RESTART_NGINX" == true ]]; then
  restart_nginx
else
  log "Skipping Nginx restart"
fi

if [[ "$RUN_SMOKE" == true ]]; then
  run_smoke_test
else
  log "Skipping smoke test"
fi

log "Ubuntu stack restart complete"

if [[ "$TAIL_LOGS" == true ]]; then
  tail_logs
fi
