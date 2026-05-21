# Taste Mode Affinity Preview Design

작성일: `2026-05-21`
구현 상태: `implemented`

현재 구현은 `AudioTasteModeAffinityService`와 GMS preview item 응답 필드 `taste_mode_affinity`를 추가한다. 이 필드는 `include_explanations=true`이고 사용자의 audio taste profile이 `heavy`일 때만 채워지며, ranking score/order/context에는 영향을 주지 않는다.

## 1. Purpose

`Taste Mode Affinity Preview`는 `Heavy Taste Modes v1`의 관찰 단계를 GMS preview 후보 단위까지 확장한다.

현재 `taste_modes`는 admin audio taste profile/dataset 응답에서만 볼 수 있다. 이 정보만으로는 실제 GMS 후보가 사용자의 어떤 청취 모드와 가까운지 확인하기 어렵다. 이번 단계의 목적은 추천 순위는 바꾸지 않고, 각 playable GMS candidate가 어떤 heavy taste mode와 가장 가까운지 inspection-only 정보로 보여주는 것이다.

## 2. Product Decision

사용자가 선택한 방향은 아래와 같다.

- 기존 GMS preview item에 `taste_mode_affinity`를 추가한다.
- 단, `include_explanations=true` 요청에서만 채운다.
- `include_explanations=false` 요청에서는 field를 `null`로 둔다.
- GMS ranking score, item order, `context.engine`, audit model version에는 영향을 주지 않는다.

이렇게 하는 이유는 실제 추천 품질에 영향을 주기 전에, mode 분리가 후보 수준에서 납득 가능한지 먼저 확인하기 위해서다.

## 3. Scope

이번 구현에서 하는 것:

- `POST /api/v1/gms/recommendations/preview` 응답 item에 inspection-only `taste_mode_affinity`를 추가한다.
- `include_explanations=true`일 때만 후보별 nearest taste mode를 계산한다.
- heavy profile이 아니거나 `taste_modes`가 비어 있거나 candidate audio feature가 usable하지 않으면 `taste_mode_affinity=null`을 반환한다.
- affinity 계산은 Spring API 내부에서 on-demand로 수행한다.

이번 구현에서 하지 않는 것:

- GMS ranking score 변경
- GMS item order 변경
- `context.engine += "+taste-mode"` 같은 serving model 표시
- 새로운 DB table, migration, audit schema 추가
- UI 표시 변경
- K-means 또는 새 clustering 모델 추가

## 4. Response Contract

`GmsRecommendationPreviewResponse.RecommendationItem`에 아래 field를 추가한다.

```json
{
  "taste_mode_affinity": {
    "applied": true,
    "mode_id": "mode-1",
    "label": "high_energy_bright_danceable",
    "similarity": 0.86,
    "distance": 0.14,
    "tokens": ["mode_energy_match", "mode_tempo_match"]
  }
}
```

Field rules:

| Field | Rule |
| --- | --- |
| `applied` | affinity가 계산되면 `true` |
| `mode_id` | 가장 가까운 `AudioTasteMode.modeId` |
| `label` | 가장 가까운 `AudioTasteMode.label` |
| `similarity` | `1.0 - distance`, `0.0-1.0` clamp 후 4자리 반올림 |
| `distance` | candidate와 mode centroid 간 normalized distance |
| `tokens` | 가까운 축 설명 token. 없으면 `mode_profile_distance` |

`taste_mode_affinity`는 계산 대상이 아니면 `null`이다. 빈 object나 `applied=false` object를 내려주지 않는다. 이 방식이 “이번 요청에서 관찰 가능한 mode affinity가 있었다”와 “없었다”를 가장 단순하게 구분한다.

## 5. Applicability

Affinity는 아래 조건이 모두 맞을 때만 계산한다.

| Condition | Rule |
| --- | --- |
| request | `include_explanations=true` |
| profile type | `heavy` |
| profile modes | `profile.tasteModes()` non-empty |
| candidate | playable PMS-backed candidate |
| candidate audio | `AudioTasteTrackFeature.usable() == true` |
| serving impact | none |

