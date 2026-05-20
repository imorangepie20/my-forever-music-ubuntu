# Audio Feature Provider Strategy

작성일: `2026-05-06`

이 문서는 `my-forever-music`이 트랙 오디오 특성을 어떤 외부 공급원에서 확보하고, PMS에 어떤 원칙으로 저장할지 정리하는 구현 전략 문서입니다.

## 1. 왜 전략을 바꿨는가

`2024-11-27` Spotify 공식 공지 이후, 신규 Web API use case와 Development Mode 앱은 `Audio Features`, `Audio Analysis`, `Recommendations`를 더 이상 안정적으로 사용할 수 없습니다.

추가로 `2025-05-15`부터 Spotify Extended Quota 신청은 사실상 조직 대상만 허용되고, `2026-02-11`과 `2026-03-09`의 Development Mode 변경으로 개인 개발 환경 제약이 더 명확해졌습니다.

즉, 개인 개발자와 MacBook 로컬 시험 서비스 기준에서는 아래 가정이 더 이상 맞지 않습니다.

- `Spotify 오디오 특성을 PMS import의 필수 동기 단계로 둔다`
- `Spotify 오디오 특성을 우리 서비스의 canonical audio feature source로 둔다`

## 2. 현재 서비스 결정

- `Spotify`는 계속 중요한 `playlist import / playback / metadata` source다.
- 하지만 `Spotify`는 더 이상 개인 개발 환경의 `주 오디오 특성 공급원`으로 가정하지 않는다.
- 트랙 분석 기준은 `provider-neutral audio feature model`로 둔다.
- 현재 외부 조회형 오디오 특성 공급원은 `ReccoBeats`를 1차 검토 대상으로 둔다.
- 공급원을 바꾸더라도 `가짜 측정값 생성 금지` 원칙은 유지한다.
- 다만 `ADR-002` 이후에는 Last.fm tag, 검색 evidence, LLM 추론을 `tag_inferred` 또는 `llm_search_inferred` source class로 분리 저장할 수 있다. 이 값은 측정값이나 provider lookup 값처럼 취급하지 않고 confidence/evidence/model version을 함께 남긴다.

## 3. import와 보강의 기본 흐름

1. 사용자의 플랫폼 playlist와 track metadata를 먼저 가져온다.
2. PMS import와 `PMS user library` 저장은 오디오 특성 유무와 분리한다.
3. 가능한 경우 같은 요청 안에서 오디오 특성을 즉시 보강한다.
4. 즉시 보강에 실패하면 트랙은 `unresolved` 또는 `unavailable` 상태로 저장한다.
5. 이후 동기 재시도 또는 비동기 backfill job으로 다시 보강한다.
6. 어떤 단계에서도 provenance 없는 임의 추정값이나 fake feature를 저장하지 않는다.
7. Last.fm/검색/LLM 기반 추론값은 `audio_feature_source_class`, `audio_feature_confidence`, evidence audit trail을 남기는 경우에만 모델 입력용 estimate로 저장한다.

## 4. 현재 코드/스키마와의 호환성 규칙

현재 DB와 API 계약은 아직 `spotify_*` 이름을 많이 사용한다. 이 이름은 당분간 `역사적 호환성 레이어`로 해석한다.

예:

- `spotify_track_id`
- `spotify_audio_feature_source`
- `spotify_audio_features_filled`
- `complete_spotify_audio_feature_track_count`

현재 해석 원칙:

- 필드 이름이 `spotify_*`여도 실제 값의 공급원이 반드시 Spotify일 필요는 없다.
- `spotify_audio_feature_source`에는 `spotify_api`, `reccobeats_lookup`, `reccobeats_isrc_match`, `unavailable`, `unresolved` 같은 값을 둘 수 있다.
- `spotify_audio_features_filled`는 `Spotify provenance`가 아니라 `현재 호환 스냅샷이 충분히 채워졌는지`를 뜻한다.
- provider-neutral 스키마로 완전히 옮기기 전까지는 기존 필드명을 유지하되, 문서와 UI는 이를 `legacy compatibility fields`로 설명한다.

## 5. 오디오 특성 완전성 기준

현재 서비스에서 중요하게 보는 핵심 수치는 아래다.

- `duration_ms`
- `key`
- `mode`
- `acousticness`
- `danceability`
- `energy`
- `instrumentalness`
- `liveness`
- `loudness`
- `speechiness`
- `tempo`
- `valence`
- `resolved_at`

주의:

- `time_signature`, `analysis_url`, provider 전용 href는 모든 공급원이 주지 않을 수 있다.
- 따라서 장기 canonical model은 `공통 분모 필드` 중심이어야 한다.

## 6. ReccoBeats 적용 해석

ReccoBeats는 현재 기준으로 아래 장점이 있다.

- Spotify track id를 이용한 조회형 batch lookup이 가능하다
- 별도 인증 키 없이 실험 가능하다
- `acousticness`, `danceability`, `energy`, `instrumentalness`, `key`, `liveness`, `loudness`, `mode`, `speechiness`, `tempo`, `valence`를 제공한다

제약도 있다.

- `GET /v1/track/:id/audio-features`는 Spotify id가 아니라 ReccoBeats UUID를 기대한다
- ISRC lookup은 중복 결과가 나올 수 있다
- provider별 응답 모델 차이를 그대로 PMS canonical model에 넣을 수는 없다

상세 확인 결과는 [streaming-platforms-api/reccobeats.md](/Users/woosungjo/music-space/my-forever-music/docs/streaming-platforms-api/reccobeats.md) 를 본다.

## 7. Completion 및 하이브리드 모델 확장

새 개인화 모델 개발을 위해 오디오 특성 completion pipeline을 별도 정책으로 확장한다.

우선순위:

1. `ReccoBeats` lookup
2. `Last.fm` tag/listening context evidence
3. Search + LLM inference

추론값은 측정값으로 승격하지 않으며, 새 `AudioTasteVectorModel`과 hybrid reranker는 source class와 confidence를 함께 사용한다.

상세 계획은 [AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md](AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md) 와 [ADR-002](../decisions/ADR-002-audio-feature-completion-and-inferred-features.md) 를 따른다.

## 8. 다음 구현 과제

1. `services/api` import 파이프라인에서 `audio feature enrichment provider` 추상화 분리
2. ReccoBeats lookup client 추가
3. 현재 `spotify_*` 저장 구조를 provider-neutral 이름으로 옮기는 migration 설계
4. `PMS bootstrap`, `playlist import response`, `platform catalog`의 legacy field 이름 교체 일정 수립
5. unresolved track 재보강용 batch/job 설계

## 9. 공식 참고

- Spotify Web API changes:
  https://developer.spotify.com/blog/2024-11-27-changes-to-the-web-api
- Spotify Quota modes:
  https://developer.spotify.com/documentation/web-api/concepts/quota-modes
- Spotify February 2026 migration guide:
  https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide
- ReccoBeats reference:
  [reccobeats.md](/Users/woosungjo/music-space/my-forever-music/docs/streaming-platforms-api/reccobeats.md)
