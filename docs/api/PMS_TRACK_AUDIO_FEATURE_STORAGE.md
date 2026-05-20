# PMS Track Audio Feature Storage

작성일: `2026-05-06`

## 목적

이 문서는 사용자의 구독 스트리밍 플랫폼에서 playlist를 가져올 때, 각 track의 `오디오 특성 snapshot`을 어떤 기준으로 저장할지 정의합니다.

파일명은 과거 명명 규칙 때문에 그대로 두지만, 현재 정책은 `Spotify 전용`이 아니라 `provider-neutral`입니다.

핵심 원칙은 아래와 같습니다.

- `PMS import`와 `오디오 특성 보강`은 분리 가능한 단계로 본다
- track metadata는 먼저 저장한다
- 오디오 특성은 가능한 경우 즉시 보강하고, 실패하면 `unresolved` 또는 `unavailable` 상태로 남긴다
- 어떤 경우에도 provenance 없는 fake numeric value를 생성하지 않는다
- Last.fm tag, 검색 evidence, LLM 추론으로 채운 값은 측정값과 분리된 `inferred` estimate로만 저장하고 confidence/evidence를 함께 남긴다

상위 전략 문서는 [AUDIO_FEATURE_PROVIDER_STRATEGY.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/AUDIO_FEATURE_PROVIDER_STRATEGY.md) 를 따른다.

## 적용 범위

- 사용자 스트리밍 플랫폼 playlist import
- `PMS` track 저장
- provider-neutral audio feature enrichment
- PMS bootstrap과 이후 EMS/GMS 추천의 기준 데이터

## 저장 원칙

### 플레이리스트 import 시점 저장

사용자의 스트리밍 플랫폼 playlist를 가져올 때는 아래 순서를 따릅니다.

1. 플랫폼 원본 track metadata를 확보합니다.
2. `PMS import snapshot`과 `PMS user library`를 먼저 저장합니다.
3. 가능한 경우 같은 요청 안에서 오디오 특성을 즉시 조회합니다.
4. 조회 실패 시 `PMS`에 provenance 없는 가짜 값을 넣지 않고 `unresolved` 또는 `unavailable` 상태로 저장합니다.
5. 이후 동기 재시도 또는 비동기 backfill 작업으로 다시 보강합니다.
6. ReccoBeats lookup이 계속 실패하면 Last.fm tag evidence와 Search + LLM inference를 사용해 낮은 신뢰도의 estimate를 만들 수 있습니다. 이 경우 측정값이 아니라 `tag_inferred`/`llm_search_inferred` 계층으로 분리해야 합니다.

즉, 현재 목표 상태에서는 `playlist import 성공`과 `audio feature 완전성`을 같은 체크포인트로 묶지 않습니다.

### Provider-neutral 스냅샷 기준

현재 DB 쓰기 경로와 API 권장 필드는 `audio_*` / `audio_feature_*` 이름을 사용합니다.
기존 `spotify_*` 컬럼과 API 필드는 당분간 `legacy compatibility fields`로 남겨둡니다.

현재 저장 구조의 권장 필드는 아래와 같습니다.

- `audio_feature_track_id`
- `audio_analysis_url`
- `audio_track_href`
- `audio_track_uri`
- `audio_feature_type`
- `audio_duration_ms`
- `audio_key`
- `audio_mode`
- `audio_time_signature`
- `audio_acousticness`
- `audio_danceability`
- `audio_energy`
- `audio_instrumentalness`
- `audio_liveness`
- `audio_loudness`
- `audio_speechiness`
- `audio_tempo`
- `audio_valence`
- `audio_resolved_at`

관리 필드:

- `audio_feature_source`
- `audio_features_filled`
- `audio_feature_source_class` (계획)
- `audio_feature_confidence` (계획)
- `audio_feature_model_version` (계획)
- `audio_feature_completed_at` (계획)

Phase 1 coverage audit에서는 아직 DB 컬럼이 없어도 기존 `audio_feature_source` 문자열을 아래 source class로 해석합니다.

- `provider_lookup`: `reccobeats_*`, `spotify_api`, `spotify_match`
- `tag_inferred`: `lastfm_*`
- `llm_search_inferred`: `llm_*`, `web_search_*`, `*_search_inferred`
- `legacy_generated`: `fallback_generated` 등 과거 생성형 seed 값
- `unresolved`: `unresolved`, `unavailable`, blank
- `unknown`: 아직 분류 규칙이 없는 source

주의:

- legacy `spotify_*` 필드 이름이 남아 있어도 값의 provenance가 반드시 Spotify라는 뜻은 아닙니다.
- 새 저장/조회 경로는 `audio_*` provider-neutral 컬럼을 기준으로 합니다.

