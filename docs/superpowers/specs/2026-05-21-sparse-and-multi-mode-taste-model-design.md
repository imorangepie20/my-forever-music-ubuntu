# Sparse And Multi-Mode Taste Model Design

작성일: `2026-05-21`

## 1. Purpose

`SparseAndMultiModeTasteModel v1`은 사용자의 음악 취향을 단일 선호 벡터가 아니라, 곡 수와 행동 데이터 양에 따라 신뢰도가 달라지는 여러 청취 모드의 집합으로 모델링한다.

이 모델은 두 종류의 사용자를 같은 구조 안에서 다룬다.

- 적은 곡을 가져온 사용자: 5-30곡만으로도 약하지만 즉시 쓸 수 있는 취향 가설을 만든다.
- 많은 곡을 가져온 사용자: 200곡 이상에서는 하나의 평균 취향이 아니라 여러 청취 모드로 분리해 추천한다.

첫 구현의 목표는 deep model을 바로 학습하는 것이 아니다. 현재 Spring API의 `AudioCentroidBaseline`을 확장해 profile type, confidence, diversity summary, source quality mix를 계산하고, GMS audio taste boost가 이 신뢰도에 따라 보수적으로 동작하게 만드는 것이다.

## 2. Product Principle

대부분의 사용자는 음악을 하나의 취향으로 듣지 않는다.

사용자는 상황과 감정에 따라 여러 방식으로 듣는다.

- 출근, 이동, 운동처럼 에너지가 필요한 모드
- 일하거나 집중할 때 배경으로 깔아두는 모드
- 밤이나 휴식 시간의 낮은 에너지 모드
- 특정 아티스트나 장르에 꽂힌 모드
- 오래 유지되는 안정적 취향
- 최근에 잠깐 이동한 취향
- 좋아하지만 너무 자주 추천되면 질리는 영역

따라서 모델은 “이 사용자는 이런 취향이다” 하나로 끝나면 안 된다. 첫 버전부터 취향의 확실성, 다양성, 좁음, 여러 모드로 확장 가능한 구조를 응답에 담아야 한다.

## 3. Profile Type

모델은 feature-ready track 수를 기준으로 사용자 profile type을 분류한다.

| Type | Feature-ready tracks | Meaning | Serving behavior |
| --- | ---: | --- | --- |
| `none` | `0-4` | 취향 추론 불가 | audio taste boost 미적용 |
| `weak` | `5-9` | 약한 취향 가설 | 아주 약한 boost만 허용 |
| `ready` | `10-49` | 기본 sparse profile | 보수적 boost 적용 |
| `strong` | `50-199` | 안정적 profile | centroid, variance, negative zone까지 사용 가능 |
| `heavy` | `200+` | multi-mode 분석 대상 | cluster 기반 profile로 확장 |

초기 운영 기준은 `10곡 이상`을 실사용 가능한 최소 profile로 본다. 5-9곡은 개인화 느낌을 줄 수는 있지만 confidence가 낮은 weak profile로만 취급한다.

## 4. Narrow Taste Guard

10곡 이상이라고 항상 좋은 audio taste profile은 아니다.

예를 들어 10곡이 모두 한 아티스트이거나, 한 장르와 한 playlist에만 몰려 있으면 이는 일반 오디오 취향이라기보다 artist affinity 또는 특정 문맥 취향에 가깝다.

첫 구현은 아래 summary를 계산한다.

| Field | Meaning |
| --- | --- |
| `distinct_artist_count` | feature-ready track 중 고유 artist 수 |
| `dominant_artist_share` | 가장 많이 등장한 artist의 비율 |
| `distinct_source_platform_count` | feature-ready track의 source platform 다양성 |
| `source_quality_mix` | provider / llm accepted / weak / lastfm / missing 비율 |
| `profile_focus` | `balanced`, `artist_narrow`, `source_narrow`, `low_quality` 중 하나 |

초기 gate:

- `min_distinct_artists = 3`
- `artist_narrow` 조건: `dominant_artist_share >= 0.70`
- `low_quality` 조건: usable feature 중 weak tier 비율이 `0.50` 이상

`artist_narrow` 또는 `low_quality` profile은 `ready` 이상이어도 boost weight를 낮추고 warning을 남긴다.

## 5. Profile Confidence

모델 confidence는 단일 값으로 응답한다. 값은 `0.0-1.0` 범위다.

초기 confidence는 아래 요소를 곱하거나 감쇠한다.

| Factor | Rule |
| --- | --- |
| track count confidence | `none=0`, `weak=0.25`, `ready=0.55`, `strong=0.75`, `heavy=0.90` |
| feature coverage | PMS library feature-ready ratio |
| source quality | provider/accepted inference weight 평균 |
| diversity penalty | artist/source가 좁으면 감쇠 |
| negative signal bonus | negative event가 있으면 회피 영역을 더 잘 알 수 있으므로 소폭 상승 |

초기 공식은 설명 가능해야 한다. deep model score처럼 opaque하게 만들지 않는다.

Example:

```text
profile_confidence =
  type_base_confidence
  * clamp(0.35 + feature_ready_ratio, 0.35, 1.0)
  * source_quality_average
  * diversity_multiplier
  + negative_signal_bonus
```

결과는 0.0-1.0으로 clamp한다.

## 6. Global Taste And Modes

v1은 두 단계로 간다.

### 6-1. First implementation

첫 구현은 아래만 만든다.

- `profile_type`
- `profile_confidence`
- `diversity_summary`
- `source_quality_mix`
- `warnings`
- profile type에 따른 GMS audio boost weight 조절

