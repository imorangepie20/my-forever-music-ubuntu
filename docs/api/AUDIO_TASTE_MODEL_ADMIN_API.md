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
- `profile.positive_track_count`: positive event 기반 feature-ready row 수
- `profile.negative_track_count`: negative event 기반 feature-ready row 수
- `profile.positive_centroid`: 선호 오디오 특성 centroid
- `profile.negative_centroid`: 회피/부정 오디오 특성 centroid
- `profile.coverage`: PMS library feature coverage 요약
- `profile.warnings`: insufficient data 또는 coverage gate 안내

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
- `rows`: PMS user library의 audio feature row 목록

Row fields:

- `track_scope`: 현재 `pms_user_track`
- `track_id`, `title`, `artist_name`, `source_platform`
- `audio_feature_source`
- `audio_features_filled`
- `feature_weight`
- `feature_tier`: `provider`, `llm_accepted`, `llm_weak`, `lastfm_partial`, `missing`
- `acousticness`, `danceability`, `energy`, `instrumentalness`, `liveness`, `speechiness`, `tempo`, `valence`

## Serving

`POST /api/v1/gms/recommendations/preview`는 다음 조건을 만족할 때 audio taste boost를 적용합니다.

- target user의 profile이 `audio_taste_applicable=true`
- positive feature-ready row가 10개 이상
- PMS library feature coverage가 0.30 이상
- 후보 track에 사용 가능한 audio feature가 있음

적용 시:

- PMS/SASRec ranking 뒤에 작은 audio boost를 계산합니다.
- feature coverage와 candidate feature weight가 낮으면 boost weight를 자동 축소합니다.
- response `warnings`에 `Audio taste ranking adjusted ... via audio-taste:v1` 문구를 남깁니다.
- response `context.engine`에 `+audio-taste:v1`을 추가합니다.
- candidate reason에 audio taste explanation token을 덧붙입니다.

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
- dataset fingerprint, hybrid weight audit field, offline metric promotion gate는 다음 단계입니다.
- audio feature completion이 아직 못 채운 track은 boost 대상에서 제외됩니다.
