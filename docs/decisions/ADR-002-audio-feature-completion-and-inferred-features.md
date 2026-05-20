# ADR-002 Audio Feature Completion and Inferred Features

작성일: `2026-05-20`

## 상태

승인

## 결정

오디오 특성이 비어 있는 트랙은 `ReccoBeats -> Last.fm evidence -> Search + LLM inference` 순서의 completion pipeline으로 보강한다.

기존의 `가짜 수치 생성 금지` 원칙은 유지한다. 다만 LLM/검색/태그 기반 추론값은 아래 조건을 만족할 때 모델 입력용 estimate로 저장할 수 있다.

- 측정값 또는 provider lookup 값으로 표시하지 않는다.
- `audio_feature_source_class`를 `tag_inferred` 또는 `llm_search_inferred`로 저장한다.
- `audio_feature_confidence`를 저장한다.
- evidence와 inference model/rule version을 audit trail로 남긴다.
- 이후 더 신뢰도 높은 provider lookup 값이 들어오면 그 값을 우선한다.
- confidence 기준 미달이면 값을 채우지 않고 unresolved로 둔다.

## 배경

개인화 추천의 다음 단계는 기존 `SASRec`, `user_personalization_profile`, `GMS 6-axis evaluator` 위에 사용자의 오디오 취향 벡터를 추가하는 것입니다.

이 모델은 `danceability`, `energy`, `valence`, `acousticness`, `tempo` 같은 provider-neutral audio feature coverage가 충분해야 안정적으로 동작합니다. 하지만 실제 PMS/EMS 데이터에는 오디오 특성이 비어 있는 트랙이 많고, Spotify audio features를 canonical source로 가정할 수 없습니다.

따라서 비어 있는 값을 그대로 방치하면 모델 coverage가 낮아지고, 장르/아티스트 반복 중심의 추천에서 벗어나기 어렵습니다.

## 근거

- ReccoBeats는 인증 키 없이 사용할 수 있는 음악 추천/데이터베이스 API이며, 오디오 특성 조회와 업로드 기반 추출 API를 제공한다.
- ReccoBeats multiple audio features API는 `GET /v1/audio-features`를 제공한다.
- ReccoBeats rate limit은 존재하므로 batch/cache/retry 정책이 필요하다.
- Last.fm `track.getInfo`, `track.getTopTags`, `artist.getTopTags`, `user.getRecentTracks`는 metadata, tag, scrobble context를 제공하지만, numeric audio feature를 직접 제공하지 않는다.
- 따라서 Last.fm은 직접 측정 source가 아니라 inference evidence source로만 사용해야 한다.

## 결과

좋은 점:

- 기존 트랙과 새 트랙의 오디오 feature coverage를 높일 수 있다.
- Audio Taste Model이 사용자 취향의 mood/energy/tempo 축을 학습할 수 있다.
- LLM/검색 추론값을 쓰더라도 provenance와 confidence가 남아 운영 검토가 가능하다.
- 추천 모델이 `source_class`와 `confidence`를 사용해 추론값을 과신하지 않을 수 있다.

트레이드오프:

- DB schema와 admin 화면이 더 복잡해진다.
- completion job, rate limit, retry, evidence audit 운영이 필요하다.
- 추론값은 실제 오디오 분석보다 부정확할 수 있다.
- feature source별 품질 차이를 모델 학습과 평가에서 계속 관리해야 한다.

## 후속 작업

1. `audio_feature_source_class`, `audio_feature_confidence`, evidence table, completion job table 설계
2. PMS/EMS 공통 audio feature completion job 구현
3. ReccoBeats provider adapter 분리
4. Last.fm tag/evidence mapper 추가
5. Search + LLM inference JSON schema와 confidence gate 정의
6. `AudioTasteVectorModel` dataset/export/training/ranking 계약 추가
7. Hybrid reranker와 recommendation audit log 확장

## 관련 문서

- [AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md](../architecture/AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md)
- [AUDIO_FEATURE_PROVIDER_STRATEGY.md](../architecture/AUDIO_FEATURE_PROVIDER_STRATEGY.md)
- [PMS_TRACK_AUDIO_FEATURE_STORAGE.md](../api/PMS_TRACK_AUDIO_FEATURE_STORAGE.md)