기존 `positive_centroid`, `negative_centroid`, `coverage`는 유지한다.

### 6-2. Heavy-user expansion

`heavy` profile은 이번 구현에서는 cluster 적용 대상임을 표시하고, 다음 구현 단계에서 cluster를 계산한다.

Cluster 후보 feature:

- audio feature vector: acousticness, danceability, energy, instrumentalness, liveness, speechiness, normalized tempo, valence
- artist and genre/tag tokens
- event weight
- recency
- source quality weight

초기 cluster 수는 고정값이 아니라 track 수에 따라 제한한다.

| Feature-ready tracks | Cluster target |
| ---: | ---: |
| `200-399` | `3-4` |
| `400-799` | `4-6` |
| `800+` | `5-8` |

Cluster는 곧바로 serving 필수 조건으로 만들지 않는다. 먼저 admin dataset/export에 노출하고, 추천 품질을 확인한 뒤 GMS boost에 연결한다.

## 7. Serving Policy

GMS audio taste boost는 profile confidence에 따라 작동한다.

| Type | Max audio boost weight |
| --- | ---: |
| `none` | `0.00` |
| `weak` | `0.03` |
| `ready` | `0.08` |
| `strong` | `0.12` |
| `heavy` | `0.12` until clusters are used |

현재 코드의 audio taste boost는 이미 작은 보정으로 동작한다. 이 설계는 boost를 더 공격적으로 키우는 것이 아니라, 적은 데이터와 낮은 품질 데이터에서 과신하지 않도록 profile confidence로 조절한다.

Serving response는 다음 문맥을 남겨야 한다.

- `context.engine`에 `audio-taste:v1` 유지
- warnings에 profile type과 confidence 기반 조정 이유 추가
- item reason token에 `energy_match`, `valence_match` 같은 기존 설명 유지
- narrow profile이면 `artist_narrow_audio_profile` 또는 `low_quality_audio_profile` token 추가

## 8. API Contract Changes

`GET /api/v1/recommendations/admin/audio-taste/profile`과 `POST /recompute` 응답의 `profile`에 아래 필드를 추가한다.

```json
{
  "profile_type": "ready",
  "profile_confidence": 0.62,
  "profile_focus": "balanced",
  "diversity": {
    "distinct_artist_count": 8,
    "dominant_artist_name": "Artist Name",
    "dominant_artist_share": 0.18,
    "distinct_source_platform_count": 2
  },
  "source_quality_mix": {
    "provider": 0.72,
    "llm_accepted": 0.18,
    "llm_weak": 0.04,
    "lastfm_partial": 0.06,
    "missing": 0.0
  }
}
```

`GET /dataset` rows는 기존 field를 유지한다. Dataset summary에는 위 profile fields가 들어가므로 별도 row schema 변경은 1차 구현에서 필요 없다.

## 9. Data Requirements

첫 구현은 새 DB migration 없이 가능해야 한다.

필요한 입력:

- PMS user library track
- `PmsTrackAudioFeatures`
- `TrackAudioFeatureEvidenceStore`
- `UserMusicEventStore`
- `EventSignalWeights`

Genre/tag 기반 다양성은 현재 row마다 안정적으로 없으므로 첫 구현의 diversity는 artist/source 중심으로 둔다. Genre/tag cluster는 heavy-user expansion 때 추가한다.

## 10. Error Handling

- feature-ready track이 없으면 `profile_type=none`, `profile_confidence=0.0`, `audio_taste_applicable=false`.
- user id가 비어 있으면 기존처럼 실패한다.
- evidence store가 비어 있어도 source quality는 audio feature source와 filled 여부로 계산한다.
- profile type은 API 응답에서 항상 non-null이어야 한다.
- confidence 계산은 NaN이나 Infinity를 만들지 않고 항상 0.0-1.0으로 clamp한다.

## 11. Testing Strategy

Unit tests:

- 0-4곡이면 `none`
- 5-9곡이면 `weak`
- 10-49곡이면 `ready`
- 50-199곡이면 `strong`
- 200곡 이상이면 `heavy`
- 10곡 이상이어도 한 artist 비율이 70% 이상이면 `artist_narrow`
- weak tier 비율이 50% 이상이면 `low_quality`
- confidence는 profile type, feature coverage, source quality, diversity에 따라 증가/감소한다.

GMS service tests:

- `none` profile은 audio taste boost를 적용하지 않는다.
- `weak` profile은 작은 boost만 적용한다.
- `ready` 이상은 기존 boost보다 profile confidence에 따라 조절된다.
- narrow/low-quality profile은 warning과 reason token을 남긴다.

Web/API tests:

- admin audio taste profile response에 `profile_type`, `profile_confidence`, `diversity`, `source_quality_mix`가 snake_case로 내려온다.

## 12. First Implementation Boundary

이번 구현에서 하지 않는 것:

- K-means 또는 embedding cluster 구현
- Python/FastAPI training job 추가
- 새 DB table 추가
- offline metric promotion gate 추가
- UI 대시보드 확장

이번 구현에서 하는 것:

- sparse profile type 산출
- confidence 산출
- artist/source diversity summary 산출
- source quality mix 산출
- GMS audio boost weight를 profile confidence와 type에 연결
- 문서와 API 계약 업데이트

이 경계가 중요한 이유는, 먼저 작은 곡 수에서도 모델이 과신하지 않는 구조를 만든 뒤 heavy-user cluster를 안전하게 붙이기 위해서다.
