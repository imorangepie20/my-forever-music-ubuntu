# Audio Taste Model v1 Design

작성일: `2026-05-20`

## 배경

오디오 특성 completion pipeline이 PMS/EMS track의 `danceability`, `energy`, `valence`, `tempo`, `acousticness` 같은 provider-neutral feature를 채우기 시작했다. 이제 이 값을 추천 모델에 연결해야 한다.

기존 추천 축은 이미 아래 기반을 갖고 있다.

- `SASRec` sequence model과 registry/promote/rollback 흐름
- `user_personalization_profile` 기반 artist/source platform reranker
- `GMS` playlist 6축 evaluator
- recommendation snapshot/audit log
- audio feature completion job/evidence와 confidence tier 정책

이번 단계의 목적은 큰 deep model을 바로 추가하는 것이 아니라, 오디오 취향 신호를 설명 가능하고 검증 가능한 방식으로 하이브리드 추천에 붙이는 것이다.

## 승인된 방향

첫 모델은 아래 순서로 간다.

1. `AudioTasteDataset v1`
   - PMS user library, user music event, recommendation snapshot, PMS/EMS audio feature snapshot을 결합한다.
   - source confidence, missing indicator, evidence count를 함께 내보낸다.

2. `AudioCentroidBaseline v1`
   - 사용자의 positive/negative audio centroid를 만든다.
   - 후보 track audio feature와 centroid 간 거리를 score로 바꾼다.
   - 설명 가능한 운영 baseline으로 먼저 serving에 붙인다.

3. `AudioTasteVectorModel v1`
   - user audio vector와 item audio/tag vector의 dot product 또는 가벼운 ranking model로 확장한다.
   - 첫 구현에서는 artifact 계약과 dataset validation까지만 준비하고, serving 핵심은 centroid baseline으로 둔다.

4. `HybridReranker v1`
   - 기존 SASRec/profile/GMS score에 audio taste score를 낮은 비중으로 결합한다.
   - audio coverage와 confidence가 낮으면 audio score 영향력을 자동으로 줄인다.

## 데이터 규칙

### Track feature

필수 numeric feature:

- `danceability`
- `energy`
- `valence`
- `acousticness`
- `instrumentalness`
- `liveness`
- `speechiness`
- `tempo`

보조 feature:

- `duration_ms`
- `primary_genre`
- `source_platform`
- `audio_feature_source`
- `audio_features_filled`
- `audio_feature_confidence` 또는 evidence confidence
- `evidence_count`

Feature quality weight:

| Source 상태 | 기본 weight |
|---|---:|
| measured/provider lookup 또는 `audio_features_filled=true` | `1.00` |
| `llm_search_inferred` accepted | `0.70` |
| `llm_search_low_confidence` weak | `0.35` |
| Last.fm partial inferred | `0.25` |
| feature missing 또는 rejected | `0.00` |

실제 코드에서는 snapshot column이 부족할 수 있으므로, `audio_feature_source`, `audio_features_filled`, `track_audio_feature_evidence.confidence`, evidence count로 weight를 계산한다.

### User label weight

Positive:

| Event | Weight |
|---|---:|
| `track_saved` | `2.0` |
| `added_to_playlist` | `1.7` |
| `recommendation_liked` | `1.5` |
| `play_completed` | `1.2` |
| `replay` | `1.1` |

Weak/neutral:

| Event | Weight |
|---|---:|
| `play_started` | `0.3` |
| `ignored_recommendation` | `-0.1` |

Negative:

| Event | Weight |
|---|---:|
| `skip_next` | `-0.25` |
| `recommendation_rejected` | `-1.5` |

이 가중치는 기존 `EventSignalWeights`와 맞춰야 한다. 값이 이미 코드에 있으면 그 값을 single source of truth로 사용한다.

## AudioCentroidBaseline v1

사용자별로 아래 summary를 만든다.

- positive centroid: positive event가 붙은 track feature의 weighted mean
- negative centroid: negative event가 붙은 track feature의 weighted mean
- variance preference: positive track feature의 weighted variance
- coverage summary: 사용된 track 수, filled ratio, inferred ratio, weak ratio

Candidate score:

```text
positive_similarity = 1 - normalized_distance(candidate, positive_centroid)
negative_distance_bonus = normalized_distance(candidate, negative_centroid)
coverage_weight = min(1.0, user_feature_ready_ratio * candidate_feature_weight)

audio_taste_score =
  coverage_weight
  * clamp(0.70 * positive_similarity + 0.30 * negative_distance_bonus, 0, 1)
```

운영 규칙:

