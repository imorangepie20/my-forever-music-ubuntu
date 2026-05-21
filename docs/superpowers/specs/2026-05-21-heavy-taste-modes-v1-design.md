# Heavy Taste Modes v1 Design

작성일: `2026-05-21`

## 1. Purpose

`Heavy Taste Modes v1`은 `SparseAndMultiModeTasteModel v1`의 다음 단계다.

현재 Audio Taste Model은 사용자의 positive centroid 하나를 중심으로 GMS preview 후보에 작은 boost를 준다. 이 방식은 sparse user에게는 안전하지만, feature-ready track이 200곡 이상인 heavy user에게는 취향을 하나의 평균값으로 눌러버릴 위험이 있다.

이번 단계의 목표는 추천 serving을 흔들지 않고, heavy user의 PMS library를 여러 청취 모드로 나누어 admin profile/dataset에서 먼저 관찰하는 것이다.

## 2. Product Decision

이번 구현은 사용자가 선택한 1번 방향을 따른다.

- `taste_modes`는 admin audio taste profile과 dataset response에만 노출한다.
- GMS preview ranking, `context.engine`, item score에는 아직 연결하지 않는다.
- 모드 품질을 실제 사용자 데이터로 확인한 뒤 다음 단계에서 GMS boost에 연결한다.

이 결정은 현재 audio feature coverage가 아직 올라가는 중이고, LLM inferred feature도 섞여 있기 때문이다. 먼저 모델이 사용자의 취향을 납득 가능한 방식으로 분리하는지 확인한다.

## 3. Scope

이번 구현에서 하는 것:

- `profile_type=heavy`인 사용자에게 `taste_modes`를 계산한다.
- `GET /api/v1/recommendations/admin/audio-taste/profile`
- `POST /api/v1/recommendations/admin/audio-taste/recompute`
- `GET /api/v1/recommendations/admin/audio-taste/dataset`
- 각 mode는 centroid, confidence, track count, top artists, representative tracks를 포함한다.
- 계산은 Spring API 내부에서 on-demand로 수행한다.
- 새 DB table, migration, Python training job은 만들지 않는다.

이번 구현에서 하지 않는 것:

- GMS preview scoring에 `taste_modes` 적용
- K-means 또는 embedding clustering
- offline model artifact 저장
- UI 대시보드 확장
- genre/tag 기반 cluster

## 4. Applicability

`taste_modes`는 아래 조건에서만 non-empty로 내려간다.

| Condition | Rule |
| --- | --- |
| profile type | `heavy` |
| usable feature rows | `>= 200` |
| usable source quality | missing tier 제외 |
| serving impact | none |

`profile_type`이 `none`, `weak`, `ready`, `strong`이면 `taste_modes=[]`를 반환한다.

이렇게 하는 이유는 strong user도 centroid 기반으로는 충분히 설명 가능하고, 200곡 미만에서 mode를 억지로 나누면 모드별 대표성이 쉽게 약해지기 때문이다.

## 5. Mode Model

v1은 deterministic bucket clustering을 사용한다.

K-means는 아직 쓰지 않는다. K-means는 seed, iteration, convergence, empty cluster, scaling 정책을 함께 고정해야 하고 결과가 설명하기 어렵다. 현재 목적은 추천 품질 향상이 아니라 admin 검증이므로, 고정 규칙으로 같은 데이터에는 항상 같은 결과를 내는 것이 더 중요하다.

### 5-1. Feature axes

각 usable track은 아래 축으로 bucket key를 만든다.

| Axis | Rule |
| --- | --- |
| energy | `high` if `energy >= 0.65`, `low` if `energy <= 0.35`, otherwise `mid` |
| valence | `bright` if `valence >= 0.62`, `dark` if `valence <= 0.38`, otherwise `neutral` |
| danceability | `danceable` if `danceability >= 0.65`, otherwise omitted |
| acousticness | `acoustic` if `acousticness >= 0.65`, otherwise omitted |
| tempo | `fast` if normalized tempo >= `0.65`, `slow` if normalized tempo <= `0.35`, otherwise omitted |

