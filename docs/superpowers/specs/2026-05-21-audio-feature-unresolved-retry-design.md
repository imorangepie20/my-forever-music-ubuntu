# Audio Feature Unresolved Retry and Taste Gate Design

작성일: `2026-05-21`

## 1. 문제

Audio feature completion을 반복 실행하면 초반에는 `completed_job_count`가 나오지만, 시간이 지나면 `completed_job_count=0`으로 바로 떨어진다. 현재 worker는 `queued`와 만료된 `retry_wait`만 claim하고, `unresolved`가 된 job은 자동 재처리하지 않는다. 따라서 ReccoBeats no-match, evidence 부족, low-confidence inference가 누적되면 coverage가 더 이상 올라가지 않는다.

이 상태에서는 Audio Taste Model v1이 정상 구현되어도 PMS library feature coverage gate(`0.30`)를 통과하지 못해 GMS preview에 `audio-taste:v1` boost가 붙지 않는다.

## 2. 목표

이번 단계의 목표는 두 가지다.

1. `unresolved` completion job을 reason별로 안전하게 다시 queue에 넣어 후속 LLM/search retry를 운영자가 실행할 수 있게 한다.
2. Audio Taste profile gate를 환경변수로 조정 가능하게 만들어, 기본 제품 기준은 유지하면서 서버 검증 환경에서는 낮은 coverage threshold로 `audio-taste:v1` serving을 확인할 수 있게 한다.

## 3. 범위

### 포함

- 관리자 전용 unresolved requeue API
- `last_error`, `track_scope`, `target_user_id`, `limit` 기반 requeue filter
- 재큐잉 reason으로 `manual_llm_retry` 사용
- worker가 `manual_llm_retry` job에서 ReccoBeats 반복 실패를 줄이고 Last.fm/LLM inference를 우선 시도
- Audio Taste gate 환경변수화
  - `AUDIO_TASTE_MIN_POSITIVE_READY_TRACKS`
  - `AUDIO_TASTE_MIN_FEATURE_READY_RATIO`
- API 문서와 운영 문서 업데이트
- unit/WebMvc tests와 targeted API tests

### 제외

- 새 DB 테이블 추가
- LLM inference schema 변경
- provider search/Spotify fallback resolver 신규 개발
- frontend 화면 변경
- neural AudioTasteVectorModel 학습

## 4. 현재 동작 해석

`completed_job_count=0`은 하나의 의미가 아니다.

- `claimed_job_count > 0`, `unresolved_job_count > 0`: claim은 됐지만 남은 곡이 모두 no-match/low-confidence/evidence 부족으로 완료되지 않은 상태다.
- `claimed_job_count=0`: claim 가능한 `queued` 또는 만료된 `retry_wait` job이 없는 상태다. 기존 `unresolved` job은 자동 claim 대상이 아니다.

운영자는 이 둘을 구분해서 봐야 한다. 이번 requeue API는 두 번째 상태에서 `unresolved`를 선별적으로 다시 `queued`로 되돌리는 도구다.

## 5. API 설계

### Requeue unresolved jobs

```http
POST /api/v1/recommendations/admin/audio-feature-completion/requeue-unresolved
```

Query parameters:

| Name | Required | Default | Description |
| --- | --- | --- | --- |
| `user_id` | yes | - | 관리자 사용자 ID. 기존 audio feature completion admin 권한 규칙을 그대로 사용한다. |
| `target_user_id` | no | - | PMS job 대상 사용자 ID. EMS job에는 적용하지 않는다. |
| `track_scope` | no | all | `pms_user_track`, `ems_collected_track`, 또는 all |
| `last_error` | no | all | exact match filter. 예: `reccobeats_no_match` |
| `limit` | no | `50` | 한 번에 requeue할 최대 job 수. 서버에서 1-200 사이로 clamp한다. |
| `retry_reason` | no | `manual_llm_retry` | 새 job의 requested reason. v1은 `manual_llm_retry`만 허용한다. |

Response:

```json
{
  "target_user_id": "user-...",
  "track_scope": "pms_user_track",
  "last_error": "reccobeats_no_match",
  "scanned_job_count": 50,
  "requeued_job_count": 42,
  "skipped_existing_job_count": 8,
  "jobs": [
    {
      "job_id": 1001,
      "track_scope": "pms_user_track",
      "track_id": "pms-track-tidal-332021418",
      "user_id": "user-...",
      "priority": 110,
      "status": "queued",
      "requested_reason": "manual_llm_retry"
    }
  ]
}
```

### Identity rule

기존 unique key는 `track_scope + track_id + requested_reason`이다. 따라서 기존 `pms_import` job을 수정하지 않고, 같은 track에 `manual_llm_retry` job을 새로 만든다. 이 방식은 과거 실패 사유를 보존하면서 후속 retry 이력을 분리한다.

## 6. Worker 설계

기존 기본 job(`pms_import`, `ems_collect`)은 현재 순서를 유지한다.