- positive feature-ready track이 `10`곡 미만이면 audio taste score를 적용하지 않는다.
- candidate feature weight가 `0`이면 no-op 한다.
- weak inferred feature만 있는 사용자는 audio score 최대 영향력을 절반으로 줄인다.
- score와 함께 explanation token을 만든다: `energy_match`, `tempo_match`, `valence_match`, `acoustic_profile_match`, `low_confidence_audio`.

## Hybrid scoring

첫 serving 결합은 보수적으로 둔다.

```text
final_score =
  0.55 * existing_score
  + 0.25 * sasrec_adjustment
  + 0.10 * profile_adjustment
  + 0.10 * audio_taste_score
```

현재 코드에서 `existing_score`와 SASRec/profile boost가 이미 섞여 있으면, 첫 구현은 `RecommendationReranker` 이후 마지막 단계에서 audio boost를 곱하는 방식으로 시작한다.

```text
boosted_score = current_score * (1 + audio_weight * centered_audio_taste_score)
```

초기 `audio_weight` 기본값은 `0.12`로 둔다. 아래 조건에서는 자동 축소한다.

- user audio coverage `< 0.30`
- candidate audio feature weight `< 0.50`
- weak inferred feature ratio `> 0.50`
- audio model offline metric이 baseline 대비 회귀

## API와 운영 화면

첫 구현 API:

- `GET /api/v1/recommendations/admin/audio-taste/dataset?user_id&target_user_id`
  - dataset summary와 sample rows를 반환한다.
- `POST /api/v1/recommendations/admin/audio-taste/recompute?user_id&target_user_id`
  - user audio centroid를 재계산한다.
- `GET /api/v1/recommendations/admin/audio-taste/profile?user_id&target_user_id`
  - 현재 centroid, coverage, feature weights, warnings를 반환한다.

첫 구현에서는 별도 사용자 화면을 만들지 않는다. 관리자 화면은 기존 `/recommendations/sasrec-admin` 또는 `/recommendations/feature-coverage`에 작은 패널로 붙인다.

Recommendation audit log에는 GMS preview 생성 시 아래 값을 남긴다.

- `audio_model_version`
- `audio_taste_applied`
- `audio_taste_weight`
- `audio_feature_dataset_fingerprint`
- coverage/warning summary

기존 `recommendation_audit_log` schema가 부족하면 payload 문자열 또는 context summary에 먼저 포함하고, schema 확장은 별도 migration으로 분리한다.

## AI service 역할

v1의 serving baseline은 Spring API에서 계산한다.

AI service는 다음 단계에서 아래 endpoint를 맡는다.

- `POST /v1/recommendations/datasets/audio-taste/validate`
- `POST /v1/recommendations/datasets/audio-taste/train`
- `POST /v1/recommendations/datasets/audio-taste/rank`

첫 구현의 성공 조건은 AI deep model이 아니라, Spring dataset과 centroid baseline이 실제 GMS ranking에 안전하게 들어가는 것이다.

## Evaluation

Offline:

- 기존 GMS ranking 대비 Hit@K, MRR@K, nDCG@K 회귀 없음
- `audio_taste_score` 단독 ranking과 hybrid ranking 비교
- confidence tier별 성능 분리
- PMS source platform별 성능 분리

Product proxy:

- 추천 track save rate
- GMS playlist save rate
- recommendation liked/rejected ratio
- skip_next 감소
- play_completed 증가

Promotion gate:

- positive feature-ready track `>= 10`
- user audio coverage `>= 0.30`
- candidate feature-ready ratio `>= 0.30`
- 핵심 metric 회귀 없음
- low-confidence-only cohort에서 별도 회귀 없음

## 구현 경계

이번 v1 범위에 포함:

- audio taste dataset summary/export
- user audio centroid 계산
- centroid profile 저장소 또는 재계산 서비스
- GMS preview ranking에 audio boost 적용
- admin 조회/recompute endpoint
- audit/warnings에 audio 적용 여부 기록
- 단위 테스트와 최소 통합 테스트

이번 v1 범위에서 제외:

- 복잡한 neural audio taste model 학습
- 별도 vector DB
- 대규모 batch training scheduler
- 일반 사용자에게 audio taste 내부 지표 노출
- schema 대형 개편

## 성공 기준

- feature coverage가 충분한 사용자에게 GMS preview ranking이 audio taste score를 반영한다.
- coverage가 부족한 사용자나 low-confidence catalog에서는 자동 no-op 또는 낮은 영향력으로 동작한다.
- 추천 결과의 reason/audit에서 audio score 적용 여부를 추적할 수 있다.
- 기존 SASRec/profile/GMS baseline이 깨지지 않는다.
