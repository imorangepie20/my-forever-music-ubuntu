# Taste Mode Affinity Operational Gate Design

작성일: `2026-05-21`

## 1. Purpose

GMS preview는 이미 heavy audio taste profile 사용자에게 `items[].taste_mode_affinity`를 inspection-only 신호로 내려준다. 이 값은 추천 후보가 사용자의 어떤 taste mode와 가까운지 보여주지만, 아직 ranking score에는 영향을 주지 않는다.

이번 단계의 목적은 이 신호를 곧바로 boost로 승격하지 않고, 운영자가 안전하게 판단할 수 있는 gate와 dry-run 구조를 만드는 것이다.

- 기본 정책은 추천 품질 보호다.
- 실제 ranking 변경은 기본적으로 하지 않는다.
- gate가 “적용 가능”으로 판단한 후보에 대해서만 dry-run score delta를 계산한다.
- preview response와 audit log에 gate 결과를 남겨 후속 단계에서 boost를 켤 근거를 쌓는다.

## 2. Product Decision

선택한 방향은 `Preview 응답 + audit log + feature flag dry-run`이다.

즉, 1차 구현은 아래를 한다.

- item별 taste mode affinity gate 결과를 계산한다.
- `/gms-preview` 응답에 gate status와 dry-run 값을 노출한다.
- preview audit log에 gate 요약을 구조화해서 남긴다.
- feature flag가 켜져 있어도 1차 기본값은 ranking을 바꾸지 않는다.

이번 단계에서 하지 않는 것:

- 기본 ranking order 변경
- 실제 taste mode affinity boost 상시 적용
- offline metric promotion gate 구현
- 별도 운영 대시보드 생성
- 새 추천 모델 버전 승격

## 3. Gate States

item별 gate status는 아래 값 중 하나다.

| Status | Meaning | Ranking impact |
| --- | --- | --- |
| `not_applicable` | gate를 평가할 수 없음 | 없음 |
| `blocked` | affinity는 있지만 운영 기준 미달 | 없음 |
| `eligible` | 기준을 통과했지만 dry-run 비활성 또는 delta 없음 | 없음 |
| `dry_run` | 기준을 통과했고 적용 가정 score를 계산함 | 없음 |

`dry_run`은 “적용했다면 이렇게 바뀐다”는 뜻이지 실제 적용이 아니다.

## 4. Gate Reasons

reason은 사람이 읽고 운영 판단에 쓸 수 있는 짧은 token으로 둔다.

| Reason | Condition |
| --- | --- |
| `profile_not_heavy` | profile type이 `heavy`가 아님 |
| `missing_profile` | audio taste profile이 없음 |
| `missing_affinity` | item에 taste mode affinity가 없음 |
| `candidate_audio_unusable` | candidate audio feature가 usable하지 않음 |
| `low_profile_confidence` | `profile_confidence < 0.55` |
| `low_mode_similarity` | `similarity < 0.82` |
| `large_mode_distance` | `distance > 0.18` |
| `weak_token_match` | token이 비어 있거나 `mode_profile_distance`만 있음 |
| `eligible` | 모든 gate 기준 통과 |

여러 조건이 동시에 걸리면 첫 번째 실패 이유를 대표 reason으로 사용하고, 세부 근거는 `reason_tokens`에 모두 남긴다.

## 5. Thresholds

초기 threshold는 보수적으로 둔다.

| Field | Default |
| --- | ---: |
| `min_profile_confidence` | `0.55` |
| `min_similarity` | `0.82` |
| `max_distance` | `0.18` |
| `max_boost_weight` | `0.03` |

왜 이 정도인가:

- taste mode affinity는 아직 inspection-only 신호였으므로, 기존 `audio-taste:v1` boost보다 더 약해야 한다.
- heavy profile이어도 source 품질이나 mode confidence가 낮을 수 있으므로 profile confidence gate를 먼저 둔다.
- similarity/distance는 같은 값을 반대 방향으로 표현하지만, 운영 로그에서 원인을 읽기 쉽게 둘 다 기준으로 남긴다.
- `mode_profile_distance`만 있는 경우는 실제 feature 축의 명확한 match token이 없다는 뜻이므로 block한다.

## 6. Dry-Run Scoring

dry-run score는 실제 ranking에는 쓰지 않는다.

```text
centered_affinity = similarity - 0.5
suggested_boost_weight = min(max_boost_weight, profile_confidence * max_boost_weight)
dry_run_score = current_score * (1 + suggested_boost_weight * centered_affinity)
dry_run_delta = dry_run_score - current_score
```

점수는 기존 GMS score와 같은 scale을 유지하고, 응답에는 소수 4자리까지 반올림한다.

dry-run 계산 조건:

- gate status가 `eligible`이어야 한다.
- dry-run feature flag가 true여야 한다.
- `current_score`가 finite number여야 한다.

dry-run flag가 false이면 status는 `eligible`로 두고 `dry_run_score`와 `dry_run_delta`는 null로 둔다.

## 7. Feature Flags

1차 구현은 설정값만 추가하고 기본은 안전하게 둔다.

