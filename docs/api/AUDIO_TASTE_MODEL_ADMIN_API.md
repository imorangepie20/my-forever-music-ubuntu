# Audio Taste Model Admin API

작성일: `2026-05-21`

Audio Taste Model v1은 PMS user library, user music event, audio feature completion snapshot을 사용해 사용자 오디오 취향 centroid를 on-demand로 계산합니다.

v1의 serving 모델은 deep model artifact가 아니라 Spring API 내부의 설명 가능한 `AudioCentroidBaseline`입니다. 충분한 feature-ready 행동 데이터가 있을 때만 GMS preview ranking에 `audio-taste:v1` boost를 보수적으로 적용합니다.

## Endpoints

### Get profile

```http
GET /api/v1/recommendations/admin/audio-taste/profile?user_id={adminUserId}&target_user_id={targetUserId}&event_limit=500
```

Query parameters:

| Name | Required | Default | Description |
| --- | --- | --- | --- |
| `user_id` | yes | - | 요청 사용자 ID |
| `target_user_id` | no | `user_id` | profile을 계산할 사용자 ID |
| `event_limit` | no | `500` | 최근 user music event 조회 한도. 서버에서 1-2000 사이로 clamp 합니다. |

Response fields:

- `status`: `ok` 또는 `insufficient_data`
- `profile.audio_taste_applicable`: GMS preview boost 적용 가능 여부
- `profile.profile_type`: `none`, `weak`, `ready`, `strong`, `heavy`
- `profile.profile_confidence`: 0.0-1.0 confidence for applying audio taste signals
- `profile.profile_focus`: `balanced`, `artist_narrow`, `source_narrow`, `low_quality`
- `profile.diversity`: artist/source diversity summary
- `profile.source_quality_mix`: provider/LLM/Last.fm/missing tier ratio
- `profile.taste_modes`: `heavy` profile에서만 채워지는 inspection-only 청취 모드 요약. non-heavy profile은 빈 배열입니다.
- `profile.positive_track_count`: positive event 기반 feature-ready row 수
- `profile.negative_track_count`: negative event 기반 feature-ready row 수
- `profile.positive_centroid`: 선호 오디오 특성 centroid
- `profile.negative_centroid`: 회피/부정 오디오 특성 centroid
- `profile.coverage`: PMS library와 최근 positive EMS playback 참조 row의 feature coverage 요약
- `profile.warnings`: insufficient data 또는 coverage gate 안내

### EMS playback events

`/gms-playlists` playback events with `track_id=ems-track:<id>` can contribute to the audio taste profile when the referenced `ems_collected_track` row has usable audio features. The audio taste dataset remains user-scoped: it includes PMS library rows plus EMS rows referenced by recent positive user events, not the full EMS acquisition pool.

Response example:

```json
{
  "service": "api",
  "status": "ok",
  "generated_at": "2026-05-21T00:00:00Z",
  "profile": {
    "user_id": "target-user",
    "status": "ok",
    "audio_taste_applicable": true,
    "profile_type": "heavy",
    "profile_focus": "balanced",
    "profile_confidence": 0.62,
    "diversity": {
      "distinct_artist_count": 8,
      "dominant_artist_name": "Artist A",
      "dominant_artist_share": 0.18,
      "distinct_source_platform_count": 2
    },
    "source_quality_mix": {
      "provider": 0.72,
      "llm_accepted": 0.18,
      "llm_weak": 0.04,
      "lastfm_partial": 0.06,
      "missing": 0.0
    },
    "taste_modes": [
      {
        "mode_id": "mode-1",
        "label": "high_energy_bright_danceable",
        "track_count": 78,
        "confidence": 0.74,
        "centroid": {
          "acousticness": 0.18,
          "danceability": 0.76,
          "energy": 0.81,
          "instrumentalness": 0.03,
          "liveness": 0.13,
          "speechiness": 0.06,
          "tempo": 0.58,
          "valence": 0.72
        },
        "top_artists": [
          { "artist_name": "Artist A", "track_count": 12 }
        ],
        "representative_tracks": [
          {
            "track_id": "pms-track-001",
            "title": "Track Title",
            "artist_name": "Artist A",
            "source_platform": "tidal",
            "distance_to_centroid": 0.08
          }
        ]
      }
    ],
    "positive_track_count": 12,
    "negative_track_count": 1,
    "event_limit": 500,
    "positive_centroid": {
      "acousticness": 0.21,
      "danceability": 0.74,
      "energy": 0.68,
      "instrumentalness": 0.04,
      "liveness": 0.12,
      "speechiness": 0.05,
      "tempo": 0.46,
      "valence": 0.71
    },
    "negative_centroid": {
      "acousticness": 0.5,
      "danceability": 0.5,
      "energy": 0.5,
      "instrumentalness": 0.5,
      "liveness": 0.5,
      "speechiness": 0.5,
      "tempo": 0.5,
      "valence": 0.5
    },
    "coverage": {
      "track_count": 40,
      "usable_track_count": 28,
      "feature_ready_ratio": 0.7,
      "inferred_track_count": 3,
      "weak_track_count": 1
    },
    "warnings": [],
    "recomputed_at": "2026-05-21T00:00:00Z"
  }
}
```