## 상태 기준

### source 값 기준

`audio_feature_source`는 현재 호환 스냅샷을 어떻게 확보했는지 표현합니다.

허용 예시:

- `spotify_api`
  - Spotify API에서 직접 가져온 값
- `reccobeats_lookup`
  - Spotify id 또는 provider lookup key로 ReccoBeats 조회
- `reccobeats_isrc_match`
  - ISRC 후보 중 title/artist/duration으로 재선택한 값
- `unavailable`
  - 조회를 시도했지만 현재 snapshot을 채우지 못함
- `unresolved`
  - 아직 조회를 시도하지 않았거나 후속 보강 대상
- `lastfm_track_tag_inferred`
  - Last.fm track tag evidence 기반 추론값
- `lastfm_artist_tag_inferred`
  - Last.fm artist tag evidence 기반 추론값
- `llm_search_inferred`
  - 검색 evidence와 LLM 구조화 추론 기반 estimate

### filled 값 기준

`audio_features_filled`는 현재 호환 스냅샷이 서비스 기준으로 충분히 채워졌는지 나타내는 플래그입니다.

현재 기준으로는 아래 조건을 만족할 때 `true`로 봅니다.

- 핵심 수치 필드가 모두 존재함
- `audio_resolved_at`이 존재함
- source가 명확함
- source class와 confidence가 존재함 (추론값 저장 이후 기준)

단, `time_signature`, `analysis_url`, provider 전용 href는 공급원에 따라 비어 있을 수 있으므로 장기 canonical model에서는 필수 필드에서 분리할 수 있습니다.

### 추론값 저장 기준

`Last.fm`, 검색, LLM으로 채운 값은 아래 조건을 모두 만족해야 합니다.

- `audio_feature_source_class`가 `tag_inferred` 또는 `llm_search_inferred`다.
- `audio_feature_confidence`가 threshold 이상이다.
- evidence table에 tag/source URL/요약/model version이 남는다.
- ReccoBeats 같은 provider lookup 값이 이후 들어오면 그 값을 우선한다.
- 추천 모델은 source class와 confidence를 feature weight로 사용한다.

상세 계획은 [AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md](../architecture/AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md) 와 [ADR-002](../decisions/ADR-002-audio-feature-completion-and-inferred-features.md) 를 따른다.

## 현재 구현 메모

현재 코드 기준 반영 내용:

- `pms_track`, `pms_imported_track`, `pms_user_track` 테이블은 provider-neutral `audio_*` 컬럼을 기준으로 audio feature snapshot을 저장합니다
- Flyway 마이그레이션: [V3__add_spotify_audio_features_to_pms_track.sql](/Users/woosungjo/music-space/my-forever-music/services/api/src/main/resources/db/migration/V3__add_spotify_audio_features_to_pms_track.sql)
- provider-neutral 컬럼 추가 마이그레이션: [V18__add_provider_neutral_audio_feature_columns.sql](/Users/woosungjo/music-space/my-forever-music/services/api/src/main/resources/db/migration/V18__add_provider_neutral_audio_feature_columns.sql)
- JPA 모델: [PmsTrackAudioFeatures.java](/Users/woosungjo/music-space/my-forever-music/services/api/src/main/java/io/myforevermusic/api/modules/pms/infrastructure/persistence/PmsTrackAudioFeatures.java)
  - class name은 legacy 호환 때문에 유지하지만 컬럼 매핑은 `audio_*`를 사용합니다
- 트랙 엔터티: [PmsCatalogTrackEntity.java](/Users/woosungjo/music-space/my-forever-music/services/api/src/main/java/io/myforevermusic/api/modules/pms/infrastructure/persistence/PmsCatalogTrackEntity.java)
- PMS bootstrap 응답은 track별 `audio_features_filled`, `audio_feature_source`를 권장 필드로 내려주고 legacy `spotify_*` alias도 함께 유지합니다
- 현재 Spotify/TIDAL import provider는 audio-features 조회가 실패해도 import를 계속 진행하고, `source=unavailable`, `filled=false` 스냅샷을 저장합니다
- 이 동작은 새 provider-neutral 정책과 더 가깝고, 과거 문서의 `import 중단` 설명은 더 이상 기준이 아닙니다

## 다음 연결 지점

1. `audio feature enrichment provider` 추상화 추가
2. ReccoBeats lookup client와 batch enrichment 경로 연결
3. legacy `spotify_*` API alias 제거 시점 정의
4. 오래된 DB 컬럼 정리용 후속 migration 설계
