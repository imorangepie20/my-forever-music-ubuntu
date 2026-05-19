#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

if [[ "$(uname -s)" != "Darwin" ]]; then
  cat >&2 <<'EOF'
restart-macbook-stack.sh is for the MacBook domain-dev stack only.

On Ubuntu, use the systemd/Nginx stack instead:
  sudo systemctl restart "my-forever-music@$USER.target"
  BASE_URL=https://imapplepie20.tplinkdns.com ./infra/scripts/check-ubuntu-stack.sh
EOF
  exit 1
fi

DOCKER_DIR="${REPO_ROOT}/infra/docker"
NGINX_SSL_CONFIG_DIR="${NGINX_SSL_CONFIG_DIR:-/Users/woosungjo/preProject/humamAppleTeamPreject001/ssl/config}"
NGINX_SSL_DIR="${NGINX_SSL_CONFIG_DIR}/live/imapplepie20.tplinkdns.com"
API_DIR="${REPO_ROOT}/services/api"
AI_DIR="${REPO_ROOT}/services/ai"
WEB_DIR="${REPO_ROOT}/apps/web"

RUNTIME_DIR="${REPO_ROOT}/tmp/local-stack"
LOG_DIR="${RUNTIME_DIR}/logs"
PID_DIR="${RUNTIME_DIR}/pids"

DB_COMPOSE_FILE="${DOCKER_DIR}/docker-compose.local-db.yml"
DOMAIN_PROXY_COMPOSE_FILE="${DOCKER_DIR}/docker-compose.macbook-domain-proxy.yml"
DOCKER_ENV_FILE="${DOCKER_DIR}/.env.local"
AI_ENV_FILE="${AI_DIR}/.env.local"
API_ENV_FILE="${API_DIR}/.env.local"
WEB_ENV_FILE="${WEB_DIR}/.env.local"

PUBLIC_DOMAIN="${PUBLIC_DOMAIN:-imapplepie20.tplinkdns.com}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-my-forever-music-local-postgres}"
REDIS_CONTAINER="${REDIS_CONTAINER:-my-forever-music-local-redis}"
PROXY_CONTAINER="${PROXY_CONTAINER:-my-forever-music-domain-proxy}"

DB_PORT="5433"
REDIS_PORT="${REDIS_PORT:-6379}"
POSTGRES_PORT="5433"
AI_PORT="${AI_PORT:-8000}"
API_PORT="${API_PORT:-8081}"
WEB_PORT="${WEB_PORT:-5173}"
WEB_HOST="${WEB_HOST:-0.0.0.0}"

SCREEN_PREFIX="${SCREEN_PREFIX:-my-forever-music}"
AI_SCREEN_SESSION="${AI_SCREEN_SESSION:-${SCREEN_PREFIX}-ai}"
API_SCREEN_SESSION="${API_SCREEN_SESSION:-${SCREEN_PREFIX}-api}"
WEB_SCREEN_SESSION="${WEB_SCREEN_SESSION:-${SCREEN_PREFIX}-web}"

TAIL_LOGS=false

usage() {
  cat <<USAGE
Usage: ./infra/scripts/restart-macbook-stack.sh [--tail]

Restart the whole MacBook development stack in one command:
  1. PostgreSQL + Redis Docker containers
  2. HTTPS domain proxy Docker container
  3. FastAPI AI service
  4. Spring Boot API service
  5. Vite web service

Options:
  --tail      Tail AI/API/Web logs after startup
  -h, --help  Show this help

Main URLs:
  https://${PUBLIC_DOMAIN}/tidal-playlist-test
  http://127.0.0.1:${API_PORT}/actuator/health
  http://127.0.0.1:${AI_PORT}/health
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tail)
      TAIL_LOGS=true
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

mkdir -p "${LOG_DIR}" "${PID_DIR}"

log() {
  printf '[restart-stack] %s\n' "$*"
}

shell_quote() {
  printf '%q' "$1"
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

validate_env_file() {
  local env_file="$1"
  local label="$2"
  local invalid_lines

  invalid_lines="$(
    awk '
      /^[[:space:]]*$/ || /^[[:space:]]*#/ { next }
      !/^[A-Za-z_][A-Za-z0-9_]*=/ { print FNR ":" $0 }
    ' "${env_file}"
  )"

  if [[ -n "${invalid_lines}" ]]; then
    echo "${label} env file has invalid assignment line(s): ${env_file}" >&2
    echo "${invalid_lines}" >&2
    exit 1
  fi
}

source_required_env_file() {
  local env_file="$1"
  local label="$2"

  if [[ ! -f "${env_file}" ]]; then
    echo "Missing ${label} env file: ${env_file}" >&2
    exit 1
  fi

  validate_env_file "${env_file}" "${label}"
  set -a
  # shellcheck source=/dev/null
  source "${env_file}"
  set +a
}

