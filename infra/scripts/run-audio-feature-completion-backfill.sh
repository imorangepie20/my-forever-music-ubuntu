#!/usr/bin/env bash
set -Eeuo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
ADMIN_USER_ID="${ADMIN_USER_ID:-}"
TARGET_USER_ID="${TARGET_USER_ID:-}"
SCOPE="${SCOPE:-pms}"
PMS_LIMIT="${PMS_LIMIT:-50}"
EMS_LIMIT="${EMS_LIMIT:-50}"
PROCESS_LIMIT="${PROCESS_LIMIT:-10}"
ROUNDS="${ROUNDS:-5}"
SLEEP_SECONDS="${SLEEP_SECONDS:-2}"
WORKER_ID="${WORKER_ID:-audio-feature-backfill-manual}"
POSITIVE_EVENTS=false
POSITIVE_SCOPE="${POSITIVE_SCOPE:-all}"
POSITIVE_EVENT_LIMIT="${POSITIVE_EVENT_LIMIT:-500}"
POSITIVE_LIMIT="${POSITIVE_LIMIT:-20}"
REQUEUE_UNRESOLVED=false
REQUEUE_LIMIT="${REQUEUE_LIMIT:-20}"
REQUEUE_TRACK_SCOPE="${REQUEUE_TRACK_SCOPE:-}"
REQUEUE_LAST_ERROR="${REQUEUE_LAST_ERROR:-}"
DRY_RUN=false

usage() {
  cat <<USAGE
Usage: ./infra/scripts/run-audio-feature-completion-backfill.sh [options]

Safely enqueue and process audio feature completion jobs.

Required:
  --admin-user ID       admin user id allowed to call recommendation admin APIs

Options:
  --target-user ID      PMS user whose library should be scanned. Default: admin user
  --base-url URL        API base URL. Default: ${BASE_URL}
  --scope SCOPE         pms, ems, or all. Default: ${SCOPE}
  --pms-limit N         PMS tracks to scan on enqueue. Default: ${PMS_LIMIT}
  --ems-limit N         EMS tracks to scan on enqueue. Default: ${EMS_LIMIT}
  --process-limit N     jobs to claim per processing round. Default: ${PROCESS_LIMIT}
  --rounds N            max processing rounds. Default: ${ROUNDS}
  --sleep SECONDS       delay between processing rounds. Default: ${SLEEP_SECONDS}
  --worker-id ID        worker id stored on claimed jobs. Default: ${WORKER_ID}
  --positive-events     enqueue feature-missing tracks with positive user events
  --positive-scope SCOPE
                        positive event track scope: all, pms, or ems. Default: ${POSITIVE_SCOPE}
  --positive-event-limit N
                        recent user music events to scan. Default: ${POSITIVE_EVENT_LIMIT}
  --positive-limit N    max positive-event tracks to enqueue. Default: ${POSITIVE_LIMIT}
  --requeue-unresolved  create manual_llm_retry jobs before processing
  --requeue-limit N     max unresolved jobs to requeue. Default: ${REQUEUE_LIMIT}
  --track-scope SCOPE   requeue scope: pms_user_track or ems_collected_track
  --last-error REASON   requeue only unresolved jobs with this exact reason
  --dry-run             print the planned calls without hitting the API
  -h, --help            show this help

Examples:
  ADMIN_USER_ID=user-... TARGET_USER_ID=user-... ./infra/scripts/run-audio-feature-completion-backfill.sh

  ./infra/scripts/run-audio-feature-completion-backfill.sh \\
    --admin-user user-... \\
    --target-user user-... \\
    --pms-limit 80 \\
    --process-limit 10 \\
    --rounds 8

  ./infra/scripts/run-audio-feature-completion-backfill.sh \\
    --admin-user user-... \\
    --target-user user-... \\
    --positive-events \\
    --positive-scope ems \\
    --positive-limit 10 \\
    --process-limit 5 \\
    --rounds 2

  ./infra/scripts/run-audio-feature-completion-backfill.sh \\
    --admin-user user-... \\
    --target-user user-... \\
    --requeue-unresolved \\
    --track-scope pms_user_track \\
    --last-error llm_search_low_confidence
USAGE
}

log() {
  printf '[audio-feature-backfill] %s\n' "$*"
}