### Recompute profile

```http
POST /api/v1/recommendations/admin/audio-taste/recompute?user_id={adminUserId}&target_user_id={targetUserId}&event_limit=500
```

v1은 별도 profile table을 만들지 않고 매 요청마다 PMS library와 user event에서 on-demand로 재계산합니다. 응답 구조는 `GET /profile`과 같습니다.

### Dataset

```http
GET /api/v1/recommendations/admin/audio-taste/dataset?user_id={adminUserId}&target_user_id={targetUserId}&event_limit=500
```

Response fields:

- `dataset_version`: 현재 `audio-taste-dataset-v1`
- `user_id`: dataset 대상 사용자 ID
- `profile`: profile summary
- `rows`: PMS user library와 최근 positive EMS playback 참조 audio feature row 목록

Row fields:

- `track_scope`: `pms_user_track` 또는 `ems_collected_track`
- `track_id`, `title`, `artist_name`, `source_platform`
- `audio_feature_source`
- `audio_features_filled`
- `feature_weight`
- `feature_tier`: `provider`, `llm_accepted`, `llm_weak`, `lastfm_partial`, `missing`
- `acousticness`, `danceability`, `energy`, `instrumentalness`, `liveness`, `speechiness`, `tempo`, `valence`

## Serving

`POST /api/v1/gms/recommendations/preview`는 다음 조건을 만족할 때 audio taste boost를 적용합니다.

- target user의 profile이 `audio_taste_applicable=true`
- positive feature-ready row가 readiness gate 이상
- PMS library feature coverage가 readiness gate 이상
- 후보 track에 사용 가능한 audio feature가 있음

적용 시:

- PMS/SASRec ranking 뒤에 작은 audio boost를 계산합니다.
- feature coverage와 candidate feature weight가 낮으면 boost weight를 자동 축소합니다.
- response `warnings`에 `Audio taste ranking adjusted ... via audio-taste:v1` 문구를 남깁니다.
- response `context.engine`에 `+audio-taste:v1`을 추가합니다.
- candidate reason에 audio taste explanation token을 덧붙입니다.

`heavy` profile에서는 `include_explanations=true` 요청에 한해 GMS preview item마다 inspection-only `taste_mode_affinity`를 함께 내려줍니다. 이 값은 nearest `profile.taste_modes[]`를 후보 audio feature와 비교해 계산한 진단 필드입니다.

```json
{
  "items": [
    {
      "track_id": "pms-track-001",
      "taste_mode_affinity": {
        "applied": true,
        "mode_id": "mode-1",
        "label": "high_energy_bright_danceable",
        "similarity": 0.9321,
        "distance": 0.0679,
        "tokens": ["mode_energy_match", "mode_valence_match"]
      }
    }
  ]
}
```

`taste_mode_affinity` rules:

- `include_explanations=false`이면 `null`
- profile이 `heavy`가 아니거나 `taste_modes`가 비어 있으면 `null`
- 후보 audio feature가 없거나 usable하지 않으면 `null`
- GMS ranking score, item order, `context.engine`, audit model version에는 영향을 주지 않음

### Taste mode affinity operational gate

`include_explanations=true` 요청에서는 `taste_mode_affinity`가 붙은 heavy profile 후보에 `items[].taste_mode_gate`가 함께 내려올 수 있습니다.

`taste_mode_gate`는 operational gate 신호입니다. 기본값에서는 dry-run으로 동작하며 `rank`, `score`, item order를 바꾸지 않고, 적용했다면 어느 정도 점수가 움직였을지만 보여줍니다.

```json
{
  "taste_mode_gate": {
    "status": "dry_run",
    "reason": "eligible",
    "reason_tokens": ["eligible", "mode_energy_match"],
    "suggested_boost_weight": 0.018,
    "dry_run_score": 0.9184,
    "dry_run_delta": 0.0084
  }
}
```

Statuses:

| Status | Meaning |
| --- | --- |
| `not_applicable` | gate가 이 item을 평가할 수 없음 |
| `blocked` | affinity는 있지만 운영 threshold가 거부함 |
| `eligible` | threshold는 통과했지만 dry-run score를 계산하지 않음 |
| `dry_run` | threshold를 통과했고 hypothetical score delta를 계산함 |

Preview audit log는 request 단위 `taste_mode_gate_summary` payload를 저장합니다. 이 값에는 evaluated, eligible, dry-run, blocked, not-applicable count와 reason count totals가 포함됩니다.