source_optional_env_file() {
  local env_file="$1"

  if [[ -f "${env_file}" ]]; then
    validate_env_file "${env_file}" "optional"
    set -a
    # shellcheck source=/dev/null
    source "${env_file}"
    set +a
  fi
}

stop_screen_session() {
  local session_name="$1"
  local label="$2"

  if ! command -v screen >/dev/null 2>&1; then
    return
  fi

  if screen -ls | grep -q "[[:space:]]${session_name}[[:space:]]"; then
    log "${label}: stopping screen session ${session_name}"
    screen -S "${session_name}" -X quit >/dev/null 2>&1 || true
  fi
}

stop_port_listener() {
  local port="$1"
  local label="$2"
  local pids

  pids="$(lsof -tiTCP:"${port}" -sTCP:LISTEN || true)"
  if [[ -z "${pids}" ]]; then
    log "${label}: no listener on port ${port}"
    return
  fi

  log "${label}: stopping listener(s) on port ${port}: ${pids//$'\n'/ }"
  kill ${pids}

  for _ in {1..24}; do
    sleep 0.25
    if [[ -z "$(lsof -tiTCP:"${port}" -sTCP:LISTEN || true)" ]]; then
      log "${label}: port ${port} released"
      return
    fi
  done

  echo "${label}: port ${port} is still in use after SIGTERM. Stop it manually and rerun this script." >&2
  exit 1
}

start_detached_shell() {
  local session_name="$1"
  local label="$2"
  local workdir="$3"
  local log_file="$4"
  local pid_file="$5"
  local command="$6"

  : >"${log_file}"

  if command -v screen >/dev/null 2>&1; then
    stop_screen_session "${session_name}" "${label}"
    log "${label}: starting detached screen session ${session_name}"
    screen -dmS "${session_name}" bash -lc "cd $(shell_quote "${workdir}") && ${command} >>$(shell_quote "${log_file}") 2>&1"
    echo "screen:${session_name}" >"${pid_file}"
    return
  fi

  log "${label}: screen not found, starting with nohup"
  (
    cd "${workdir}"
    exec bash -lc "${command}"
  ) >"${log_file}" 2>&1 </dev/null &
  echo "$!" >"${pid_file}"
}

compose_down_up() {
  local compose_file="$1"
  local label="$2"

  log "${label}: docker compose down"
  docker compose -f "${compose_file}" down
  log "${label}: docker compose up"
  docker compose -f "${compose_file}" up -d
}

wait_for_container_health() {
  local container_name="$1"
  local label="$2"

  for _ in {1..80}; do
    local status
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_name}" 2>/dev/null || true)"
    if [[ "${status}" == "healthy" || "${status}" == "running" ]]; then
      log "${label}: ${status}"
      return
    fi
    sleep 1
  done

  echo "${label}: container did not become healthy: ${container_name}" >&2
  docker logs --tail 80 "${container_name}" >&2 || true
  exit 1
}

wait_for_http() {
  local url="$1"
  local label="$2"

  for _ in {1..100}; do
    if curl -fsS "${url}" >/dev/null 2>&1; then
      log "${label}: ready ${url}"
      return
    fi
    sleep 0.5
  done

  echo "${label}: did not become ready: ${url}" >&2
  case "${label}" in
    ai) tail -n 100 "${LOG_DIR}/ai.log" >&2 || true ;;
    api) tail -n 100 "${LOG_DIR}/api.log" >&2 || true ;;
    web) tail -n 100 "${LOG_DIR}/web.log" >&2 || true ;;
  esac
  exit 1
}

validate_real_ssl_certificate() {
  if [[ ! -f "${NGINX_SSL_DIR}/fullchain.pem" || ! -f "${NGINX_SSL_DIR}/privkey.pem" ]]; then
    echo "Missing real SSL certificate files for ${PUBLIC_DOMAIN}: ${NGINX_SSL_DIR}" >&2
    exit 1
  fi

  if openssl x509 -in "${NGINX_SSL_DIR}/fullchain.pem" -noout -issuer 2>/dev/null | grep -qi 'mkcert'; then
    echo "Refusing to start domain proxy with mkcert certificate. Use the real certificate for ${PUBLIC_DOMAIN}." >&2
    exit 1
  fi
}

restart_docker_services() {
  source_optional_env_file "${DOCKER_ENV_FILE}"
  export POSTGRES_PORT="5433"
  export REDIS_PORT="${REDIS_PORT:-6379}"

  compose_down_up "${DB_COMPOSE_FILE}" "docker-db"
  wait_for_container_health "${POSTGRES_CONTAINER}" "postgres"
  wait_for_container_health "${REDIS_CONTAINER}" "redis"

  validate_real_ssl_certificate
  export NGINX_SSL_CONFIG_DIR
  compose_down_up "${DOMAIN_PROXY_COMPOSE_FILE}" "docker-domain-proxy"
  wait_for_container_health "${PROXY_CONTAINER}" "domain-proxy"
}

