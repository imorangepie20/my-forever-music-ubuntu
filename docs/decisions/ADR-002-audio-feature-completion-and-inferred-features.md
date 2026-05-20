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
- confidence는 단일 통과/실패 기준이 아니라 계층으로 해석한다.

### 2026-05-20 추가 결정: LLM/search 추론값의 신뢰도 계층 저장

실측 또는 provider lookup이 실패한 트랙에 대해 LLM/search가 evidence 기반 numeric estimate를 반환하면 아래 계층으로 처리한다.

| Confidence | 저장 정책 | Job 상태 | 추천/학습 사용 |
|---|---|---|---|
| `>= 0.68` | `llm_search_inferred` snapshot으로 저장하고 `audio_features_filled=true` | `completed` | 일반 inferred feature로 사용하되 measured/provider보다 낮은 가중치 |
| `0.50 <= confidence < 0.68` | numeric estimate와 evidence를 저장하되 `audio_features_filled=false` 유지 | `unresolved` 또는 이후 `needs_review` | 새 Audio Taste Model에서 낮은 가중치의 weak signal로만 사용 |
| `< 0.50` 또는 evidence 없음 | audio feature snapshot은 저장하지 않고 evidence/사유만 남김 | `unresolved` | 학습/서빙 feature로 사용하지 않음 |

따라서 `audio_features_filled`는 “사용자 트랙에 신뢰 가능한 feature snapshot이 완성되었는가”를 의미하고, “참고 가능한 weak inference가 존재하는가”와 분리한다.

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
- confidence 기준 미달 값을 전부 버리지 않으므로, 실제 서비스 데이터에서 얻은 weak signal을 새 모델 실험에 활용할 수 있다.

트레이드오프:

- DB schema와 admin 화면이 더 복잡해진다.
- completion job, rate limit, retry, evidence audit 운영이 필요하다.
- 추론값은 실제 오디오 분석보다 부정확할 수 있다.
- feature source별 품질 차이를 모델 학습과 평가에서 계속 관리해야 한다.
- `audio_features_filled=false`인 트랙에도 일부 numeric estimate가 존재할 수 있으므로, downstream 모델은 filled 여부와 confidence를 반드시 함께 봐야 한다.

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
