# services/api

Spring Boot 메인 API 서비스 폴더입니다.

## 목표

- 인증, 사용자 설정, 플레이리스트, 트랙, 플랫폼 연동 담당
- PMS / EMS / GMS 도메인 오케스트레이션 담당
- `apps/web`, `apps/desktop`가 공통으로 사용하는 백엔드 API 제공
- 사용자가 구독 중인 스트리밍 플랫폼과 연결하고 플레이리스트를 PMS로 적재
- 플랫폼을 옮겨도 유지되는 사용자 소유 playlist/taste library 관리
- 플랫폼 연동과 PMS user library 저장 이후 사용자별 음악 학습 모델 입력 데이터 제공
- 추천 평가, 저장, 재생 행동을 PMS 학습 데이터로 환류
- EMS 수집 데이터와 GMS 평가 결과가 다시 PMS 학습 데이터로 이어지는 환류 담당

## 권장 스택

- `Spring Boot 3.5.x`
- `Java 21`
- `Gradle`
- `Spring Web`
- `Spring Security`
- `Spring Data JPA`
- `Bean Validation`
- `Actuator`
- `Flyway`

## 권장 구조

```text
services/api/
├── build.gradle.kts
├── settings.gradle.kts
├── src/
│   ├── main/
│   │   ├── java/
│   │   └── resources/
│   └── test/
│       ├── java/
│       └── resources/
└── README.md
```

## 구현 가이드

1. API 계약은 OpenAPI 기준으로 문서화
2. 도메인은 `auth`, `user`, `pms`, `ems`, `gms`, `platform` 기준으로 분리
3. 외부 AI 호출은 직접 모델을 넣지 말고 `services/ai` 연동 계층으로 분리
4. 데스크탑 앱을 고려해 세션보다 토큰/클라이언트 독립 구조를 우선 검토
5. 플랫폼 원본 데이터와 보강된 오디오 특성 데이터는 분리해 저장하는 방향을 우선 검토
6. 외부 플랫폼 playlist import 결과는 가능한 한 `PMS user library` canonical model로 승격
7. 사용자 평가, 저장, 재생, 스킵, playlist 추가 같은 행동 이벤트는 추천 모델 입력으로 남길 수 있게 설계

## 현재 스캐폴드에 포함된 것

- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`
- `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`
- Spring Boot 메인 애플리케이션 클래스
- 기본 보안 설정
- OpenAPI 설정
- 샘플 엔드포인트: `/api/v1/system/info`
- 회원가입 엔드포인트: `POST /api/v1/auth/register`
- 로그인 엔드포인트: `POST /api/v1/auth/login`
- 플랫폼 카탈로그 엔드포인트: `GET /api/v1/platforms/catalog`
- 플랫폼 연결 bootstrap 엔드포인트: `GET /api/v1/platforms/connections/bootstrap`
- 플랫폼 연결 명령 엔드포인트: `POST /api/v1/platforms/connections/connect`, `POST /api/v1/platforms/connections/disconnect`
- Last.fm signal preview 엔드포인트: `GET /api/v1/platforms/lastfm/preview`
- Last.fm signal profile 저장 엔드포인트: `POST /api/v1/platforms/lastfm/profile`
- Last.fm scrobble bootstrap/sync 엔드포인트: `GET /api/v1/platforms/lastfm/scrobbles/bootstrap`, `POST /api/v1/platforms/lastfm/scrobbles/sync`
- 플랫폼 OAuth 엔드포인트: `POST /api/v1/platforms/oauth/start`, `POST /api/v1/platforms/oauth/complete`
- PMS import bootstrap 엔드포인트: `GET /api/v1/pms/import/bootstrap`
- PMS import 명령 엔드포인트: `POST /api/v1/pms/import/playlists`
- PMS workspace bootstrap 엔드포인트: `GET /api/v1/pms/workspace/bootstrap`
- EMS workspace analysis 엔드포인트: `POST /api/v1/ems/workspace/analysis`
- EMS provider search 엔드포인트: `POST /api/v1/ems/collection/search` (`search_pool` 소스로 EMS 저장)
- EMS search playlist track detail 엔드포인트: `GET /api/v1/ems/collection/search/playlists/{platformId}/{externalPlaylistId}/tracks` (`search_pool` playlist-track 링크 저장)
- GMS AI preview 브리지 엔드포인트: `POST /api/v1/gms/recommendations/preview`
- GMS feedback 저장 엔드포인트: `POST /api/v1/gms/recommendations/feedback`
- PMS personal playlist 엔드포인트: `GET /api/v1/pms/personal-playlists/bootstrap`, `POST /api/v1/pms/personal-playlists`, `POST /api/v1/pms/personal-playlists/tracks`
- PMS bootstrap용 JPA 엔터티 / 리포지토리 / Flyway 마이그레이션
- Actuator 설정: `/actuator/health`
- Swagger UI 경로: `/docs`
- OpenAPI 문서 경로: `/openapi`

이 스캐폴드는 아직 핵심 서비스의 전체 구현이 아니라 `PMS bootstrap -> EMS analysis -> GMS preview` 최소 검증 버전이다. 장기적으로는 플랫폼 OAuth, 플레이리스트 동기화, 사용자 제작 playlist, 행동 이벤트 적재, GMS 평가 저장까지 확장한다.

현재는 여기에 `회원가입 -> Spotify 기본 플랫폼 선택 -> 플랫폼 연결 상태 조회 -> 실제 Spotify OAuth/callback -> PMS playlist import -> EMS 다음 단계 안내`까지 추가되어, 서비스 구현을 온보딩부터 순차적으로 확장할 수 있는 상태다.

이제 `POST /api/v1/auth/login`도 추가되어, 로컬 시험 서비스 중 기존 계정으로 다시 로그인하고 현재 온보딩 단계(`/platforms` 또는 `/pms`)를 복원할 수 있다.

또한 현재 `Last.fm`은 `PMS import` 플랫폼이 아니라 `장기 청취 신호 플랫폼`으로 다뤄진다. 그래서 `GET /api/v1/platforms/lastfm/preview`는 공개 사용자명 기준으로 recent scrobble, top artist, top track을 읽어와 `EMS/GMS` 장기 취향 preview를 제공한다.

이제 `POST /api/v1/platforms/lastfm/profile`도 추가되어, preview에 사용한 공개 사용자명을 계정에 저장하고 `EMS analysis`가 이 저장값을 바탕으로 `top artist` affinity를 자동 blend 할 수 있다.

이후 `GET/POST /api/v1/platforms/lastfm/scrobbles/*`도 추가되어, 최근 scrobble snapshot을 계정 단위로 저장하고 다시 `/platforms`에서 확인할 수 있다.

같은 저장값은 `POST /api/v1/ems/workspace/analysis`와 `POST /api/v1/gms/recommendations/preview`에도 반영된다. 현재는 저장된 `Last.fm scrobble snapshot`이 있으면 그 최근 artist recurrence를 먼저 사용하고, snapshot이 비어 있으면 live `Last.fm top artist` 조회로 fallback 한다.

현재 `pms_track`는 기본 메타데이터와 legacy `spotify_*` 오디오 특성 스냅샷 필드를 함께 저장할 수 있도록 확장되어 있다. 현재 기본 구현은 playlist metadata import를 먼저 저장하고, 오디오 특성은 `ReccoBeats` 조회형 API로 보강한다.

또한 현재는 `platform credential store`와 `platform playlist provider` 계층이 추가되어, 실제 외부 플랫폼 연동을 import 흐름 위에서 provider별로 처리할 수 있다.

추가로 `platform authorization code exchange client` 계층도 들어가 있어서, Spotify PKCE draft가 켜져 있으면 callback의 authorization code를 실제 token endpoint와 교환하고 그 결과를 credential로 저장한다.

TIDAL은 Spotify 다음 provider로 고정되어 있으며, 현재는 TIDAL OAuth 2.1 + PKCE token exchange/refresh client 기반만 추가되어 있다. 실제 TIDAL playlist provider와 PMS import 검증이 끝나기 전까지 사용자 온보딩과 import 후보에는 노출하지 않는다.

현재 `spotify` provider는 실제 사용자 token으로 `GET /me/playlists`, `GET /playlists/{playlist_id}/items`를 호출하고, 트랙별 오디오 특성은 `ReccoBeats GET /v1/audio-features`로 보강한다. 실패하거나 누락된 항목이 있으면 가짜 오디오 특성을 만들지 않고 `unavailable` placeholder로 저장한다.

현재 이 import 경로는 단순 텍스트 입력값만 저장하지 않는다. playlist cover image, playlist external URL/URI, track album title, album image, track external URL/URI, preview URL까지 같이 저장한다.

또한 현재 `spotify` credential은 만료 60초 전부터 refresh token 기반 자동 갱신을 시도한다. Spotify refresh 응답에 새 refresh token이 없으면 기존 refresh token을 유지한다.

refresh 이후에도 usable credential이 확보되지 않으면 `platform connection bootstrap`과 `pms import bootstrap`은 `reconnect_required` 상태를 내려준다. 이때 `POST /api/v1/pms/import/playlists`는 `409 conflict`와 `platform_reconnect_required` 코드로 응답하므로, 웹은 사용자를 `/platforms` 재연결 흐름으로 돌려보내면 된다.

추가로 `PMS import` 저장소는 이제 프로필별로 나뉜다. `local`에서는 `InMemoryPmsPlaylistImportStore`를 사용하고, `!local`에서는 `JpaPmsPlaylistImportStore`가 `pms_imported_playlist`, `pms_imported_track`, `pms_imported_playlist_track` 테이블에 import 결과를 영속 저장한다.

이 import 직후 정식 사용자 라이브러리 sync도 함께 수행한다. `local`에서는 `InMemoryPmsUserLibraryStore`, `!local`에서는 `JpaPmsUserLibraryStore`가 `pms_user_playlist`, `pms_user_track`, `pms_user_playlist_track` 테이블에 동기화 결과를 유지한다.

또한 `GET /api/v1/pms/workspace/bootstrap`는 현재 raw import snapshot보다 정식 `PMS user library`를 우선 사용한다.

추가로 `GET /api/v1/pms/workspace/bootstrap`는 이제 optional `playlist_id` query parameter를 받으며, 해당 값이 있으면 가능한 경우 그 playlist 기준으로 library/track/artwork 컨텍스트를 다시 투영한다.

현재 `POST /api/v1/gms/recommendations/preview`는 AI preview 결과를 그대로 보여주는 데서 멈추지 않고, 가능하면 `PMS user library`에서 실제 playable track으로 재매핑한다. 그래서 웹앱은 `GMS` 카드에서도 실제 album art와 platform playback target을 표시할 수 있다.

상세 저장 기준은 [PMS_TRACK_AUDIO_FEATURE_STORAGE.md](/Users/woosungjo/music-space/my-forever-music/docs/api/PMS_TRACK_AUDIO_FEATURE_STORAGE.md) 를 본다.

API 계약 문서 인덱스는 [docs/api/README.md](/Users/woosungjo/music-space/my-forever-music/docs/api/README.md) 를 먼저 본다.

## 실행 전제

- `Java 21`
- `Gradle wrapper`
- 첫 wrapper 생성이 필요한 경우에만 `Gradle 8.14+`

현재 `local` 프로필은 브라우저 확인과 API 연결 점검을 빠르게 할 수 있도록 `DataSource`, `JPA`, `Flyway` 자동 구성을 잠시 제외한다.  
즉, 현재 스캐폴드 상태에서는 `PostgreSQL` 없이도 `./gradlew bootRun`이 가능하다.

또한 macOS 로컬 개발에서는 `8080` 포트가 Docker나 다른 앱과 자주 충돌하므로, `local` 프로필 기본 포트는 `8081`을 사용한다. 운영/서버 기준 기본 포트는 여전히 `8080`이다.

이 상태에서도 `EMS workspace analysis`는 동작한다. 다만 `local`에서는 요청 컨텍스트 중심으로 분석하고, `database` 프로필에서는 `PMS` 카탈로그와 사용자 라이브러리 신호를 함께 반영한다.

반대로 `database` 같은 DB 활성 프로필로 실행하면 Flyway가 `pms_*` demo bootstrap 테이블과 `pms_imported_*`, `pms_user_*` 영속 테이블을 함께 만들고, 가져온 플레이리스트와 정식 사용자 라이브러리를 DB에 유지한다.

## 참고 환경 변수

- `API_PORT`
- `DB_HOST`
- `DB_PORT`
- `DB_NAME`
- `DB_USERNAME`
- `DB_PASSWORD`
- `APP_VERSION`
- `AI_SERVICE_BASE_URL`
- `AI_RECOMMENDATION_PREVIEW_PATH`
- `AI_AUDIO_FEATURE_INFERENCE_PATH`
- `AI_SASREC_TRAINING_PATH`
- `AI_SASREC_RANKING_PATH`
- `AI_SASREC_LATEST_MODEL_PATH`
- `AI_SASREC_MODEL_VERSION`
- `APP_RECOMMENDATION_METADATA_APPLY_ACCEPTED_ISRCS_ENABLED`
- `APP_RECOMMENDATION_METADATA_APPLY_ACCEPTED_ISRCS_ADMIN_USER_ID`
- `APP_RECOMMENDATION_METADATA_APPLY_ACCEPTED_ISRCS_BATCH_LIMIT`
- `APP_RECOMMENDATION_METADATA_WIKIDATA_USER_AGENT`
- `APP_RECOMMENDATION_METADATA_DISCOGS_USER_AGENT`
- `DISCOGS_TOKEN` 또는 `APP_RECOMMENDATION_METADATA_DISCOGS_TOKEN`
- `RECCOBEATS_ENABLED`
- `RECCOBEATS_API_BASE_URL`
- `AUDIO_FEATURES_COMPLETION_SCHEDULER_ENABLED`
- `AUDIO_FEATURES_COMPLETION_SCHEDULER_FIXED_DELAY_MS`
- `AUDIO_FEATURES_COMPLETION_SCHEDULER_INITIAL_DELAY_MS`
- `AUDIO_FEATURES_COMPLETION_SCHEDULER_BATCH_LIMIT`
- `AUDIO_FEATURES_COMPLETION_SCHEDULER_WORKER_ID`
- `AUDIO_FEATURES_COMPLETION_LASTFM_MIN_CONFIDENCE`
- `SPOTIFY_OAUTH_ENABLED`
- `SPOTIFY_CLIENT_ID`
- `SPOTIFY_CLIENT_SECRET`
- `SPOTIFY_REDIRECT_URI`
- `SPOTIFY_AUTHORIZATION_URI`
- `SPOTIFY_TOKEN_URI`
- `SPOTIFY_API_BASE_URI`
- `TIDAL_OAUTH_ENABLED`
- `TIDAL_CLIENT_ID`
- `TIDAL_CLIENT_SECRET`
- `TIDAL_REDIRECT_URI`
- `TIDAL_AUTHORIZATION_URI`
- `TIDAL_TOKEN_URI`
- `TIDAL_API_BASE_URI`
- `TIDAL_COUNTRY_CODE`
- `TIDAL_SCOPES`
- `LASTFM_ENABLED`
- `LASTFM_API_KEY`
- `LASTFM_SHARED_SECRET`
- `LASTFM_API_ROOT`
- `YOUTUBE_DATA_API_KEY`

## 실행 예시

```bash
./gradlew bootRun
```

`bootRun`은 `services/api/.env.local`이 있으면 자동으로 읽는다. 같은 키를 셸에서 이미 export 했다면 셸 환경변수가 우선하지만, 빈 문자열로 export 된 경우에는 `.env.local` 값을 사용한다.

AI 서비스와 함께 로컬 실행 예시:

```bash
AI_SERVICE_BASE_URL=http://localhost:8000 ./gradlew bootRun
```

학습된 SASRec MVP artifact를 GMS preview 재정렬에 반영하려면 AI service의 `/v1/recommendations/datasets/sasrec/train` 응답에서 받은 `model_version`을 함께 지정할 수 있다. 이 값이 비어 있으면 API는 AI service의 `/v1/recommendations/datasets/sasrec/models/latest`에서 해당 사용자 최신 artifact를 조회하고, 찾지 못한 경우 기존 PMS playable 후보 정렬만 사용한다.

```bash
AI_SERVICE_BASE_URL=http://localhost:8000 \
AI_SASREC_MODEL_VERSION=sasrec-your-model-version \
./gradlew bootRun
```

사용자 행동/추천 snapshot export payload를 바로 AI SASRec 학습으로 넘기는 운영 경계:

```bash
curl -X POST "http://localhost:8081/api/v1/recommendations/datasets/users/{userId}/sasrec/train?event_limit=300&snapshot_limit=200&epochs=30&persist_artifact=true"
```

export payload의 `summary.dataset_version`과 `summary.dataset_fingerprint`는 동일 학습 입력을 추적하기 위한 snapshot id 역할을 한다. SASRec artifact metadata, model registry 응답, auto-train log에도 같은 fingerprint가 남는다.

관리자 계정으로 다른 사용자 모델을 학습/promote 하려면 admin endpoint에 `target_user_id`를 함께 넘긴다.

```bash
curl -X POST "http://localhost:8081/api/v1/recommendations/admin/sasrec/models/auto-train?user_id={adminUserId}&target_user_id={targetUserId}&event_limit=300&snapshot_limit=200&epochs=30&persist_artifact=true"
```

PostgreSQL을 붙여 PMS bootstrap을 DB 기반으로 확인하려면:

```bash
cd /Users/woosungjo/music-space/my-forever-music/infra/docker
docker compose -f docker-compose.local-db.yml up -d

cd /Users/woosungjo/music-space/my-forever-music/services/api
SPRING_PROFILES_ACTIVE=database \
DB_PORT=5433 \
AI_SERVICE_BASE_URL=http://localhost:8000 \
./gradlew bootRun
```

> **참고**: 로컬 개발 환경에서 포트 충돌을 방지하기 위해 PostgreSQL은 `5433` 포트를 사용합니다. `application-database.yml`에서 기본값이 설정되어 있으므로 `DB_PORT=5433`만 지정하면 됩니다.

macOS에서는 `docker compose` 실행 전에 `Docker Desktop`이 떠 있어야 한다.

관련 파일:

- [docker-compose.local-db.yml](/Users/woosungjo/music-space/my-forever-music/infra/docker/docker-compose.local-db.yml)
- [env.local.example](/Users/woosungjo/music-space/my-forever-music/infra/docker/env.local.example)

Homebrew 기반 macOS에서 `java`가 바로 잡히지 않으면:

```bash
export PATH="/opt/homebrew/opt/openjdk@21/bin:/opt/homebrew/bin:$PATH"
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
AI_SERVICE_BASE_URL=http://localhost:8000 ./gradlew bootRun
```

Spotify PKCE draft까지 같이 확인하려면:

```bash
export SPOTIFY_OAUTH_ENABLED=true
export SPOTIFY_CLIENT_ID=your_spotify_client_id
export SPOTIFY_CLIENT_SECRET=your_spotify_client_secret
export SPOTIFY_REDIRECT_URI=https://your-domain.example.com/platforms/oauth/callback
AI_SERVICE_BASE_URL=http://localhost:8000 ./gradlew bootRun
```

이 설정이 있으면 `/api/v1/platforms/oauth/start`의 Spotify 응답은 external authorization URL을 내려준다. OAuth 설정이 없으면 내부 승인 화면이나 mock credential로 대체하지 않고 실패한다.

TIDAL OAuth 설정값도 `application.yml`에 준비되어 있지만, 현재 catalog에서는 `pms_import_supported=false`이므로 실제 TIDAL playlist import provider가 완성될 때까지 `/api/v1/platforms/oauth/start` 대상이 아니다.

프로젝트에 맞춘 상세 설정과 실행 스크립트는 [SPOTIFY_OAUTH_SETUP.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/SPOTIFY_OAUTH_SETUP.md) 를 본다.

Last.fm public preview를 같이 확인하려면:

```bash
export LASTFM_ENABLED=true
export LASTFM_API_KEY=your_lastfm_api_key
AI_SERVICE_BASE_URL=http://localhost:8000 ./gradlew bootRun
```

이 설정이 있으면 `/api/v1/platforms/lastfm/preview?username=public-user-name&period=1month` 형태로 공개 청취 신호 preview를 확인할 수 있다.

YouTube playback fallback resolver까지 같이 확인하려면:

```bash
export YOUTUBE_DATA_API_KEY=your_youtube_data_api_key
AI_SERVICE_BASE_URL=http://localhost:8000 ./gradlew bootRun
```

이 설정이 없으면 `/api/v1/platforms/playback/youtube/resolve-track` 는 `412 Precondition Failed` 로 `YOUTUBE_DATA_API_KEY is required...` 를 반환한다.

저장된 profile을 기반으로 scrobble snapshot까지 적재하려면:

```bash
curl -X POST http://127.0.0.1:8081/api/v1/platforms/lastfm/scrobbles/sync \
  -H 'Content-Type: application/json' \
  -d '{
    "user_id": "user-{uuid}",
    "limit": 40
  }'
```

예시 호출:

```bash
curl -X POST http://127.0.0.1:8081/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{
    "display_name": "Forever Listener",
    "email": "listener@example.com",
    "password": "music2026",
    "preferred_platform_id": "spotify",
    "marketing_opt_in": true,
    "accepted_terms": true,
    "accepted_privacy_policy": true
  }'
```

```bash
curl -X POST http://127.0.0.1:8081/api/v1/gms/recommendations/preview \
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

이 엔드포인트는 현재 `GMS` preview 브리지이므로 `mode`는 생략하거나 `gms`로 고정해 사용하는 것을 기준으로 한다.

PMS bootstrap은 현재 아래 세 모드로 동작한다.

- `imported-user`: 현재 사용자 기준 정식 PMS user library
- `imported-snapshot`: 정식 라이브러리 sync 전 raw import 복구 소스
- `user-owned-database`: 같은 `user_id` 소유의 `pms_playlist`, `pms_track`, `pms_playlist_track` 기반 bootstrap
- `empty-library`: import 전 빈 PMS workspace

## Wrapper 상태

- 현재 `gradle-wrapper.properties`, `gradlew`, `gradlew.bat`, `gradle-wrapper.jar`가 생성된 상태다
- 그래서 이후에는 `system Gradle` 없이도 wrapper 기준 실행이 가능하다
- 첫 설정을 다시 만들 때만 아래 명령이 필요하다

```bash
./gradlew wrapper
```