Bucket label examples:

- `high_energy_bright_danceable`
- `low_energy_acoustic`
- `mid_energy_neutral`
- `high_energy_dark_fast`

### 5-2. Mode target count

Mode count is capped by feature-ready row count.

| Usable tracks | Target mode count |
| ---: | ---: |
| `200-399` | `3-4` |
| `400-799` | `4-6` |
| `800+` | `5-8` |

The algorithm first groups by bucket key, sorts by weighted track count, then keeps the largest target count.

Very small buckets are merged into the nearest kept bucket. Distance is calculated between bucket centroids using:

- acousticness
- danceability
- energy
- instrumentalness
- liveness
- speechiness
- normalized tempo
- valence

If there are fewer than 3 meaningful buckets, the response returns the available modes and adds a warning that the heavy profile is mode-narrow.

## 6. Mode Fields

Each `taste_modes[]` item includes:

```json
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
    { "artist_name": "Artist A", "track_count": 12 },
    { "artist_name": "Artist B", "track_count": 8 }
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
```

### Confidence

Mode confidence is explanatory, not a serving score.

Initial formula:

```text
mode_confidence =
  clamp(
    profile_confidence
    * min(1.0, sqrt(mode_track_count / 50))
    * average_feature_weight
    * artist_diversity_multiplier,
    0.0,
    1.0
  )
```

`artist_diversity_multiplier` is:

- `0.70` if one artist is 70 percent or more of the mode
- `0.85` if one artist is 50 percent or more of the mode
- `1.00` otherwise

## 7. Profile Contract

`AudioTasteProfileService.Profile` adds:

```java
List<TasteMode> tasteModes
```

For non-heavy profiles, `tasteModes` is an empty list.

The admin JSON uses snake case:

```json
{
  "profile": {
    "profile_type": "heavy",
    "taste_modes": []
  }
}
```

`GET /dataset` keeps existing row schema. The dataset `profile` contains the same `taste_modes` summary, so there is no separate row-level mode assignment in v1.

## 8. Data Requirements

Required inputs already exist:

- `PmsUserLibraryStore`
- `PmsTrackAudioFeatures`
- `AudioTasteTrackFeature`
- `AudioTasteFeatureQuality`
- existing source tier and feature weight

No new persistence is required.

## 9. Error Handling

- Empty PMS library returns `taste_modes=[]`.
- Non-heavy profile returns `taste_modes=[]`.
- A track with incomplete or unusable audio features is excluded.
- Null numeric features use the same midpoint behavior as the centroid model.
- Mode confidence is always clamped to `0.0-1.0`.
- Representative tracks are sorted by distance to centroid and limited to 5 per mode.
- Top artists are limited to 5 per mode.

## 10. Testing Strategy

Unit tests:

- Heavy profile with 200 usable tracks returns at least 3 taste modes.
- Strong profile with 199 usable tracks returns no taste modes.
- High-energy danceable tracks and low-energy acoustic tracks split into different modes.
- Small buckets merge into nearest larger mode.
- Representative tracks are closest to centroid.
- Mode confidence decreases when a single artist dominates.

Web/API tests:

- `GET /profile` exposes `profile.taste_modes` in snake case.
- Non-heavy profile returns an empty `taste_modes` array.

Regression tests:

- GMS preview response does not change `context.engine` or ranking because of `taste_modes`.
- Existing audio taste boost remains driven by `audio-taste:v1` centroid scoring only.

## 11. Implementation Boundary

This implementation is an inspection layer. It should make the user's taste structure visible without changing recommendation output.

The next serving step will be a separate decision: use `taste_modes` to select candidate-specific mode affinity, then blend that signal into GMS boost only after manual inspection shows stable modes.