```text
ReccoBeats -> Last.fm tag inference -> LLM/search inference
```

`requested_reason=manual_llm_retry` job은 아래 순서를 사용한다.

```text
Last.fm tag inference -> LLM/search inference -> unresolved
```

이유:

- 이미 `reccobeats_no_match`로 unresolved 된 track에 같은 ReccoBeats 요청을 반복하는 것은 시간이 오래 걸리고 성공률이 낮다.
- Last.fm partial inference는 weak signal로 보존할 수 있고, LLM/search accepted result는 `audio_features_filled=true`로 coverage를 올릴 수 있다.
- evidence/confidence gate는 그대로 유지하므로 낮은 품질 값을 완료 처리하지 않는다.

결과 처리:

- LLM/search accepted + complete snapshot: `completed`
- LLM/search low-confidence but complete estimate: `unresolved` + `llm_search_low_confidence`, snapshot/evidence는 weak signal로 저장
- Last.fm partial: `unresolved` + `lastfm_tag_inferred_partial_audio_features`, snapshot/evidence는 weak signal로 저장
- evidence 없음 또는 numeric incomplete: `unresolved` + 구체 사유

## 7. Audio Taste Gate 설정

`AudioTasteProfileService`의 hard-coded gate를 설정값으로 분리한다.

| Env | Default | Meaning |
| --- | ---: | --- |
| `AUDIO_TASTE_MIN_POSITIVE_READY_TRACKS` | `10` | positive event 기반 feature-ready track 최소 수 |
| `AUDIO_TASTE_MIN_FEATURE_READY_RATIO` | `0.30` | PMS library usable audio feature coverage 최소 비율 |

기본값은 제품 기준을 그대로 유지한다. 운영 검증 시에만 `AUDIO_TASTE_MIN_FEATURE_READY_RATIO=0.07` 또는 `0.10`처럼 낮춰 GMS preview에서 `audio-taste:v1` 적용 여부를 확인할 수 있다.

Profile response warning은 설정값을 반영해야 한다.

예:

```text
Audio taste feature coverage is below 0.30.
```

설정값이 `0.10`이면 warning도 `0.10`을 표시한다.

## 8. 운영 절차

1. PMS missing feature job을 enqueue한다.
2. `process`를 반복 실행한다.
3. `completed_job_count=0`으로 떨어지면 job status/reason 분포를 확인한다.
4. `reccobeats_no_match`가 많으면 requeue API로 `manual_llm_retry` job을 만든다.
5. `process`를 다시 실행한다.
6. `Audio Taste profile`의 coverage와 warnings를 확인한다.
7. 서버 검증 목적이면 `AUDIO_TASTE_MIN_FEATURE_READY_RATIO`를 낮춰 재시작하고 GMS preview에서 `context.engine`에 `audio-taste:v1`이 붙는지 확인한다.
8. 검증 후 기본값 `0.30`으로 되돌린다.

## 9. 테스트 전략

### Application tests

- `AudioFeatureCompletionServiceTest`
  - unresolved `reccobeats_no_match` PMS job을 `manual_llm_retry`로 requeue한다.
  - 같은 `manual_llm_retry` job이 이미 있으면 duplicate를 만들지 않는다.
  - `target_user_id` filter가 다른 사용자의 PMS job을 제외한다.

- `AudioFeatureCompletionWorkerServiceTest`
  - `manual_llm_retry` PMS job은 ReccoBeats client를 호출하지 않고 LLM/search inference를 시도한다.
  - accepted LLM/search snapshot은 track audio feature를 채우고 job을 completed로 만든다.
  - low-confidence snapshot은 unresolved로 남지만 weak snapshot을 저장한다.

- `AudioTasteProfileServiceTest`
  - default coverage gate는 0.30이다.
  - custom properties로 0.07을 주면 같은 dataset이 applicable이 된다.
  - warning message가 설정값을 반영한다.

### WebMvc tests

- `POST /api/v1/recommendations/admin/audio-feature-completion/requeue-unresolved`
  - snake_case response field 확인
  - admin user id 필수 확인

### Verification

- `./gradlew test --tests AudioFeatureCompletionServiceTest`
- `./gradlew test --tests AudioFeatureCompletionWorkerServiceTest`
- `./gradlew test --tests AudioTasteProfileServiceTest`
- `./gradlew test --tests AudioFeatureCompletionAdminControllerWebMvcTest`
- `./gradlew test`

## 10. 성공 기준

- 운영자가 unresolved reason별로 completion job을 다시 queue에 넣을 수 있다.
- `manual_llm_retry`는 ReccoBeats no-match 반복 시간을 줄이고 LLM/search 경로를 우선 사용한다.
- Audio Taste gate 기본값은 제품 기준 `10`, `0.30`을 유지한다.
- 서버 검증 환경에서는 env 설정만으로 coverage threshold를 낮춰 `audio-taste:v1` 적용 여부를 확인할 수 있다.
- 문서에는 `completed_job_count=0`의 의미와 다음 운영 액션이 명확히 적힌다.
