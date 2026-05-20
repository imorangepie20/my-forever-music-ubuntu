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
- LLM/search 응답이 `status=ok`, evidence non-empty, confidence gate 통과, 필수 numeric feature 완비이면 PMS/EMS audio feature snapshot을 `llm_search_inferred`로 저장하고 job을 `completed`로 바꿉니다.
- LLM/search도 실패하거나 confidence/evidence gate를 통과하지 못하면 `unresolved`로 남깁니다.

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

## 5. Scheduled processing

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

운영 상태는 `GET /api/v1/system/admin/schedules?user_id={adminUserId}`의 `audio-feature-completion` 항목에서도 확인할 수 있습니다.

## 6. Feature coverage summary

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

## 7. Job identity

중복 큐잉 방지를 위해 아래 조합은 유니크합니다.

```text
track_scope + track_id + requested_reason
```

현재 1차 enqueue 규칙:

| Scope | Track source | Requested reason | Priority |
| --- | --- | --- | --- |
| `pms_user_track` | PMS user library track | `pms_import` | `100` |
| `ems_collected_track` | EMS collected track | `ems_collect` | `60` |

## 8. Storage

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

## 9. 다음 연결 지점

1. Last.fm partial inference를 source class/confidence-aware 모델 feature store에 연결합니다.
2. 실패/모호한 track은 LLM/search inference 단계로 넘깁니다.