restart_ai() {
  source_optional_env_file "${AI_ENV_FILE}"

  if [[ ! -x "${AI_DIR}/.venv/bin/uvicorn" ]]; then
    echo "Missing AI virtualenv executable: ${AI_DIR}/.venv/bin/uvicorn" >&2
    echo "Create it with: cd ${AI_DIR} && python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements-dev.txt" >&2
    exit 1
  fi

  stop_screen_session "${AI_SCREEN_SESSION}" "ai"
  stop_port_listener "${AI_PORT}" "ai"

  log "ai: starting FastAPI on port ${AI_PORT}"
  start_detached_shell \
    "${AI_SCREEN_SESSION}" \
    "ai" \
    "${AI_DIR}" \
    "${LOG_DIR}/ai.log" \
    "${PID_DIR}/ai.pid" \
    "export AI_ROOT_PATH=''; exec ./.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port $(shell_quote "${AI_PORT}")"
  wait_for_http "http://127.0.0.1:${AI_PORT}/health" "ai"
}

restart_api() {
  source_required_env_file "${API_ENV_FILE}" "API"

  local java_home_default="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  local api_path="/opt/homebrew/opt/openjdk@21/bin:/opt/homebrew/bin:${PATH}"
  local resolved_java_home="${JAVA_HOME:-${java_home_default}}"
  local resolved_profile="${SPRING_PROFILES_ACTIVE:-database}"
  local resolved_api_port="${API_PORT:-8081}"
  local resolved_db_port="5433"
  local resolved_ai_url="http://127.0.0.1:${AI_PORT}"
  if grep -q '^[[:space:]]*AI_SERVICE_BASE_URL=' "${API_ENV_FILE}"; then
    resolved_ai_url="${AI_SERVICE_BASE_URL:-${resolved_ai_url}}"
  fi

  stop_screen_session "${API_SCREEN_SESSION}" "api"
  stop_port_listener "${resolved_api_port}" "api"

  log "api: starting Spring Boot on port ${resolved_api_port} with profile ${resolved_profile}"
  start_detached_shell \
    "${API_SCREEN_SESSION}" \
    "api" \
    "${API_DIR}" \
    "${LOG_DIR}/api.log" \
    "${PID_DIR}/api.pid" \
    "set -a; source $(shell_quote "${API_ENV_FILE}"); set +a; export PATH=$(shell_quote "${api_path}"); export JAVA_HOME=$(shell_quote "${resolved_java_home}"); export SPRING_PROFILES_ACTIVE=$(shell_quote "${resolved_profile}"); export API_PORT=$(shell_quote "${resolved_api_port}"); export DB_PORT=$(shell_quote "${resolved_db_port}"); export AI_SERVICE_BASE_URL=$(shell_quote "${resolved_ai_url}"); exec ./gradlew bootRun"
  wait_for_http "http://127.0.0.1:${resolved_api_port}/actuator/health" "api"
}

restart_web() {
  source_required_env_file "${WEB_ENV_FILE}" "web"

  local resolved_web_host="${WEB_HOST:-0.0.0.0}"
  local resolved_web_port="${WEB_PORT:-5173}"

  stop_screen_session "${WEB_SCREEN_SESSION}" "web"
  stop_port_listener "${resolved_web_port}" "web"

  log "web: starting Vite on ${resolved_web_host}:${resolved_web_port}"
  start_detached_shell \
    "${WEB_SCREEN_SESSION}" \
    "web" \
    "${WEB_DIR}" \
    "${LOG_DIR}/web.log" \
    "${PID_DIR}/web.pid" \
    "set -a; source $(shell_quote "${WEB_ENV_FILE}"); set +a; export WEB_HOST=$(shell_quote "${resolved_web_host}"); export WEB_PORT=$(shell_quote "${resolved_web_port}"); exec npm run dev -- --host \"\${WEB_HOST}\" --port \"\${WEB_PORT}\""
  wait_for_http "http://127.0.0.1:${resolved_web_port}/tidal-playlist-test" "web"
}

print_status() {
  log "stack ready"
  log "web https:  https://${PUBLIC_DOMAIN}/tidal-playlist-test"
  log "web local:  http://127.0.0.1:${WEB_PORT}/tidal-playlist-test"
  log "api health: http://127.0.0.1:${API_PORT}/actuator/health"
  log "ai health:  http://127.0.0.1:${AI_PORT}/health"
  log "logs:       ${LOG_DIR}"
}

require_command docker
require_command lsof
require_command curl
require_command npm
require_command openssl

restart_docker_services
restart_ai
restart_api
restart_web
print_status

if [[ "${TAIL_LOGS}" == true ]]; then
  tail -n 80 -f "${LOG_DIR}/ai.log" "${LOG_DIR}/api.log" "${LOG_DIR}/web.log"
fi