When `app.recommendation.taste-mode-affinity.apply-ranking-boost=true`, eligible `dry_run` or `eligible` gate results become a conservative ranking signal.

- The default remains `false`, so production starts in dry-run mode.
- The boost runs after `audio-taste:v1` and uses the same formula shown by dry-run.
- `context.engine` appends `+taste-mode-affinity:v1` only when at least one candidate is actually boosted.
- `warnings[]` uses `ranking_impact=enabled` and includes applied, rank-changed, blocked, max-positive-delta, and max-negative-delta counts.
- `taste_mode_gate_summary` includes `boost_applied_count` and `rank_changed_count`.

### Ubuntu rollout profile

The default gate remains conservative. On 2026-05-22, the Ubuntu server dataset was verified with a sparse heavy profile using this rollout profile:

```env
AUDIO_TASTE_MIN_POSITIVE_READY_TRACKS=10
AUDIO_TASTE_MIN_FEATURE_READY_RATIO=0.07
APP_RECOMMENDATION_TASTE_MODE_AFFINITY_APPLY_RANKING_BOOST=true
APP_RECOMMENDATION_TASTE_MODE_AFFINITY_MIN_PROFILE_CONFIDENCE=0.10
APP_RECOMMENDATION_TASTE_MODE_AFFINITY_MAX_BOOST_WEIGHT=0.30
```

Observed verification result for the current user:

- `context.engine=rule-based-preview-v1+audio-taste:v1+taste-mode-affinity:v1`
- `evaluated_count=10`
- `eligible_count=7`
- `blocked_count=3`
- `boost_applied_count=7`
- `rank_changed_count=8`
- `max_positive_delta=0.0129`

Rollback is operational only:

```env
APP_RECOMMENDATION_TASTE_MODE_AFFINITY_APPLY_RANKING_BOOST=false
```

## Readiness gates

기본 운영 gate는 보수적으로 유지합니다.

| Env | Default | Description |
| --- | --- | --- |
| `AUDIO_TASTE_MIN_POSITIVE_READY_TRACKS` | `10` | audio taste profile 적용에 필요한 positive feature-ready track 최소 개수 |
| `AUDIO_TASTE_MIN_FEATURE_READY_RATIO` | `0.30` | PMS library 전체 track 대비 feature-ready track 최소 비율 |

| Type | Feature-ready tracks | Serving |
| --- | ---: | --- |
| `none` | `0-4` | no audio taste boost |
| `weak` | `5-9` | only available when verification gate is lowered; very small boost |
| `ready` | `10-49` | conservative sparse profile boost |
| `strong` | `50-199` | stable centroid profile boost |
| `heavy` | `200+` | multi-mode candidate; cluster expansion follows after v1 confidence rollout |

서버 검증이나 소량 backfill 성능 측정 중에는 일시적으로 gate를 낮출 수 있습니다. 예를 들어 기존 DB에서 10곡만 먼저 채우고 모델 흐름을 확인하려면 `AUDIO_TASTE_MIN_POSITIVE_READY_TRACKS=10`, `AUDIO_TASTE_MIN_FEATURE_READY_RATIO=0.07`처럼 둘 수 있습니다.

주의:

- gate를 낮추는 것은 모델 검증을 빠르게 열기 위한 운영 설정입니다.
- 추론값이 많은 사용자의 추천 영향력은 feature tier weight와 coverage weight로 계속 축소됩니다.
- production default는 `10`과 `0.30`입니다.

## Quality tiers

| Tier | Source rule | Weight | Serving behavior |
| --- | --- | ---: | --- |
| `provider` | ReccoBeats 등 provider snapshot + `audio_features_filled=true` | `1.00` | full signal |
| `llm_accepted` | `llm_search_inferred` + `audio_features_filled=true` | `0.70` | reduced but usable |
| `llm_weak` | `llm_search_low_confidence`, confidence/evidence gate 통과 | `0.35` | weak signal |
| `lastfm_partial` | Last.fm tag evidence 존재 | `0.25` | weak signal |
| `missing` | unresolved/missing | `0.00` | no-op |

## Current limits

- v1은 profile을 영속 저장하지 않습니다.
- v1은 neural/vector artifact를 학습하지 않습니다.
- `taste_modes`와 GMS preview `taste_mode_affinity`는 기본값에서는 inspection-only입니다. `APP_RECOMMENDATION_TASTE_MODE_AFFINITY_APPLY_RANKING_BOOST=true`일 때만 gate 통과 후보가 GMS ranking에 반영됩니다.
- dataset fingerprint, hybrid weight audit field, offline metric promotion gate는 다음 단계입니다.
- audio feature completion이 아직 못 채운 track은 boost 대상에서 제외됩니다.