fail() {
  printf '[audio-feature-backfill] ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  local name="$1"
  if ! command -v "$name" >/dev/null 2>&1; then
    fail "Missing required command: ${name}"
  fi
}

require_positive_int() {
  local name="$1"
  local value="$2"
  if [[ ! "$value" =~ ^[0-9]+$ ]] || (( value < 1 )); then
    fail "${name} must be a positive integer, got: ${value}"
  fi
}

trim_trailing_slash() {
  local value="$1"
  while [[ "$value" == */ ]]; do
    value="${value%/}"
  done
  printf '%s' "$value"
}

json_get() {
  local path="$1"
  python3 -c '
import json
import sys

path = sys.argv[1].split(".")
try:
    current = json.load(sys.stdin)
    for part in path:
        if isinstance(current, list):
            current = current[int(part)]
        else:
            current = current[part]
except Exception:
    print("")
    sys.exit(0)

if current is None:
    print("")
elif isinstance(current, bool):
    print("true" if current else "false")
else:
    print(current)
' "$path"
}

json_status_counts() {
  python3 -c '
import json
import sys

try:
    data = json.load(sys.stdin)
    counts = data.get("audio_feature_completion", {}).get("status_counts", [])
except Exception:
    counts = []

if not counts:
    print("none")
else:
    print(", ".join("{}={}".format(row.get("status"), row.get("job_count")) for row in counts))
'
}

api_get() {
  local path="$1"
  shift
  local url="${BASE_URL}${path}"
  local args=(-fsS --get "$url")
  local param

  for param in "$@"; do
    args+=(--data-urlencode "$param")
  done

  if [[ "$DRY_RUN" == true ]]; then
    printf >&2 'curl'
    printf >&2 ' %q' "${args[@]}"
    printf >&2 '\n'
    printf '{}'
    return
  fi

  curl "${args[@]}"
}

api_post() {
  local path="$1"
  shift
  local url="${BASE_URL}${path}"
  local args=(-fsS -X POST "$url")
  local param

  for param in "$@"; do
    args+=(--data-urlencode "$param")
  done

  if [[ "$DRY_RUN" == true ]]; then
    printf >&2 'curl'
    printf >&2 ' %q' "${args[@]}"
    printf >&2 '\n'
    printf '{}'
    return
  fi

  curl "${args[@]}"
}

print_profile_summary() {
  local response="$1"
  local status applicable positive track_count usable ratio confidence focus

  status="$(printf '%s' "$response" | json_get "status")"
  applicable="$(printf '%s' "$response" | json_get "profile.audio_taste_applicable")"
  positive="$(printf '%s' "$response" | json_get "profile.positive_track_count")"
  track_count="$(printf '%s' "$response" | json_get "profile.coverage.track_count")"
  usable="$(printf '%s' "$response" | json_get "profile.coverage.usable_track_count")"
  ratio="$(printf '%s' "$response" | json_get "profile.coverage.feature_ready_ratio")"
  confidence="$(printf '%s' "$response" | json_get "profile.profile_confidence")"
  focus="$(printf '%s' "$response" | json_get "profile.profile_focus")"

  log "audio taste: status=${status:-unknown}, applicable=${applicable:-unknown}, positive_ready=${positive:-?}, usable=${usable:-?}/${track_count:-?}, ratio=${ratio:-?}, confidence=${confidence:-?}, focus=${focus:-?}"
}

print_coverage_summary() {
  local response="$1"
  local tracks filled ratio recent counts

  tracks="$(printf '%s' "$response" | json_get "pms_library.track_count")"
  filled="$(printf '%s' "$response" | json_get "pms_library.audio_feature_filled_count")"
  ratio="$(printf '%s' "$response" | json_get "pms_library.audio_feature_coverage_ratio")"
  recent="$(printf '%s' "$response" | json_get "audio_feature_completion.recent_job_count")"
  counts="$(printf '%s' "$response" | json_status_counts)"

  log "feature coverage: PMS audio=${filled:-?}/${tracks:-?}, ratio=${ratio:-?}, recent_jobs=${recent:-?}, statuses=${counts}"
}

