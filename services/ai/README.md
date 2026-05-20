# services/ai

FastAPI 기반 AI/추천 서비스 폴더입니다.

## 목표

- 추천 계산
- 임베딩/모델 추론
- AI 보강 API
- `services/api`가 호출하는 AI 전용 백엔드 제공
- Spotify 오디오 특성 기반 분석 데이터 보강
- 오디오 특성을 확보하지 못한 트랙의 명시적 실패/재시도/제외 정책 지원
- 사용자 플레이리스트와 행동 데이터를 반영한 개인화 모델 지원
- EMS 외부 플레이리스트와 트렌딩 트랙 평가 지원

## 현재 스캐폴드에 포함된 것

- `app.main` 실행 엔트리포인트
- 기본 정보 엔드포인트: `/`
- 헬스체크 엔드포인트: `/health`
- 추천 미리보기 엔드포인트: `POST /v1/recommendations/preview`
- 추천 dataset 검증 엔드포인트: `POST /v1/recommendations/datasets/validate`
- SASRec dataset 준비 엔드포인트: `POST /v1/recommendations/datasets/sasrec/prepare`
- SASRec offline metric report 엔드포인트: `POST /v1/recommendations/datasets/sasrec/offline-report`
- PyTorch SASRec MVP 학습 엔드포인트: `POST /v1/recommendations/datasets/sasrec/train`
- SASRec MVP 후보 랭킹 엔드포인트: `POST /v1/recommendations/datasets/sasrec/rank`
- Search + LLM audio feature 추론 엔드포인트: `POST /v1/audio-features/infer`
- FastAPI 문서 경로: `/docs`
- OpenAPI 경로: `/openapi.json`
- 최소 테스트 파일: `tests/test_health.py`

## 권장 구조

```text
services/ai/
├── app/
│   ├── config.py
│   ├── main.py
│   ├── routers/
│   ├── schemas/
│   ├── services/
│   ├── models/
│   └── jobs/
├── tests/
├── requirements.txt
├── requirements-dev.txt
└── README.md
```

## 환경 변수

- `AI_APP_NAME`: 서비스 이름, 기본값 `My Forever Music AI`
- `AI_APP_VERSION`: 서비스 버전, 기본값 `0.1.0`
- `AI_ENV`: 실행 환경, 기본값 `local`
- `AI_ROOT_PATH`: Nginx 뒤에서 `/ai/*`로 공개할 때 사용할 prefix
- `AI_MODEL_ARTIFACT_DIR`: SASRec MVP 같은 모델 artifact 저장 디렉터리, 기본값 `models`
- `AI_EMS_OVERVIEW_MODEL`: EMS Overview 해석에 사용할 LLM 모델. 비어 있으면 해석을 생성하지 않고 `model_not_configured`를 반환
- `AI_EMS_ACQUISITION_MODEL`: EMS editorial acquisition signal 추출에 사용할 LLM 모델. 비어 있으면 `AI_EMS_OVERVIEW_MODEL`을 재사용
- `AI_AUDIO_FEATURE_INFERENCE_MODEL`: Search + LLM 기반 audio feature estimate에 사용할 모델. 기본값 `gpt-5-mini`
- `AI_AUDIO_FEATURE_INFERENCE_MIN_CONFIDENCE`: `llm_search_inferred` 저장 최소 confidence. 기본값 `0.68`
- `AI_LLM_API_KEY`: OpenAI-compatible chat completions provider API key
- `AI_LLM_BASE_URL`: OpenAI-compatible LLM base URL, 기본값 `https://api.openai.com/v1`

`AI_ROOT_PATH`는 직접 `8000` 포트로 접근할 때는 비워두고, Ubuntu 서버에서 Nginx가 `/ai/`로 프록시할 때는 `/ai`로 주는 것을 권장합니다.

## 실행 예시

직접 포트로 실행:

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Nginx `/ai/` 프록시 뒤에서 실행:

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt
AI_ROOT_PATH=/ai uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## 확인 경로

- 직접 실행 시
  - `http://127.0.0.1:8000/`
  - `http://127.0.0.1:8000/health`
  - `http://127.0.0.1:8000/docs`

- Nginx 프록시 뒤 실행 시
  - `http://SERVER_IP/ai/`
  - `http://SERVER_IP/ai/health`
  - `http://SERVER_IP/ai/docs`

## 테스트

```bash
pytest
```

`pytest.ini`가 포함되어 있어 현재는 별도 `PYTHONPATH` 설정 없이 위 명령만 실행하면 됩니다.

## 추천 API 초안

현재 preview 엔드포인트는 실제 개인화 모델 이전의 내부 계약 검증용입니다. 사용자-facing 추천은 PMS user library가 준비된 뒤 `services/api`에서 실제 playable track으로 재매핑되는 흐름을 기준으로 합니다.

- 경로: `POST /v1/recommendations/preview`
- 용도: PMS / EMS / GMS 추천 흐름에서 AI 서비스 응답 형태를 먼저 검증
- 특성: 실제 개인화 모델 전 단계의 rule-based preview 응답 생성

예시 요청:

```json
{
  "request_id": "preview-001",
  "user_id": "user-123",
  "playlist_id": "pms-spotify-{spotify_playlist_id}",
  "mode": "gms",
  "mood": "upbeat",
  "energy_level": 4,
  "familiarity_bias": 3,
  "limit": 5,
  "seed_track_ids": ["pms-track-spotify-{spotify_track_id}"],
  "seed_artist_names": ["Imported Artist"],
  "seed_genres": ["imported-genre"],
  "include_explanations": true
}
```

예시 호출:

```bash
curl -X POST http://127.0.0.1:8000/v1/recommendations/preview \
  -H 'Content-Type: application/json' \
  -d '{
    "mode": "gms",
    "user_id": "user-{uuid}",
    "playlist_id": "pms-spotify-{spotify_playlist_id}",
    "mood": "upbeat",
    "limit": 3,
    "seed_track_ids": ["pms-track-spotify-{spotify_track_id}"]
  }'
```

상세 계약 문서는 [AI_RECOMMENDATION_PREVIEW.md](/Users/woosungjo/music-space/my-forever-music/docs/api/AI_RECOMMENDATION_PREVIEW.md) 에 정리했습니다.

## 추천 Dataset Import Harness

Spring API의 `GET /api/v1/recommendations/datasets/users/{userId}/sequence` 응답을 AI service로 가져와 학습 가능 상태를 검증하는 내부 엔드포인트입니다.

- 경로: `POST /v1/recommendations/datasets/validate`
- 용도: `user_music_event`와 `recommendation_snapshot` 기반 sequence payload 검증
- 응답: dataset id, readiness status, sequence/event/snapshot count, unique track count, positive/negative signal count, token preview, warnings

현재 단계에서는 payload의 구조와 readiness를 검증합니다. 실제 모델 학습은 `/sasrec/train`에서 수행합니다.

SASRec MVP 입력 준비 엔드포인트:

- 경로: `POST /v1/recommendations/datasets/sasrec/prepare`
- 입력: dataset validate와 같은 Spring exporter payload
- 출력: `track_id -> item_index` vocabulary, next-item training window, target weight, warning

이 단계는 PyTorch 모델을 아직 실행하지 않고, sequence payload가 SASRec류 next-item training 예제로 변환 가능한지 검증합니다.

Offline metric report 엔드포인트:

- 경로: `POST /v1/recommendations/datasets/sasrec/offline-report`
- 방식: leave-last-out 평가
- 출력: `HitRate@K`, `MRR@K`, `NDCG@K`, evaluation example, warning

현재 report는 recency baseline을 사용합니다. PyTorch 기반 SASRec 모델은 이 report를 baseline으로 삼아 개선 여부를 비교합니다.

PyTorch SASRec MVP 학습 엔드포인트:

- 경로: `POST /v1/recommendations/datasets/sasrec/train`
- 방식: 1-layer Transformer encoder 기반 next-item prediction
- 입력: dataset validate와 같은 Spring exporter payload
- 출력: model version, train/evaluation split, final loss, leave-last-out metric, recency baseline metric, metric delta, predicted item indices, model artifact path

기본적으로 `AI_MODEL_ARTIFACT_DIR/sasrec/{model_version}/` 아래에 `model.pt`와 `metadata.json`을 저장합니다. API 검증이나 임시 실행에서 파일 저장을 원하지 않으면 `persist_artifact=false` 쿼리 파라미터를 사용합니다.

저장된 최신 artifact는 아래 경로에서 확인할 수 있습니다.

```http
GET /v1/recommendations/datasets/sasrec/models/latest?user_id={userId}
```

Spring API는 `AI_SASREC_MODEL_VERSION`이 비어 있을 때 이 경로를 조회해 GMS preview 재정렬에 사용할 사용자 최신 SASRec 모델을 찾습니다.

SASRec MVP 후보 랭킹 엔드포인트:

- 경로: `POST /v1/recommendations/datasets/sasrec/rank`
- 입력: `model_version`, `context_track_ids`, `candidate_track_ids`, `k`
- 방식: 저장된 `model.pt`와 `metadata.json`을 로드해 candidate track logits를 정렬
- 출력: ranked candidates, unknown track warning, artifact missing status

장기적으로는 이 서비스가 아래 역할까지 확장됩니다.

- Spotify 오디오 특성 적재
- 미수집 트랙의 재시도/부분 제외/사용자 안내 정책
- 사용자별 취향 모델 추가 학습
- EMS 수집 트랙을 사용자 모델로 평가해 GMS 후보 생성
- 사용자 행동 데이터 기반 재학습

사용자별 음악 학습 모델은 플랫폼 연동과 PMS user library 저장이 먼저 안정화된 뒤 개발합니다. 모델 입력의 1차 기준은 `PMS user library`, Spotify audio feature snapshot, Last.fm scrobble snapshot, 사이트 내부 평가/재생 행동 이벤트입니다.

## 다음 구현 우선순위

1. Spotify 오디오 특성 적재 실패 시 재시도/부분 제외/사용자 안내 전략 정리
2. `services/api` 호출용 내부 계약과 에러 코드 정리
3. recency baseline 대비 offline metric 개선 검증 자동화
4. Spring API에서 SASRec rank endpoint 호출 연결
5. 플랫폼 연동 이후 생성되는 `PMS user library` 기반 사용자 모델 입력 계약 확장