`include_explanations=false`일 때는 heavy profile이라도 계산하지 않는다.

## 6. Affinity Model

Nearest mode는 candidate audio feature vector와 각 mode centroid의 normalized Euclidean distance로 고른다.

사용 축:

- acousticness
- danceability
- energy
- instrumentalness
- liveness
- speechiness
- normalized tempo
- valence

Distance:

```text
distance = min(1.0, sqrt(sum(axis_delta^2) / 8))
similarity = round(clamp(1.0 - distance))
```

Tie-break:

1. lower distance
2. higher mode confidence
3. higher mode track count
4. `mode_id` ascending

Explanation token:

| Axis | Token rule |
| --- | --- |
| energy | `abs(candidate.energy - mode.energy) <= 0.15` -> `mode_energy_match` |
| valence | `abs(candidate.valence - mode.valence) <= 0.15` -> `mode_valence_match` |
| danceability | `abs(candidate.danceability - mode.danceability) <= 0.15` -> `mode_danceability_match` |
| tempo | `abs(normalized_candidate_tempo - mode.tempo) <= 0.15` -> `mode_tempo_match` |

If no token matches, return `mode_profile_distance`.

## 7. Architecture

Add a small application service:

```java
AudioTasteModeAffinityService
```

Responsibilities:

- Accept `AudioTasteProfileService.Profile` and candidate `AudioTasteTrackFeature`.
- Return nullable/optional `TasteModeAffinity`.
- Keep distance/token math independent from GMS preview orchestration.

Add response record:

```java
GmsRecommendationPreviewResponse.TasteModeAffinityItem
```

GMS preview service change:

- Reuse the existing `AudioTasteProfileService.Profile` when audio taste ranking already computed it.
- If the profile was not computed because ranking did not need it, compute a read-only profile only for `include_explanations=true` affinity output.
- While converting `RankedLibraryCandidate` to `RecommendationItem`, attach affinity only when request `includeExplanations()` is true.
- Do not feed affinity back into `RankedLibraryCandidate.affinityScore`.
- Do not add a new taste-mode context or model marker. Existing `audio-taste:v1` context behavior remains unchanged.

## 8. Error Handling

- Missing profile -> no affinity.
- Non-heavy profile -> no affinity.
- Empty `tasteModes` -> no affinity.
- Missing candidate audio feature -> no affinity.
- Unusable candidate audio feature -> no affinity.
- Null numeric audio values use midpoint behavior, same as existing audio taste scoring.
- Any calculated distance/similarity is clamped to `0.0-1.0`.

No warnings are added for skipped affinity. The absence of `taste_mode_affinity` is enough; warnings should remain reserved for serving-impacting or data-quality events.

## 9. Testing Strategy

Unit tests:

- Heavy profile with taste modes and usable candidate returns nearest mode affinity.
- Tie-break prefers lower distance, then higher confidence/count/id.
- Non-heavy profile returns empty.
- Unusable candidate returns empty.

GMS preview tests:

- `include_explanations=true` and heavy profile returns `items[].taste_mode_affinity`.
- `include_explanations=false` returns `taste_mode_affinity=null`.
- Candidate ranking order does not change because of mode affinity.
- `context.engine` does not gain a new taste-mode marker.
- Existing `audio-taste:v1` ranking behavior remains unchanged.

Regression command:

```bash
cd services/api
./gradlew test --tests '*AudioTaste*' --tests '*GmsRecommendationPreviewServiceTest*' --rerun-tasks
```

## 10. Implementation Boundary

This feature is a diagnostic lens, not a recommender. It should help decide whether `taste_modes` are meaningful enough to become a future ranking signal.

The next decision after this step is whether candidate-to-mode similarity should become a small GMS boost signal for heavy users. That decision requires manual inspection of live preview responses first.