print_enqueue_summary() {
  local response="$1"
  local scanned enqueued skipped

  scanned="$(printf '%s' "$response" | json_get "scanned_track_count")"
  enqueued="$(printf '%s' "$response" | json_get "enqueued_job_count")"
  skipped="$(printf '%s' "$response" | json_get "skipped_existing_job_count")"

  log "enqueue: scanned=${scanned:-?}, enqueued=${enqueued:-?}, skipped_existing=${skipped:-?}"
}

print_positive_enqueue_summary() {
  local response="$1"
  local scanned enqueued skipped

  scanned="$(printf '%s' "$response" | json_get "scanned_track_count")"
  enqueued="$(printf '%s' "$response" | json_get "enqueued_job_count")"
  skipped="$(printf '%s' "$response" | json_get "skipped_existing_job_count")"

  log "positive enqueue: scanned=${scanned:-?}, enqueued=${enqueued:-?}, skipped_existing=${skipped:-?}"
}

print_requeue_summary() {
  local response="$1"
  local scanned requeued skipped

  scanned="$(printf '%s' "$response" | json_get "scanned_job_count")"
  requeued="$(printf '%s' "$response" | json_get "requeued_job_count")"
  skipped="$(printf '%s' "$response" | json_get "skipped_existing_job_count")"

  log "requeue unresolved: scanned=${scanned:-?}, requeued=${requeued:-?}, skipped_existing=${skipped:-?}"
}

print_process_summary() {
  local round="$1"
  local response="$2"
  local claimed completed retry_wait unresolved failed

  claimed="$(printf '%s' "$response" | json_get "claimed_job_count")"
  completed="$(printf '%s' "$response" | json_get "completed_job_count")"
  retry_wait="$(printf '%s' "$response" | json_get "retry_wait_job_count")"
  unresolved="$(printf '%s' "$response" | json_get "unresolved_job_count")"
  failed="$(printf '%s' "$response" | json_get "failed_job_count")"

  log "process round ${round}: claimed=${claimed:-0}, completed=${completed:-0}, retry_wait=${retry_wait:-0}, unresolved=${unresolved:-0}, failed=${failed:-0}"
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --admin-user)
        ADMIN_USER_ID="${2:?--admin-user requires a value}"
        shift 2
        ;;
      --target-user)
        TARGET_USER_ID="${2:?--target-user requires a value}"
        shift 2
        ;;
      --base-url)
        BASE_URL="${2:?--base-url requires a value}"
        shift 2
        ;;
      --scope)
        SCOPE="${2:?--scope requires a value}"
        shift 2
        ;;
      --pms-limit)
        PMS_LIMIT="${2:?--pms-limit requires a value}"
        shift 2
        ;;
      --ems-limit)
        EMS_LIMIT="${2:?--ems-limit requires a value}"
        shift 2
        ;;
      --process-limit)
        PROCESS_LIMIT="${2:?--process-limit requires a value}"
        shift 2
        ;;
      --rounds)
        ROUNDS="${2:?--rounds requires a value}"
        shift 2
        ;;
      --sleep)
        SLEEP_SECONDS="${2:?--sleep requires a value}"
        shift 2
        ;;
      --worker-id)
        WORKER_ID="${2:?--worker-id requires a value}"
        shift 2
        ;;
      --positive-events)
        POSITIVE_EVENTS=true
        shift
        ;;
      --positive-scope)
        POSITIVE_SCOPE="${2:?--positive-scope requires a value}"
        shift 2
        ;;
      --positive-event-limit)
        POSITIVE_EVENT_LIMIT="${2:?--positive-event-limit requires a value}"
        shift 2
        ;;
      --positive-limit)
        POSITIVE_LIMIT="${2:?--positive-limit requires a value}"
        shift 2
        ;;
      --requeue-unresolved)
        REQUEUE_UNRESOLVED=true
        shift
        ;;
      --requeue-limit)
        REQUEUE_LIMIT="${2:?--requeue-limit requires a value}"
        shift 2
        ;;
      --track-scope)
        REQUEUE_TRACK_SCOPE="${2:?--track-scope requires a value}"
        shift 2
        ;;
      --last-error)
        REQUEUE_LAST_ERROR="${2:?--last-error requires a value}"
        shift 2
        ;;
      --dry-run)
        DRY_RUN=true
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
}

