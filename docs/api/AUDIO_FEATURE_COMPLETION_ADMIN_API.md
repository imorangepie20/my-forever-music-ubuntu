# Audio Feature Completion Admin API

작성일: `2026-05-20`

이 문서는 PMS/EMS 트랙의 누락 오디오 특성을 completion queue에 넣고, 최근 job 상태를 조회/처리하는 관리자 API 계약입니다.

이 API는 `AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md`의 Phase 2-4 구현입니다. 현재 범위는 큐 적재, 조회, ReccoBeats 기반 1차 worker 처리, opt-in scheduler 실행, Last.fm tag evidence 기반 partial inference, Search + LLM 기반 `llm_search_inferred` completion입니다.

## 1. Enqueue missing audio features

```http
POST /api/v1/recommendations/admin/audio-feature-completion/enqueue
```

Query parameters:

| Name | Required | Default | Description |
| --- | --- | --- | --- |
| `user_id` | yes | - | 관리자 사용자 ID |
| `target_user_id` | no | `user_id` | PMS library를 스캔할 사용자 ID |
| `scope` | no | `all` | `all`, `pms`, `ems` 중 하나 |
| `pms_limit` | no | `100` | 이번 요청에서 스캔할 PMS track 최대 수 |
| `ems_limit` | no | `100` | 이번 요청에서 스캔할 EMS missing track 최대 수 |

관리자 권한:

- 현재 1차 구현은 `jowoosungtidal@gmail.com` 계정만 허용합니다.
- 권한이 없으면 `403 FORBIDDEN`을 반환합니다.

Response:

```json
{
  "target_user_id": "target-user",
  "scope": "all",
  "scanned_track_count": 5,
  "enqueued_job_count": 4,
  "skipped_existing_job_count": 1,
  "jobs": [
    {
      "job_id": 7,
      "track_scope": "pms_user_track",
      "track_id": "pms-track-spotify-001",
      "user_id": "target-user",
      "priority": 100,
      "status": "queued",
      "requested_reason": "pms_import",
      "attempt_count": 0,
      "next_retry_at": null,
      "locked_at": null,
      "locked_by": null,
      "last_error": null,
      "created_at": "2026-05-20T00:00:00Z",
      "updated_at": "2026-05-20T00:00:00Z"
    }
  ]
}
```

## 2. Automatic enqueue

사용자/운영자 수동 요청 없이도 아래 저장 흐름은 completion job을 자동 생성합니다.

| Trigger | Scope | Reason | Rule |
| --- | --- | --- | --- |
| PMS playlist import 후 user library sync 완료 | `pms_user_track` | `pms_import` | `audio_features_filled=false` 또는 incomplete audio feature |
| EMS collected track 저장/갱신 후 | `ems_collected_track` | `ems_collect` | `audio_features_filled=false` 또는 missing audio feature |

자동 enqueue도 동일하게 `track_scope + track_id + requested_reason` 유니크 키를 사용하므로 같은 트랙은 중복 적재되지 않습니다.

## 3. List recent jobs

```http
GET /api/v1/recommendations/admin/audio-feature-completion/jobs
```

Query parameters:

| Name | Required | Default | Description |
| --- | --- | --- | --- |
| `user_id` | yes | - | 관리자 사용자 ID |
| `status` | no | all | `queued`, `running`, `completed`, `retry_wait`, `failed`, `unresolved` 등 |
| `limit` | no | `50` | 최대 조회 수 |

Response:

```json
{
  "jobs": [
    {
      "job_id": 7,
      "track_scope": "pms_user_track",
      "track_id": "pms-track-spotify-001",
      "user_id": "target-user",
      "priority": 100,
      "status": "queued",
      "requested_reason": "pms_import",
      "attempt_count": 0,
      "next_retry_at": null,
      "locked_at": null,
      "locked_by": null,
      "last_error": null,
      "created_at": "2026-05-20T00:00:00Z",
      "updated_at": "2026-05-20T00:00:00Z"
    }
  ]
}
```

## 4. Process queued jobs

```http
POST /api/v1/recommendations/admin/audio-feature-completion/process
```

Query parameters:

| Name | Required | Default | Description |
| --- | --- | --- | --- |
| `user_id` | yes | - | 관리자 사용자 ID |
| `worker_id` | no | `manual-worker` | 수동/스케줄 worker 식별자 |
| `limit` | no | `20` | 이번 요청에서 claim하고 처리할 최대 job 수 |