| Property | Default | Meaning |
| --- | --- | --- |
| `app.recommendation.taste-mode-affinity.gate.enabled` | `true` | gate 평가 여부 |
| `app.recommendation.taste-mode-affinity.dry-run-enabled` | `true` | 적용 가정 score 계산 여부 |
| `app.recommendation.taste-mode-affinity.apply-ranking-boost` | `false` | 실제 ranking 적용 여부. 1차 구현에서는 false 유지 |
| `app.recommendation.taste-mode-affinity.min-profile-confidence` | `0.55` | profile confidence threshold |
| `app.recommendation.taste-mode-affinity.min-similarity` | `0.82` | similarity threshold |
| `app.recommendation.taste-mode-affinity.max-distance` | `0.18` | distance threshold |
| `app.recommendation.taste-mode-affinity.max-boost-weight` | `0.03` | dry-run/향후 boost 최대 가중치 |

`apply-ranking-boost=true`는 이번 설계에 남기지만, 1차 구현 계획에서는 실제 ranking 변경 경로를 활성화하지 않는다. 후속 promotion 단계에서 별도 스펙과 테스트를 통해 켠다.

## 8. API Contract

`GmsRecommendationPreviewResponse.items[]`에 optional field를 추가한다.

```json
{
  "taste_mode_gate": {
    "status": "dry_run",
    "reason": "eligible",
    "reason_tokens": ["eligible", "mode_energy_match", "mode_valence_match"],
    "suggested_boost_weight": 0.018,
    "dry_run_score": 0.9184,
    "dry_run_delta": 0.0084
  }
}
```

Render/consumer rules:

- field가 없거나 null이면 기존 UI는 그대로 동작한다.
- `status=blocked`이면 reason을 표시할 수 있어야 한다.
- `status=dry_run`이면 score delta를 표시할 수 있어야 한다.
- 실제 score/rank/order와 dry-run score는 다르다는 점을 UI 텍스트로 명확히 한다.

## 9. Audit Contract

preview 단위 audit에는 gate 요약을 남긴다.

추천하는 1차 저장 방식은 `recommendation_audit_log`에 nullable text/json payload를 추가하는 것이다.

필드 이름 후보:

- `taste_mode_gate_summary`

payload 예시:

```json
{
  "gate_enabled": true,
  "dry_run_enabled": true,
  "apply_ranking_boost": false,
  "evaluated_count": 12,
  "eligible_count": 5,
  "dry_run_count": 5,
  "blocked_count": 7,
  "not_applicable_count": 0,
  "max_positive_delta": 0.0112,
  "max_negative_delta": 0.0,
  "reason_counts": {
    "eligible": 5,
    "low_mode_similarity": 4,
    "weak_token_match": 3
  }
}
```

이 방식은 새 테이블보다 작고, 기존 audit 조회 흐름에 자연스럽게 붙는다. 후속 운영 대시보드가 필요해지면 이 payload를 기반으로 별도 집계 테이블을 만들 수 있다.

## 10. Data Flow

1. GMS preview가 PMS library candidate를 playable item으로 매핑한다.
2. 기존 `audio-taste:v1` ranking이 보수적으로 적용된다.
3. `include_explanations=true`이면 nearest taste mode affinity를 계산한다.
4. gate evaluator가 profile, candidate audio feature, affinity, current score를 평가한다.
5. gate 결과를 item에 붙인다.
6. dry-run이 켜져 있고 gate가 통과하면 hypothetical score delta를 계산한다.
7. response warnings에 gate summary를 짧게 남긴다.
8. recommendation audit log에 gate summary payload를 저장한다.

## 11. Error Handling

- gate service는 profile, affinity, candidate feature가 없어도 예외를 던지지 않는다.
- 숫자가 NaN/Infinity이면 score 계산을 건너뛰고 `not_applicable`로 둔다.
- audit 저장 실패가 preview 응답 자체를 깨뜨리면 안 된다. 기존 audit 저장 정책이 preview 실패로 이어지는 구조라면, 구현 계획에서 별도 try/catch 또는 store failure 정책을 명시한다.
- gate disabled이면 `taste_mode_gate`는 null로 두고, warning/audit에는 `gate_enabled=false`만 요약한다.

## 12. Testing Strategy

Unit tests:

- heavy profile + high similarity + strong tokens -> `eligible`
- dry-run enabled -> `dry_run_score`와 `dry_run_delta` 계산
- profile confidence 낮음 -> `blocked / low_profile_confidence`
- similarity 낮음 -> `blocked / low_mode_similarity`
- distance 큼 -> `blocked / large_mode_distance`
- token이 `mode_profile_distance`뿐 -> `blocked / weak_token_match`
- non-heavy profile -> `not_applicable / profile_not_heavy`
- null/NaN score -> dry-run score 없음

GMS service tests:

- `include_explanations=true`일 때 item에 `taste_mode_gate`가 붙는다.
- dry-run이 켜져도 rank/order/score는 바뀌지 않는다.
- response warnings에 gate summary가 남는다.
- audit log에 gate summary payload가 저장된다.

Web tests:

- `taste_mode_gate.status=dry_run`이면 UI가 hypothetical delta를 표시한다.
- `blocked`이면 reason token을 표시한다.
- field가 없으면 기존 card density와 동작이 유지된다.

## 13. Completion Criteria

- GMS preview response에서 taste mode affinity의 운영 gate 결과를 확인할 수 있다.
- dry-run score는 보이지만 실제 recommendation score/rank/order는 바뀌지 않는다.
- audit log에 preview 단위 gate summary가 남는다.
- gate thresholds는 application property로 조정 가능하다.
- 후속 단계에서 실제 boost 적용 여부를 판단할 수 있는 근거가 쌓인다.