validate_config() {
  require_command curl
  require_command python3

  BASE_URL="$(trim_trailing_slash "$BASE_URL")"

  if [[ -z "$ADMIN_USER_ID" ]]; then
    fail "ADMIN_USER_ID is required. Pass --admin-user or set ADMIN_USER_ID."
  fi
  if [[ -z "$TARGET_USER_ID" ]]; then
    TARGET_USER_ID="$ADMIN_USER_ID"
  fi
  case "$SCOPE" in
    pms|ems|all) ;;
    *) fail "--scope must be pms, ems, or all" ;;
  esac
  case "$POSITIVE_SCOPE" in
    all|pms|ems) ;;
    *) fail "--positive-scope must be all, pms, or ems" ;;
  esac
  if [[ -n "$REQUEUE_TRACK_SCOPE" ]]; then
    case "$REQUEUE_TRACK_SCOPE" in
      pms_user_track|ems_collected_track) ;;
      *) fail "--track-scope must be pms_user_track or ems_collected_track" ;;
    esac
  fi

  require_positive_int "--pms-limit" "$PMS_LIMIT"
  require_positive_int "--ems-limit" "$EMS_LIMIT"
  require_positive_int "--process-limit" "$PROCESS_LIMIT"
  require_positive_int "--rounds" "$ROUNDS"
  require_positive_int "--sleep" "$SLEEP_SECONDS"
  require_positive_int "--positive-event-limit" "$POSITIVE_EVENT_LIMIT"
  require_positive_int "--positive-limit" "$POSITIVE_LIMIT"
  require_positive_int "--requeue-limit" "$REQUEUE_LIMIT"
}

print_config() {
  log "base_url=${BASE_URL}"
  log "admin_user=${ADMIN_USER_ID}"
  log "target_user=${TARGET_USER_ID}"
  log "scope=${SCOPE}, pms_limit=${PMS_LIMIT}, ems_limit=${EMS_LIMIT}"
  log "process_limit=${PROCESS_LIMIT}, rounds=${ROUNDS}, sleep=${SLEEP_SECONDS}, worker_id=${WORKER_ID}"
  if [[ "$POSITIVE_EVENTS" == true ]]; then
    log "positive_events=true, positive_scope=${POSITIVE_SCOPE}, positive_event_limit=${POSITIVE_EVENT_LIMIT}, positive_limit=${POSITIVE_LIMIT}"
  fi
  if [[ "$REQUEUE_UNRESOLVED" == true ]]; then
    log "requeue_unresolved=true, requeue_limit=${REQUEUE_LIMIT}, track_scope=${REQUEUE_TRACK_SCOPE:-all}, last_error=${REQUEUE_LAST_ERROR:-all}"
  fi
}