처리 규칙:

- `queued` job과 `next_retry_at`이 지난 `retry_wait` job을 priority 순으로 claim합니다.
- PMS Spotify track은 Spotify track id로 ReccoBeats audio features를 조회합니다.
- TIDAL/외부 track은 ISRC, title, artist, duration 후보 매칭으로 ReccoBeats track을 찾습니다.
- 성공하면 PMS/EMS audio feature snapshot을 `reccobeats_lookup` 또는 `reccobeats_isrc_match`로 저장하고 job을 `completed`로 바꿉니다.
- ReccoBeats 호출 실패는 `retry_wait`로 넘기고 `next_retry_at`을 설정합니다.
- ReccoBeats 매칭이 없고 Last.fm track/artist tag evidence가 confidence gate를 통과하면 `lastfm_track_tag_inferred` 또는 `lastfm_artist_tag_inferred` partial snapshot을 track에 저장하고 `track_audio_feature_evidence`에 tag/result evidence를 남깁니다.
- Last.fm 값은 측정값이 아니므로 `audio_features_filled=false`를 유지하고 job은 `unresolved` + `lastfm_tag_inferred_partial_audio_features`로 남깁니다.
- ReccoBeats/Last.fm 모두 실패하면 Spring worker가 `services/ai`의 `POST /v1/audio-features/infer`를 호출해 OpenAI web search + structured output 기반 estimate를 요청합니다.
- LLM/search 응답이 evidence non-empty, confidence `0.68` 이상, 필수 numeric feature 완비이면 PMS/EMS audio feature snapshot을 `llm_search_inferred`로 저장하고 job을 `completed`로 바꿉니다.
- LLM/search 응답이 confidence `0.50` 이상 `0.68` 미만이고 필수 numeric feature가 완비되어 있으면 snapshot을 `llm_search_low_confidence`로 저장하되 `audio_features_filled=false`를 유지하고 job은 `unresolved` + `llm_search_low_confidence`로 남깁니다.
- LLM/search 응답의 confidence가 `0.50` 미만이면 snapshot은 변경하지 않고 job을 `unresolved` + `llm_search_rejected_low_confidence`로 남깁니다.
- LLM/search evidence가 비어 있으면 snapshot은 변경하지 않고 job을 `unresolved` + `llm_search_no_evidence`로 남깁니다.
- LLM/search numeric feature가 일부 비어 있으면 snapshot은 변경하지 않고 job을 `unresolved` + `llm_search_partial_audio_features`로 남깁니다.
- `requested_reason=manual_llm_retry` job은 ReccoBeats를 다시 호출하지 않고 Last.fm tag inference와 LLM/search inference만 재시도합니다.

Response:

```json
{
  "claimed_job_count": 3,
  "completed_job_count": 2,
  "retry_wait_job_count": 1,
  "unresolved_job_count": 0,
  "failed_job_count": 0
}
```

운영 해석:

- `claimed_job_count > 0`인데 `completed_job_count=0`이면 이번 batch에서 claim한 job이 모두 `unresolved`, `retry_wait`, `failed` 중 하나로 끝났다는 뜻입니다. 즉 worker는 돌았지만 완료 가능한 feature snapshot이 없었던 상태입니다.
- 반복 실행 후 `claimed_job_count=0`, `completed_job_count=0`으로 바로 끝나면 현재 처리 가능한 `queued` 또는 retry 시간이 지난 `retry_wait` job이 없다는 뜻입니다.
- 기존 `unresolved` job은 자동으로 다시 `queued`가 되지 않습니다. 아래 `requeue-unresolved` endpoint로 새 `manual_llm_retry` job을 만들어야 합니다.

## 5. Requeue unresolved jobs

```http
POST /api/v1/recommendations/admin/audio-feature-completion/requeue-unresolved
```

기존 `unresolved` job을 삭제하거나 상태 변경하지 않고, 같은 track에 대해 새 `manual_llm_retry` job을 멱등하게 생성합니다. 이 job은 ReccoBeats no-match를 반복하지 않고 Last.fm/LLM 검색 추론 경로만 다시 탑니다.

Query parameters:

| Name | Required | Default | Description |
| --- | --- | --- | --- |
| `user_id` | yes | - | 관리자 사용자 ID |
| `target_user_id` | no | all users | 특정 PMS 사용자 job만 재큐잉할 때 사용 |
| `track_scope` | no | all | `pms_user_track`, `ems_collected_track` 중 하나. 생략하면 전체 |
| `last_error` | no | all | 특정 unresolved reason만 exact match로 재큐잉 |
| `retry_reason` | no | `manual_llm_retry` | 현재 허용값은 `manual_llm_retry` |
| `limit` | no | `50` | 새로 만들 `manual_llm_retry` job 최대 수 |

Response:

```json
{
  "target_user_id": "target-user",
  "track_scope": "pms_user_track",
  "last_error": "reccobeats_no_match",
  "retry_reason": "manual_llm_retry",
  "scanned_job_count": 10,
  "requeued_job_count": 7,
  "skipped_existing_job_count": 3,
  "jobs": [
    {
      "job_id": 42,
      "track_scope": "pms_user_track",
      "track_id": "pms-track-spotify-001",
      "user_id": "target-user",
      "priority": 110,
      "status": "queued",
      "requested_reason": "manual_llm_retry",
      "attempt_count": 0,
      "next_retry_at": null,
      "locked_at": null,
      "locked_by": null,
      "last_error": null,
      "created_at": "2026-05-21T00:00:00Z",
      "updated_at": "2026-05-21T00:00:00Z"
    }
  ]
}
```

운영 규칙:

- 이미 complete 된 PMS/EMS track은 재큐잉하지 않습니다.
- 같은 track에 기존 `manual_llm_retry` job이 있으면 `skipped_existing_job_count`로 집계합니다.
- `limit`은 생성할 새 job 수 기준이며, 이미 complete 되었거나 중복된 unresolved row는 scan count에만 포함될 수 있습니다.
- `last_error=reccobeats_no_match` 또는 `last_error=manual_llm_retry_no_inference_result`처럼 좁혀서 운영하면 API 비용을 더 예측하기 쉽습니다.

## 5.5. Enqueue positive-event PMS/EMS tracks

```http
POST /api/v1/recommendations/admin/audio-feature-completion/enqueue-positive-events
```

Audio Taste 적용 gate를 빠르게 넘기기 위한 운영 endpoint입니다. 최근 `user_music_event` 중 positive weight를 가진 PMS/EMS track을 우선 스캔하고, 아직 complete audio feature가 없는 트랙만 `positive_audio_taste_retry` job으로 큐잉합니다. Worker는 이 reason을 ReccoBeats 반복 조회 없이 Last.fm/LLM inference 경로로 처리합니다.

EMS playback event는 `track_id` 또는 `item_id`가 `ems-track:{ems_collected_track.id}` 형태일 때 `ems_collected_track` job으로 변환됩니다.

Query parameters:

| Name | Required | Default | Description |
| --- | --- | --- | --- |
| `user_id` | yes | - | 관리자 사용자 ID |
| `target_user_id` | no | `user_id` | positive event를 스캔할 사용자 ID |
| `track_scope` | no | `all` | `all`, `pms`, `ems` 중 하나. 기존 4-argument service 호출은 호환성을 위해 PMS 전용으로 유지 |
| `event_limit` | no | `500` | 최근 user music event 스캔 수 |
| `limit` | no | `20` | 새로 만들 `positive_audio_taste_retry` job 최대 수 |

Response는 `enqueue`와 동일한 shape이며 `scope`는 `track_scope`에 따라 `all_positive_events`, `pms_positive_events`, `ems_positive_events` 중 하나입니다.

운영 예시:

```bash
./infra/scripts/run-audio-feature-completion-backfill.sh \
  --admin-user user-... \
  --target-user user-... \
  --positive-events \
  --positive-scope ems \
  --positive-limit 10 \
  --process-limit 5 \
  --rounds 2
```

## 6. Scheduled processing

`AudioFeatureCompletionScheduler`는 `process` endpoint와 같은 worker를 주기적으로 실행합니다.

기본값은 운영 사고를 피하기 위해 disabled입니다.

환경 변수:

| Env | Default | Description |
| --- | --- | --- |
| `AUDIO_FEATURES_COMPLETION_SCHEDULER_ENABLED` | `false` | scheduler 활성화 여부 |
| `AUDIO_FEATURES_COMPLETION_SCHEDULER_FIXED_DELAY_MS` | `300000` | tick 간격 |
| `AUDIO_FEATURES_COMPLETION_SCHEDULER_INITIAL_DELAY_MS` | `60000` | 서버 시작 후 첫 tick 지연 |
| `AUDIO_FEATURES_COMPLETION_SCHEDULER_BATCH_LIMIT` | `20` | tick당 claim할 job 수 |
| `AUDIO_FEATURES_COMPLETION_SCHEDULER_WORKER_ID` | `audio-feature-completion-scheduler` | job lock에 남길 worker 식별자 |
| `AUDIO_FEATURES_COMPLETION_LASTFM_MIN_CONFIDENCE` | `0.55` | Last.fm tag inference 저장 최소 confidence |
| `AI_AUDIO_FEATURE_INFERENCE_PATH` | `/v1/audio-features/infer` | Spring API가 호출할 AI audio feature inference path |
| `AI_AUDIO_FEATURE_INFERENCE_MODEL` | `gpt-5-mini` | `services/ai` Search + LLM audio feature inference 모델 |
| `AI_AUDIO_FEATURE_INFERENCE_MIN_CONFIDENCE` | `0.68` | `services/ai`가 `status=ok`으로 반환할 최소 confidence |
| `AI_AUDIO_FEATURE_INFERENCE_REVIEW_MIN_CONFIDENCE` | `0.50` | Spring worker가 low-confidence estimate를 snapshot/evidence로 보존할 최소 confidence |

운영 상태는 `GET /api/v1/system/admin/schedules?user_id={adminUserId}`의 `audio-feature-completion` 항목에서도 확인할 수 있습니다.

## 7. Feature coverage summary

`GET /api/v1/recommendations/admin/feature-coverage?user_id={adminUserId}&target_user_id={targetUserId}` 응답은 completion queue 운영 상태를 함께 반환합니다.

Response fragment:

```json
{
  "audio_feature_completion": {
    "recent_job_count": 5,
    "status_counts": [
      {
        "status": "queued",
        "job_count": 1
      },
      {
        "status": "retry_wait",
        "job_count": 1
      },
      {
        "status": "unresolved",
        "job_count": 2
      },
      {
        "status": "completed",
        "job_count": 1
      }
    ],
    "top_reasons": [
      {
        "reason": "lastfm_tag_inferred_partial_audio_features",
        "job_count": 2
      },
      {
        "reason": "llm_search_low_confidence",
        "job_count": 1
      },
      {
        "reason": "ReccoBeats API request failed (429)",
        "job_count": 1
      }
    ],
    "warnings": []
  }
}
```

집계 규칙:

- 최근 job 최대 500개를 기준으로 status count를 계산합니다.
- `top_reasons`는 `unresolved`, `retry_wait`, `failed` job의 `last_error`를 상위 10개까지 반환합니다.
- completion job store가 없는 profile은 `warnings`에 경계 부재를 노출하고 전체 feature coverage status를 `degraded`로 둡니다.
- `/recommendations/feature-coverage` 화면은 이 블록을 Audio Completion 요약 패널과 status/reason 표로 표시합니다.

## 8. Job identity

중복 큐잉 방지를 위해 아래 조합은 유니크합니다.

```text
track_scope + track_id + requested_reason
```

현재 1차 enqueue 규칙:

| Scope | Track source | Requested reason | Priority |
| --- | --- | --- | --- |
| `pms_user_track` | PMS user library track | `pms_import` | `100` |
| `ems_collected_track` | EMS collected track | `ems_collect` | `60` |
| `pms_user_track` | unresolved PMS user library track | `manual_llm_retry` | `110+` |
| `ems_collected_track` | unresolved EMS collected track | `manual_llm_retry` | `110+` |

## 9. Storage

DB migration:

- `V44__create_audio_feature_completion_job.sql`
- `V45__create_track_audio_feature_evidence.sql`

Tables:

- `audio_feature_completion_job`
- `track_audio_feature_evidence`

Local profile:

- `InMemoryAudioFeatureCompletionJobStore`

Non-local profile:

- `JpaAudioFeatureCompletionJobStore`

## 10. 다음 연결 지점

1. unresolved reason별 운영 화면 action을 더 세밀하게 연결합니다.
2. Audio Taste 모델 promotion 전, weak/inferred feature 비율별 ranking metric을 검증합니다.
