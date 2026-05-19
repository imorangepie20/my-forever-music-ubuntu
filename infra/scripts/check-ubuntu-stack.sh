#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1}"
SMOKE_USER_ID="${SMOKE_USER_ID:-}"

check() {
  local label="$1"
  shift
  printf '[check] %s\n' "$label"
  "$@"
  printf '\n'
}

check "web via nginx" curl -fsSI "$BASE_URL/"
check "api system info" curl -fsS "$BASE_URL/api/v1/system/info"
check "api health" curl -fsS "$BASE_URL/actuator/health"
check "ai health" curl -fsS "$BASE_URL/ai/health"
check "swagger redirect" curl -fsSI "$BASE_URL/docs"
check "ai docs" curl -fsSI "$BASE_URL/ai/docs"
check "ai recommendation preview" curl -fsS \
  -X POST "$BASE_URL/ai/v1/recommendations/preview" \
  -H 'Content-Type: application/json' \
  -d '{"mode":"discovery","limit":2}'
check "ems collected playlists" curl -fsS \
  "$BASE_URL/api/v1/ems/collection/playlists?platform_id=tidal&limit=2&random=false"
check "melon hot 100" curl -fsS \
  "$BASE_URL/api/v1/main-page/melon-hot-100?limit=3"

if [[ -n "$SMOKE_USER_ID" ]]; then
  check "pms workspace for ${SMOKE_USER_ID}" curl -fsS \
    "$BASE_URL/api/v1/pms/workspace/bootstrap?user_id=${SMOKE_USER_ID}"
fi

printf '[ok] Ubuntu stack smoke test passed for %s\n' "$BASE_URL"
