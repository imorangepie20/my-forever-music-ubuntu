# Public Curation Track Deduplication Design

## 목적

공개 큐레이션 모델이 PMS와 EMS 등 여러 source에서 수집한 후보를 평가할 때 동일한 음악을 여러 번 선택하지 않도록 한다. 외부 공유 playlist에는 같은 곡이 한 번만 포함되어야 한다.

## 문제 원인

후보 pool은 여러 source의 track을 `union all`로 합친다. 같은 음악이 PMS와 EMS에 각각 저장되어 있으면 서로 다른 `source_scope`와 `source_id`를 가진 별도 후보가 된다.

현재 AI scorer는 모든 후보를 점수화한 뒤 score 순으로 상위 N개를 바로 선택한다. 음악 identity 기준의 dedupe가 없기 때문에 동일한 곡이 여러 slot을 차지할 수 있다.

## 범위

### 포함

- AI scorer의 최종 선택 전 track identity dedupe
- 동일 identity 후보 중 score가 가장 높은 후보 유지
- Spring API 저장 경계에서 결과 중복을 거부하는 방어 검사
- dedupe 결과를 `score_summary`에 기록
- AI와 Spring API 테스트 추가

### 제외

- PMS 또는 EMS 원본 데이터 삭제
- candidate pool SQL의 `union all` 제거
- artist 중복 제한
- 같은 title의 다른 artist 곡 제거
- remix, live, remaster를 별도 판별하는 고급 metadata 모델

## Track Identity 규칙

후보 identity 비교에는 다음 key를 사용한다.

1. `isrc`가 있으면 정규화한 `isrc`
2. `tidal_track_id`가 있으면 정규화한 `tidal_track_id`
3. 항상 정규화한 `artist_name + title`

정규화 규칙:

- 앞뒤 공백 제거
- 소문자 변환
- 연속 공백을 하나로 축소
- fallback `artist_name + title`에서는 Unicode 문자와 숫자만 유지

예시:

```text
isrc:KRA000000001
tidal:10001
metadata:blue trio|rain street
```

ISRC가 있는 후보와 ISRC가 없는 후보가 같은 TIDAL track id를 공유할 수 있다. 단일 key만 계산하면 이 경우를 놓치므로 scorer는 후보마다 가능한 identity key 집합을 만든다.

```text
isrc:KRA000000001
tidal:10001
metadata:blue trio|rain street
```

후보가 이미 선택된 후보와 key 하나라도 겹치면 같은 곡으로 본다.

## AI Scorer 동작

AI scorer는 기존처럼 모든 후보를 먼저 점수화하고 TIDAL-ready 후보만 score 순으로 정렬한다. 그 다음 순서대로 순회하며 아직 선택되지 않은 identity만 남긴다.

```python
selected = []
seen_identity_keys = set()

for candidate, breakdown in sorted_tidal_ready_tracks:
    keys = identity_keys(candidate)
    if keys.intersection(seen_identity_keys):
        continue
    selected.append((candidate, breakdown))
    seen_identity_keys.update(keys)
    if len(selected) >= request.target_track_count:
        break
```

이 방식은 같은 음악 중 score가 가장 높은 후보를 자연스럽게 유지한다.

`score_summary`에는 다음 값을 추가한다.

```json
{
  "duplicate_candidate_count": 2,
  "unique_tidal_ready_count": 28
}
```

- `duplicate_candidate_count`: TIDAL-ready 정렬 결과에서 identity 중복으로 제외된 후보 수
- `unique_tidal_ready_count`: dedupe 후 남은 TIDAL-ready 후보 수

## Spring API 방어 검사

`PublicCurationGenerationService`는 AI scorer 응답을 저장하기 전에 동일 identity track이 두 번 포함되지 않았는지 검사한다.

- 중복이 없으면 기존처럼 draft를 저장한다.
- 중복이 있으면 `502 BAD_GATEWAY`를 반환하고 draft를 저장하지 않는다.
- 오류 메시지는 AI scorer가 duplicate track을 반환했다는 경계를 명확히 표시한다.

Spring API의 identity 계산도 AI scorer와 같은 key 집합 비교를 사용한다. AI가 잘못된 결과를 내거나 모델 구현이 교체되어도 외부 공유 playlist 저장소에는 중복 곡이 들어가지 않는다.

## 파일 경계

- `services/ai/app/services/public_curation_service.py`
  - identity key 계산
  - score 정렬 후 dedupe
  - dedupe summary 기록
- `services/ai/tests/test_public_curation.py`
  - ISRC 중복 제거
  - TIDAL track id 중복 제거
  - metadata fallback 중복 제거
  - 동일 identity 중 최고 score 유지 확인
- `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationGenerationService.java`
  - AI 응답 저장 전 중복 검사
- `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationGenerationServiceTest.java`
  - 중복 AI 응답 저장 거부 확인

## 검증

```bash
cd services/ai
pytest tests/test_public_curation.py

cd ../api
./gradlew test --tests 'io.myforevermusic.api.modules.publiccuration.application.PublicCurationGenerationServiceTest'
```

관리자 공개 큐레이션 생성 화면에서도 새 draft를 만들고 같은 track id 또는 동일 곡 제목이 반복되지 않는지 확인한다.
