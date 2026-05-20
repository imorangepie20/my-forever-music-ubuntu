# Audio Feature Confidence Tier Design

작성일: `2026-05-20`

## 배경

PMS/EMS 기존 DB 샘플 측정 결과, Search + LLM fallback은 실제로 값을 만들 수 있지만 confidence gate가 낮은 TIDAL 트랙에서는 `low_confidence`가 자주 발생했다.

- PMS 10곡 샘플: 평균 약 19.3초/곡, `completed=0`, `unresolved=10`
- EMS 10곡 샘플: 평균 약 21.8초/곡, `completed=4`, `unresolved=6`
- 대표 PMS 실패: `Sleepwalking / James Arthur`, confidence `0.46`, evidence는 있었지만 기준 미달

이 결과는 “LLM/search를 버릴 수준은 아니지만, completed feature로 그대로 승격하기에는 위험하다”는 결론을 만든다.

## 승인된 결정

LLM/search 추론값은 confidence tier로 저장하고, `completed` 여부와 모델 실험 사용 여부를 분리한다.

| Tier | Confidence | 의미 |
|---|---:|---|
| `accepted` | `>= 0.68` | 추천/학습에 사용할 수 있는 inferred snapshot |
| `weak` | `0.50 <= confidence < 0.68` | 완성 처리하지 않지만 낮은 가중치로 실험 가능한 weak signal |
| `rejected` | `< 0.50` 또는 evidence 없음 | feature snapshot으로 쓰지 않는 참고 evidence |

## 저장 정책

`accepted`:

- PMS/EMS audio feature snapshot에 numeric 값을 저장한다.
- `audio_feature_source=llm_search_inferred`로 저장한다.
- `audio_features_filled=true`로 저장한다.
- evidence table에 model output, evidence 요약, confidence를 남긴다.
- completion job은 `completed`로 바꾼다.

`weak`:

- PMS/EMS audio feature snapshot에 numeric 값을 저장한다.
- `audio_feature_source=llm_search_low_confidence` 또는 같은 의미의 명확한 source로 저장한다.
- `audio_features_filled=false`를 유지한다.
- evidence table에 model output, evidence 요약, confidence를 남긴다.
- completion job은 현재 schema에서는 `unresolved`, 사유는 `llm_search_low_confidence`로 남긴다.
- 이후 `needs_review` 상태를 추가하면 같은 데이터를 운영 검토 큐로 승격할 수 있다.

`rejected`:

- PMS/EMS audio feature snapshot은 변경하지 않는다.
- evidence가 있으면 evidence table에 참고 기록만 남긴다.
- completion job은 `unresolved`, 사유는 `llm_search_rejected_low_confidence`, `llm_search_no_evidence`, `llm_search_partial_audio_features` 중 하나로 남긴다.

## 데이터 흐름

1. ReccoBeats lookup을 먼저 시도한다.
2. ReccoBeats가 실패하면 Last.fm tag evidence를 수집한다.
3. Last.fm tag inference가 partial snapshot을 만들 수 있으면 evidence를 저장하고 `audio_features_filled=false`로 둔다.
4. Search + LLM inference를 호출한다.
5. Spring worker는 AI 응답을 `accepted`, `weak`, `rejected`로 분류한다.
6. `accepted`와 `weak`은 numeric estimate와 evidence를 저장한다.
7. 추천/학습 feature builder는 `audio_features_filled`, source, confidence를 함께 보고 가중치를 결정한다.

## API/운영 노출

Admin completion API는 아래 사유를 구분해서 보여준다.

- `llm_search_completed`
- `llm_search_low_confidence`
- `llm_search_rejected_low_confidence`
- `llm_search_no_evidence`
- `llm_search_partial_audio_features`

Feature coverage 화면은 `completed` 수치만으로 판단하지 않고, low-confidence estimate가 몇 개 쌓였는지도 운영자가 볼 수 있어야 한다. 단, 첫 구현에서는 job `top_reasons`와 evidence count로 충분하다.

## 테스트 기준

Spring API worker:

- `status=ok`, confidence `0.68` 이상, 필수 numeric feature 완비이면 snapshot 저장 후 job `completed`.
- confidence `0.50` 이상 `0.68` 미만이면 snapshot과 evidence를 저장하지만 `audio_features_filled=false`, job `unresolved`, 사유 `llm_search_low_confidence`.
- confidence `0.50` 미만이면 snapshot을 변경하지 않고 job `unresolved`, 사유 `llm_search_rejected_low_confidence`.
- evidence가 비어 있으면 snapshot을 변경하지 않고 job `unresolved`, 사유 `llm_search_no_evidence`.
- 필수 numeric feature가 일부 비어 있으면 snapshot을 변경하지 않고 job `unresolved`, 사유 `llm_search_partial_audio_features`.

AI service:

- 기존 `AI_AUDIO_FEATURE_INFERENCE_MIN_CONFIDENCE=0.68`은 `status=ok` 기준으로 유지한다.
- Spring worker가 low-confidence 계층을 판단할 수 있도록 `low_confidence` 응답에도 confidence, features, evidence를 구조화해서 반환한다.

## 구현 범위

이번 구현은 schema를 새로 늘리지 않는다. 이미 있는 PMS/EMS audio feature snapshot 필드와 `track_audio_feature_evidence`, `audio_feature_completion_job.last_error`를 사용한다.

새 모델에서 confidence별 학습 가중치를 적용하는 작업은 다음 단계로 분리한다. 이번 단계의 완료 조건은 completion pipeline이 low-confidence estimate를 버리지 않고 감사 가능한 데이터로 남기는 것이다.