main() {
  parse_args "$@"
  validate_config
  print_config

  if [[ "$DRY_RUN" == true ]]; then
    log "dry-run: no API requests will be sent"
    api_get "/actuator/health" >/dev/null
    api_get "/api/v1/recommendations/admin/feature-coverage" \
      "user_id=${ADMIN_USER_ID}" \
      "target_user_id=${TARGET_USER_ID}" >/dev/null
    api_get "/api/v1/recommendations/admin/audio-taste/profile" \
      "user_id=${ADMIN_USER_ID}" \
      "target_user_id=${TARGET_USER_ID}" \
      "event_limit=500" >/dev/null
    api_post "/api/v1/recommendations/admin/audio-feature-completion/enqueue" \
      "user_id=${ADMIN_USER_ID}" \
      "target_user_id=${TARGET_USER_ID}" \
      "scope=${SCOPE}" \
      "pms_limit=${PMS_LIMIT}" \
      "ems_limit=${EMS_LIMIT}" >/dev/null
    if [[ "$POSITIVE_EVENTS" == true ]]; then
      api_post "/api/v1/recommendations/admin/audio-feature-completion/enqueue-positive-events" \
        "user_id=${ADMIN_USER_ID}" \
        "target_user_id=${TARGET_USER_ID}" \
        "track_scope=${POSITIVE_SCOPE}" \
        "event_limit=${POSITIVE_EVENT_LIMIT}" \
        "limit=${POSITIVE_LIMIT}" >/dev/null
    fi
    if [[ "$REQUEUE_UNRESOLVED" == true ]]; then
      api_post "/api/v1/recommendations/admin/audio-feature-completion/requeue-unresolved" \
        "user_id=${ADMIN_USER_ID}" \
        "target_user_id=${TARGET_USER_ID}" \
        "track_scope=${REQUEUE_TRACK_SCOPE}" \
        "last_error=${REQUEUE_LAST_ERROR}" \
        "limit=${REQUEUE_LIMIT}" >/dev/null
    fi
    api_post "/api/v1/recommendations/admin/audio-feature-completion/process" \
      "user_id=${ADMIN_USER_ID}" \
      "worker_id=${WORKER_ID}" \
      "limit=${PROCESS_LIMIT}" >/dev/null
    return
  fi

  log "checking API health"
  api_get "/actuator/health" >/dev/null

  log "before snapshot"
  coverage_before="$(api_get "/api/v1/recommendations/admin/feature-coverage" \
    "user_id=${ADMIN_USER_ID}" \
    "target_user_id=${TARGET_USER_ID}")"
  print_coverage_summary "$coverage_before"

  profile_before="$(api_get "/api/v1/recommendations/admin/audio-taste/profile" \
    "user_id=${ADMIN_USER_ID}" \
    "target_user_id=${TARGET_USER_ID}" \
    "event_limit=500")"
  print_profile_summary "$profile_before"

  log "enqueue missing audio features"
  enqueue_response="$(api_post "/api/v1/recommendations/admin/audio-feature-completion/enqueue" \
    "user_id=${ADMIN_USER_ID}" \
    "target_user_id=${TARGET_USER_ID}" \
    "scope=${SCOPE}" \
    "pms_limit=${PMS_LIMIT}" \
    "ems_limit=${EMS_LIMIT}")"
  print_enqueue_summary "$enqueue_response"

  if [[ "$POSITIVE_EVENTS" == true ]]; then
    log "enqueue positive-event tracks"
    positive_enqueue_response="$(api_post "/api/v1/recommendations/admin/audio-feature-completion/enqueue-positive-events" \
      "user_id=${ADMIN_USER_ID}" \
      "target_user_id=${TARGET_USER_ID}" \
      "track_scope=${POSITIVE_SCOPE}" \
      "event_limit=${POSITIVE_EVENT_LIMIT}" \
      "limit=${POSITIVE_LIMIT}")"
    print_positive_enqueue_summary "$positive_enqueue_response"
  fi

  if [[ "$REQUEUE_UNRESOLVED" == true ]]; then
    log "requeue unresolved jobs"
    requeue_response="$(api_post "/api/v1/recommendations/admin/audio-feature-completion/requeue-unresolved" \
      "user_id=${ADMIN_USER_ID}" \
      "target_user_id=${TARGET_USER_ID}" \
      "track_scope=${REQUEUE_TRACK_SCOPE}" \
      "last_error=${REQUEUE_LAST_ERROR}" \
      "limit=${REQUEUE_LIMIT}")"
    print_requeue_summary "$requeue_response"
  fi

  local round response claimed
  for ((round = 1; round <= ROUNDS; round++)); do
    response="$(api_post "/api/v1/recommendations/admin/audio-feature-completion/process" \
      "user_id=${ADMIN_USER_ID}" \
      "worker_id=${WORKER_ID}" \
      "limit=${PROCESS_LIMIT}")"
    print_process_summary "$round" "$response"
    claimed="$(printf '%s' "$response" | json_get "claimed_job_count")"
    if [[ "${claimed:-0}" == "0" ]]; then
      log "no claimable jobs remain; stopping early"
      break
    fi
    if (( round < ROUNDS )); then
      sleep "$SLEEP_SECONDS"
    fi
  done

  log "after snapshot"
  coverage_after="$(api_get "/api/v1/recommendations/admin/feature-coverage" \
    "user_id=${ADMIN_USER_ID}" \
    "target_user_id=${TARGET_USER_ID}")"
  print_coverage_summary "$coverage_after"

  profile_after="$(api_get "/api/v1/recommendations/admin/audio-taste/profile" \
    "user_id=${ADMIN_USER_ID}" \
    "target_user_id=${TARGET_USER_ID}" \
    "event_limit=500")"
  print_profile_summary "$profile_after"
}

main "$@"
