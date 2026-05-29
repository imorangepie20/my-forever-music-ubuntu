# Project Guide

작성일: `2026-04-29`

이 문서는 `my-forever-music`의 가장 중요한 진입 문서입니다.  
새로운 세션, 새로운 작업자, 새로운 AI 에이전트는 이 문서를 먼저 읽고 현재 프로젝트 방향을 파악해야 합니다.

## 1. 프로젝트 한 줄 요약

`my-forever-music`은 기존 MusicSpace 개념을 바탕으로 다시 만드는 프로젝트이며, 먼저 웹앱을 완성한 뒤 같은 도메인과 UI 자산을 재사용해 Windows 데스크탑 앱까지 확장합니다.

핵심 서비스 원문 정의는 [PROJECT_KEY_SERVICE.md](/Users/woosungjo/music-space/my-forever-music/docs/PROJECT_KEY_SERVICE.md) 를 기준으로 봅니다.

사용자가 이 서비스를 왜 반복적으로 쓰는지에 대한 제품 관점은 [product/USER_MUSIC_HOME_VISION.md](/Users/woosungjo/music-space/my-forever-music/docs/product/USER_MUSIC_HOME_VISION.md) 를 함께 봅니다.

PMS 중심 감상 경험과 EMS 공개 playlist 풀 수집/노출 기준은 [product/MUSIC_DISCOVERY_AND_LISTENING_UX.md](/Users/woosungjo/music-space/my-forever-music/docs/product/MUSIC_DISCOVERY_AND_LISTENING_UX.md) 를 따릅니다.

사용자 플로우에는 mock data나 sandbox provider를 기본값으로 노출하지 않습니다. 실제 구현 기준은 [architecture/REAL_IMPLEMENTATION_POLICY.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/REAL_IMPLEMENTATION_POLICY.md) 를 따릅니다.

오류는 모든 프로젝트 영역에서 우회, 회피, 임시 처리, 에러 숨김으로 넘기지 않습니다. 실패한 경계와 근본 원인을 확인하고 수정하는 기준 역시 [architecture/REAL_IMPLEMENTATION_POLICY.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/REAL_IMPLEMENTATION_POLICY.md) 를 따릅니다.

현재 실행 전략은 [architecture/MACBOOK_LOCAL_FIRST_PLAN.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/MACBOOK_LOCAL_FIRST_PLAN.md) 을 따른다. 즉, 먼저 MacBook 로컬에서 실서비스 기능을 구현하고 시험한 뒤 Ubuntu 서버로 이전한다.

## 1-1. 서비스 핵심 정의

이 프로젝트가 만들려는 실제 서비스는 아래와 같습니다.

- 사용자가 구독 중인 스트리밍 플랫폼을 선택하고 계정을 연결한다
- 해당 플랫폼의 플레이리스트를 가져와 `PMS`에 저장한다
- `PMS`는 특정 플랫폼에 묶이지 않는 사용자 소유 음악 취향 라이브러리다
- 플랫폼을 바꾸더라도 사용자의 playlist, track, 평가, 취향 모델은 계속 유지된다
- 각 트랙의 오디오 특성은 `provider-neutral` 전략으로 보강한다
- 오디오 특성을 직접 확보하지 못한 트랙은 가짜 특성으로 채우지 않고 `unresolved` 상태로 남기며 후속 보강/재시도 정책으로 처리한다
- 사용자 플레이리스트와 행동 데이터를 바탕으로 개인별 취향 모델을 점진적으로 학습한다
- `EMS`는 외부 플랫폼의 공개 플레이리스트와 트렌딩 트랙을 수집하는 외부 탐색 공간이다
- `GMS`는 사용자 모델이 통과시킨 추천 결과가 모이는 개인화 게이트웨이 공간이다
- 사용자가 `GMS` 결과를 평가하면 다시 `PMS` 학습 데이터로 환류된다
- 사용자는 추천 결과를 저장하고 자기 playlist를 만들며 사이트 안에서 음악을 감상한다
- 어느 페이지에서든 음악 재생이 가능하고 페이지 이동 사이에도 플레이어 상태가 유지된다

## 2. 현재 확정된 큰 방향

- 프론트엔드는 `React + TypeScript + Vite`
- 메인 API는 `Spring Boot 3.5.x + Java 21 + Gradle`
- AI/추천 서비스는 `FastAPI`
- 데이터베이스는 `PostgreSQL`
- 마이그레이션은 `Flyway`
- Windows 데스크탑 앱은 후속 단계에서 `Tauri 2`
- API 계약 문서 기준은 `OpenAPI`
- 현재 1차 구현과 시험 서비스 환경은 `MacBook 로컬`
- Ubuntu 서버는 기능 안정화 후 이전하는 2차 단계

## 3. 왜 이렇게 정했는가

- 레거시 분석 결과, 도메인 구조의 강점은 `PMS / EMS / GMS` 흐름에 있었음
- 메인 백엔드는 Node보다 Spring 구조가 현재 목표와 더 자연스럽게 맞음
- 웹과 데스크탑이 같은 API와 도메인 모델을 공유해야 함
- 추천/AI는 메인 API와 분리된 별도 서비스가 유지보수에 유리함
- 핵심 서비스 문서 기준으로도 `플랫폼 연동 / 취향 모델 / EMS-GMS 환류`는 장기적으로 분리된 서비스 구조가 더 적합함

자세한 근거는 아래 문서를 본다.

- [TECH_STACK.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/TECH_STACK.md)
- [ADR-001-backend-stack.md](/Users/woosungjo/music-space/my-forever-music/docs/decisions/ADR-001-backend-stack.md)
- [DESKTOP_APP_STRATEGY.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/DESKTOP_APP_STRATEGY.md)
- [UBUNTU_SERVER_RUNBOOK.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/UBUNTU_SERVER_RUNBOOK.md)
- [UBUNTU_SERVER_SETUP_GUIDE.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/UBUNTU_SERVER_SETUP_GUIDE.md)

## 4. 현재 소스 구조 이해

```text
apps/
  web/         -> 브라우저용 사용자 앱
  desktop/     -> 향후 Tauri 기반 Windows 앱

services/
  api/         -> Spring Boot 메인 API
  ai/          -> FastAPI 추천/AI 서비스

packages/
  shared-types/
  shared-utils/
  design-tokens/

docs/
  architecture/
  api/
  product/
  diagrams/
  decisions/
```

## 5. 각 영역의 책임

- `apps/web`: PMS / EMS / GMS 중심 사용자 경험과 공통 플레이어 UI
- `apps/desktop`: 웹 UI와 공통 로직을 재사용하는 Windows 셸
- `services/api`: 인증, 사용자, 트랙, 플레이리스트, 플랫폼 연동, PMS / EMS / GMS 오케스트레이션
- `services/ai`: 오디오 특성 보강, 모델 추론, 추천 계산, AI 보강
- `packages/shared-types`: 프론트와 연계되는 도메인 타입
- `packages/shared-utils`: 공통 헬퍼와 클라이언트 유틸
- `packages/design-tokens`: 재사용 UI 토큰
- `infra/nginx`: 웹 정적 서빙과 API/AI 리버스 프록시

## 6. 새 세션이 시작되면 먼저 볼 문서

아래 순서대로 보면 현재 문맥을 가장 빨리 따라올 수 있습니다.

1. [README.md](/Users/woosungjo/music-space/my-forever-music/README.md)
2. [PROJECT_GUIDE.md](/Users/woosungjo/music-space/my-forever-music/docs/PROJECT_GUIDE.md)
3. [PROJECT_KEY_SERVICE.md](/Users/woosungjo/music-space/my-forever-music/docs/PROJECT_KEY_SERVICE.md)
4. [USER_MUSIC_HOME_VISION.md](/Users/woosungjo/music-space/my-forever-music/docs/product/USER_MUSIC_HOME_VISION.md)
5. [MUSIC_DISCOVERY_AND_LISTENING_UX.md](/Users/woosungjo/music-space/my-forever-music/docs/product/MUSIC_DISCOVERY_AND_LISTENING_UX.md)
6. [REAL_IMPLEMENTATION_POLICY.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/REAL_IMPLEMENTATION_POLICY.md)
7. [MACBOOK_LOCAL_FIRST_PLAN.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/MACBOOK_LOCAL_FIRST_PLAN.md)
8. [DATABASE_SETUP_GUIDE.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/DATABASE_SETUP_GUIDE.md)
9. [TECH_STACK.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/TECH_STACK.md)
10. [AUDIO_FEATURE_PROVIDER_STRATEGY.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/AUDIO_FEATURE_PROVIDER_STRATEGY.md)
11. [DESKTOP_APP_STRATEGY.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/DESKTOP_APP_STRATEGY.md)
12. [SPOTIFY_OAUTH_SETUP.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/SPOTIFY_OAUTH_SETUP.md)
13. [HTTPS_DOMAIN_DEV_SETUP.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/HTTPS_DOMAIN_DEV_SETUP.md)
14. [ADR-001-backend-stack.md](/Users/woosungjo/music-space/my-forever-music/docs/decisions/ADR-001-backend-stack.md)
15. [services/api/README.md](/Users/woosungjo/music-space/my-forever-music/services/api/README.md)
16. [docs/api/README.md](/Users/woosungjo/music-space/my-forever-music/docs/api/README.md)
17. [services/ai/README.md](/Users/woosungjo/music-space/my-forever-music/services/ai/README.md)
18. [UBUNTU_SERVER_RUNBOOK.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/UBUNTU_SERVER_RUNBOOK.md)
19. [UBUNTU_SERVER_SETUP_GUIDE.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/UBUNTU_SERVER_SETUP_GUIDE.md)

## 7. 앞으로 문서를 갱신하는 규칙

- 기술 스택이 바뀌면 `TECH_STACK.md`를 먼저 수정
- 구조 결정이 바뀌면 `ADR`를 추가하거나 수정
- 새 세션이 꼭 알아야 할 공통 방향이 바뀌면 이 `PROJECT_GUIDE.md`를 갱신
- 루트 구조가 바뀌면 `README.md`도 함께 수정
- 오류 처리 기준이 바뀌면 `REAL_IMPLEMENTATION_POLICY.md`와 회귀 하네스를 함께 수정

## 8. 지금 시점의 우선순위

### 1차 마감: 추천 모델 / EMS 데이터 풀 운영 완성

현재는 Spotify/TIDAL 기반 PMS import, 추천 event/snapshot, SASRec 학습/registry, GMS feedback, feature coverage/drift, EMS acquisition 1차 흐름과 운영 확장까지 들어간 상태입니다.
문서 정리, TIDAL PMS provider 오류 경계 보강, Discogs label enrichment, cold-start import 유도 UX, EMS acquisition source 품질/스케줄러/skip drift/source preset은 반영했습니다. 남은 작업은 새 기능 확장이 아니라 추천/EMS 1차 제품형 마감 검증입니다.

1. API/Web 전체 회귀 테스트
2. 실제 TIDAL 계정 import 회귀
3. EMS acquisition runbook 재실행, 가능하면 `editorial-expanded` preset 포함

### 보류: YouTube Music & Apple Music

- **YouTube Music**: 공식 API가 없어 PMS import 불가. EMS 신호원으로만 검토.
- **Apple Music**: Apple Developer Program membership 필요. 준비 시까지 보류.

### 플랫폼 확장 순서 (확정)

1. **Spotify** ✅ 완료 - 1차 기준 플랫폼
2. **TIDAL** 🟡 검증중 - Spotify 다음 우선순위
3. ~~YouTube Music~~ - 공식 API 없음, EMS 신호원으로만 검토
4. ~~Apple Music~~ - Developer Program 준비 시까지 보류

현재 참고 상태:

- `services/api`는 `Java 21 + Gradle wrapper` 기준으로 로컬 부팅 검증 완료
- `services/api`의 `local` 프로필은 현재 `PostgreSQL` 없이 부팅 가능
- `GET /api/v1/platforms/catalog` 응답 경로 추가 완료
- `POST /api/v1/auth/register` 회원가입 경로 추가 완료
- `POST /api/v1/auth/login` 로그인과 온보딩 복원 경로 추가 완료
- `GET/POST /api/v1/platforms/connections/*` 온보딩 연결 경로 추가 완료
- `POST /api/v1/platforms/oauth/*`는 사용자 플로우에서 실제 Spotify OAuth 설정이 있어야 시작됨
- `GET/POST /api/v1/pms/import/*` PMS playlist import 경로 추가 완료
- TIDAL 사용자 플레이리스트 목록 조회는 device 토큰(레거시 scope)에 맞춰 레거시 v1 `/users/{id}/playlists`를 우선 호출하고, OpenAPI v2 컬렉션 엔드포인트는 fallback으로 유지
- `POST /api/v1/platforms/oauth/start`는 `tidal`에 대해 device 경로(`/api/v1/platforms/oauth/tidal/device/start`)로 안내하며 `400`으로 거절. `TIDAL_SCOPES`는 device 허용 목록(`r_usr,w_usr,w_sub`) 안에서만 적용되고 그 외 값은 무시 + 경고 로그
- `services/api`에는 platform credential 저장소와 playlist provider 추상화가 추가되어 실제 Spotify import를 처리함
- `services/api`는 실제 Spotify/TIDAL playlist listing/item import를 처리하며, 오디오 특성을 확보하지 못한 track은 `unavailable` 스냅샷으로 남김
- `services/api`는 Spotify access token 만료 시 refresh token 기반 자동 갱신을 수행함
- `services/api`와 `apps/web`는 refresh 실패 시 `reconnect_required` 상태와 재연결 UX까지 반영함
- `services/api`는 DB 활성 프로필에서 PMS import 결과를 `pms_imported_*` 테이블에 영속 저장함
- `services/api`는 PMS import 직후 정식 `PMS user library` sync를 수행하고, DB 활성 프로필에서는 `pms_user_*` 테이블에 영속 저장함
- `GET /api/v1/pms/workspace/bootstrap`는 현재 정식 `PMS user library -> raw import snapshot -> user-owned database catalog -> empty library` 순서로 소스를 선택함
- 사용자별 음악 학습 모델은 플랫폼 연동과 PMS user library 저장 안정화 이후 개발하는 단계로 고정함
- 플랫폼 카탈로그에는 `TIDAL`, `YouTube Music`, `Apple Music`, `Last.fm`이 포함되며, 확장 순서는 `Spotify -> TIDAL -> YouTube Music`, Apple Music은 개발자 계정 준비 전까지 보류로 고정됨
- 현재 사용자 온보딩의 PMS import는 `Spotify`가 1차 안정화 대상이고, `TIDAL`은 실제 provider가 있으나 검증 단계로 남아 있음
- `GET /api/v1/platforms/lastfm/preview` 경로가 추가되어 공개 Last.fm 사용자명 기준 signal preview를 확인할 수 있음
- `POST /api/v1/platforms/lastfm/profile` 경로가 추가되어 Last.fm 사용자명을 계정에 저장할 수 있음
- `GET/POST /api/v1/platforms/lastfm/scrobbles/*` 경로가 추가되어 최근 scrobble snapshot을 저장하고 다시 `/platforms`에서 확인할 수 있음
- `apps/web`의 `/platforms`는 Last.fm preview 결과를 읽고 top artist를 EMS seed로 복사하거나 계정에 저장할 수 있음
- `apps/web`의 `/platforms`는 저장된 Last.fm profile 기준으로 recent scrobble sync와 snapshot 요약도 제공함
- `POST /api/v1/ems/workspace/analysis`는 저장된 Last.fm scrobble snapshot의 artist recurrence를 우선 blend 하고, 비어 있으면 live top artist 조회로 fallback 함
- `POST /api/v1/gms/recommendations/preview`도 저장된 Last.fm scrobble snapshot의 artist recurrence를 우선 seed artist에 blend 하고, 비어 있으면 live top artist 조회로 fallback 함
- `GET /api/v1/pms/workspace/bootstrap` 응답 검증 완료
- `POST /api/v1/ems/workspace/analysis` 응답 경로 추가 완료
- `POST /api/v1/ems/workspace/overview`는 deterministic EMS 상태와 AI 해석을 제공하지만, 현재 웹 EMS 첫 화면은 검색/재생 중심으로 구성됨
- `GET /api/v1/ems/collection/playlists`와 `GET /api/v1/ems/collection/playlists/{playlistId}`는 EMS DB에 저장된 공개 playlist와 ordered track detail을 표시함
- `POST /api/v1/ems/collection/search`는 연결된 provider 검색을 수행하고, 반환된 playlist/track 결과를 `ems_pool_*` 테이블에 먼저 적재한 뒤 백그라운드 워커가 `search_pool` 소스로 EMS 본 테이블에 반영함
- `GET /api/v1/ems/collection/admin/pool/runs`와 관련 상세/재실행 API는 관리자 `jowoosungtidal@gmail.com` 계정 전용 EMS POOL 진행 상황 조회 API임
- `GET /api/v1/ems/collection/search/playlists/{platformId}/{externalPlaylistId}/tracks`는 검색 결과 playlist의 track 목록을 provider에서 조회해 재생 가능한 detail로 제공하고, 이미 EMS에 들어간 검색 playlist와 track 링크를 `search_pool`에 저장함
- `POST /api/v1/platforms/playback/tidal/resolve-track`는 TIDAL 재생 모드에서 타 플랫폼 track metadata를 TIDAL playable target으로 resolve 함
- `POST /api/v1/gms/recommendations/preview`는 `services/ai`와의 브리지까지 검증 완료
- `services/api`는 import 전 PMS workspace가 임의 demo playlist/seed를 노출하지 않고 빈 라이브러리 상태를 반환함
- `pms_track`는 아직 `spotify_*` 이름의 legacy 오디오 특성 저장 구조를 사용하지만, 문서 기준은 provider-neutral 전략으로 전환됨
- PMS import 시 오디오 특성 저장 기준은 [AUDIO_FEATURE_PROVIDER_STRATEGY.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/AUDIO_FEATURE_PROVIDER_STRATEGY.md) 와 [PMS_TRACK_AUDIO_FEATURE_STORAGE.md](/Users/woosungjo/music-space/my-forever-music/docs/api/PMS_TRACK_AUDIO_FEATURE_STORAGE.md) 를 함께 따른다
- `docs/api/README.md`가 API 계약 문서의 공식 진입점으로 추가됨
- DB 활성 프로필에서는 PMS bootstrap이 실제 `pms_*` 테이블 기반으로 응답 가능
- `apps/web`에는 `/platforms` route가 추가되어 preferred PMS source platform을 선택할 수 있음
- `apps/web`에는 `/signup` route가 추가되어 회원가입과 기본 플랫폼 선택이 가능함
- `apps/web`에는 `/login` route가 추가되어 기존 계정 재로그인과 현재 온보딩 단계 복원이 가능함
- `apps/web`는 가입 후 세션을 로컬에 저장하고 `/platforms`에서 Spotify OAuth redirect를 처리할 수 있음
- `apps/web`는 `/pms`에서 platform playlist import와 사용자별 workspace bootstrap을 사용할 수 있음
- `apps/web`는 `/pms`, `/ems`, `/gms-preview`에서 playlist cover, album image, playable track card, global playback dock을 공유함
- `apps/web`의 EMS 화면은 provider 검색 결과와 DB 기반 공개 playlist pool을 탭 없이 표시하고, 검색 playlist detail에서도 트랙 재생이 가능함
- `apps/web`의 `/ems/pool-admin`은 관리자 계정 전용 화면이며, EMS POOL 진행 모니터링과 함께 run 재실행, entry 단위 재시도, run 삭제, 빈 EMS 플레이리스트 일괄 정리, last_error tooltip 표시를 제공함
- `apps/web`의 `/ems/pool-admin`은 polling을 AbortController로 race-safe하게 처리하고 페이지 hidden 시 polling을 멈추며, 위험 액션은 공통 HUD `ConfirmDialog`로 확인함
- `apps/web`의 공통 컴포넌트로 HUD 템플릿 스타일의 `ConfirmDialog`가 추가되어 ESC/backdrop 닫기와 loading 차단을 포함한 재사용 가능한 확인 다이얼로그 패턴을 제공함
- `services/api`는 EMS search 결과 적재의 silent skip을 제거함: `getSearchPlaylistTracks`가 진입 시 `ems_collected_playlist` row를 항상 보장하고, `storeSearchPlaylistTracks`는 playlist 인자를 필수로 받아 null이면 실패시킴 (track 누락 핵심 원인 차단)
- `services/api`는 EMS search 적재 후 트랙이 0개로 확인된 신규 playlist를 본 테이블에 남기지 않고, 기존에 적재된 빈 playlist는 `POST /api/v1/ems/collection/admin/playlists/cleanup-empty`로 일괄 삭제 가능함
- `services/api`는 TIDAL `getPlaylistTracks`가 legacy 2xx 빈 결과를 진짜 0개로 신뢰하고, legacy 실패 시에만 OpenAPI fallback을 호출하며 N+1 fallback에서 track detail이 drop된 개수를 WARN으로 로깅함
- `services/api`는 EMS pool worker가 `FOR UPDATE SKIP LOCKED` 기반 entry claim과 별도 `EmsPoolEntryClaimer` 빈으로 self-invocation 트랜잭션 문제와 race condition을 차단함
- `services/api`의 EMS pool entry processor 가드는 `running` 상태 entry도 처리하도록 보완되고, admin "다시 실행" 진입 시 stuck `running` entry를 `queued`로 reset해 복구 가능함
- `services/api`는 EMS pool admin 경로에 `POST .../entries/{entryId}/retry`, `DELETE .../pool/runs/{runId}` 엔드포인트를 제공해 stuck entry/run을 운영 가시성 안에서 복구함
- `apps/web`는 EMS/PMS playlist 재생 시 DB detail track을 읽어 queue로 넘기고, TIDAL 모드에서는 track별 TIDAL target resolve 후 재생함
- `apps/web` 공통 player는 새 재생 시작 전 기존 player state를 초기화하고, provider resolve/stream 준비 중 spinner와 상태 메시지를 표시함
- `apps/web`는 TIDAL 재생 중 `PlaybackDock` 확장 버튼으로 `/visualizer` 풀스크린 Visual EQ 페이지에 진입할 수 있음. 실제 TIDAL 오디오 신호를 `captureStream` → `AnalyserNode`로 분기해 FFT를 시각화하고, 미지원/zero-data 환경에서는 procedural fallback으로 자동 전환함. 비주얼라이저는 `bars`/`radial`/`particle` 3종 풀에서 트랙별로 랜덤 선택되며 `?animation=` 쿼리로 강제 가능함. 설계 문서는 [docs/architecture/VISUAL_EQ_PLAYER_DESIGN.md](architecture/VISUAL_EQ_PLAYER_DESIGN.md)
- `apps/web` 메인 페이지는 상단에 `HeroEqBanner` 가 노출됨. `services/api` 의 `GET /api/v1/main-page/hero-tracks?user_id=...&limit=5` 가 (로그인 사용자) GMS top 추천 → (모자라면) EMS acquisition pool 랜덤 → 그래도 부족하면 전체 preview 풀 랜덤 순서로 최대 5곡을 반환한다. 프론트는 Spotify preview URL 을 `fetch + decodeAudioData` 로 분석해 `BarsVisualizer` 로 시각화하고, 30초 종료 후 3초 간격으로 다음 트랙에 자동 advance. 5곡 끝나면 "Replay all" 표시, 사용자가 "전체 듣기" 누르면 회전 정지 + 현재 트랙으로 dock 재생. 단일 트랙용 `GET /api/v1/main-page/hero-track` 도 backward-compat 유지. 설계 문서는 [docs/architecture/MAIN_PAGE_HERO_DESIGN.md](architecture/MAIN_PAGE_HERO_DESIGN.md)
- `apps/web` 메인 페이지 `LatestTracksSection` 은 `services/api` 의 `GET /api/v1/main-page/latest-tracks?limit=10` 응답으로 acquisition_pool 최신 트랙들을 카드 grid 로 표시한다. preview 유무 무관 — 카드 클릭 시 로그인 사용자는 `PlaybackContext.playItem` 으로 dock 전체 재생, 비로그인은 `/signin` 으로 redirect
- `services/api` 는 `user_track_like` 테이블과 `POST /api/v1/user/likes`, `GET /api/v1/user/likes/state`, `GET /api/v1/user/likes` 엔드포인트를 제공. `apps/web` 의 `PlaybackDock` 가 로그인 사용자에 한해 하트 토글 버튼을 노출하고 `useTrackLike` 훅으로 상태를 동기화한다. 비로그인 사용자에겐 버튼이 숨겨짐
- `services/api` 의 `PopularPlaylistService` 는 `EmsCollectedPlaylistRepository.findPopularByTrackCount` 로 트랙 수 내림차순으로 상위 N개를 반환한다. `GET /api/v1/main-page/popular-playlists?limit=6` 가 envelope `{ playlists }` 응답을 내며, `apps/web` 의 `PopularPlaylistsSection` 이 메인 페이지 §3 카드 grid 로 표시하고 카드 클릭 시 `/playlists/ems/{playlistId}` 상세 페이지로 이동시킨다
- `apps/web` 메인 페이지 `GmsRecommendedPlaylistsSection` (§5) 는 기존 `GET /api/v1/gms/playlists/preview?limit=5` 를 재사용해 GMS top 추천 플레이리스트 5개를 카드 grid 로 표시한다. 비로그인 사용자에겐 `/signin` 안내 카드, 로그인 cold-start (409) 에겐 `/platforms` 안내 카드를 노출하고, 카드 클릭 시 `/playlists/ems/{playlistId}` 로 이동
- `services/api` 의 `HeroTrackService` 는 GMS top 후보가 DB 에는 있지만 `preview_url` 이 비어 있을 때 `SpotifyPublicCatalogClient.getTrack` (Client Credentials) 로 Spotify 카탈로그에서 preview 를 보강해 hero 응답에 포함시킨다. DB 는 in-memory enrichment 만 수행하며 갱신하지 않는다. 보강 실패 시에는 기존대로 다음 후보 (EMS acquisition pool / 전체 preview 풀) 로 fallback. TIDAL-first 정책은 풀-트랙 재생에만 적용되고 hero preview 는 anonymous 까지 지원해야 하므로 Spotify 전용 예외
- `services/api` 의 `melon` 모듈은 Jsoup 으로 `https://www.melon.com/chart/index.htm` 을 파싱해 `melon_chart_track` 테이블에 최신 100곡을 저장한다. `POST /api/v1/admin/melon/scrape` 가 수동 트리거이고 `MelonChartScraperScheduler` 가 env `MELON_SCRAPE_ENABLED=true` 일 때 24h 주기로 자동 갱신한다 (기본 disabled). `GET /api/v1/main-page/melon-hot-100?limit=10`, `/melon-hot-100/full`, `/melon-hot-100/{rank}/resolve` 세 엔드포인트가 데이터를 노출한다. resolve 는 `SpotifyPublicCatalogClient` (Client Credentials) 로 title+artist 를 Spotify 트랙으로 매칭해 `MelonResolveResponse` 로 반환하며, `apps/web` 의 공유 `MelonChartRow` 가 각 행에 Play 버튼 (resolve → `PlaybackContext.playItem` → dock 재생) + 외부 Melon 링크 둘 다 제공한다. 비로그인 사용자가 Play 누르면 `/signin` 으로 이동
- `apps/web` 의 `AlgorithmIntroSection` (메인 페이지 §4) 은 PMS / EMS / GMS 3단계 + 6-axis 추천 파이프라인을 짧게 소개하고 "Read the breakdown" CTA 로 신규 `/about/recommendation` 라우트의 `RecommendationAlgorithmPage` 로 이동시킨다. 상세 페이지는 단계별 정의, 6-axis 정의 (affinity/novelty/coherence/diversity/redundancy/confidence), no-mock 정책과 피드백 루프 설명을 정적 콘텐츠로 정리한다
- `GET /api/v1/pms/workspace/bootstrap`는 optional `playlist_id` 기준으로 현재 음악 컨텍스트를 다시 투영함
- `POST /api/v1/gms/recommendations/preview`는 가능하면 synthetic item 대신 `PMS user library`의 실제 playable track으로 재매핑함
- `POST /api/v1/gms/recommendations/feedback`는 GMS 추천 후보에 대한 like/dislike/save/skip 평가를 저장함
- `GET/POST /api/v1/pms/personal-playlists/*`는 사용자 제작 PMS playlist 생성과 GMS 추천 후보 저장을 제공함
- `GET /api/v1/gms/playlists/preview`는 사용자 PMS 라이브러리 affinity 기준으로 EMS 평가 공개 playlist 후보를 정렬해 반환하고, cold-start 사용자에는 409로 차단함. 응답 후보마다 affinity/novelty/coherence/diversity/redundancy/confidence 6축 evidence와 composite score를 포함하고, 정렬은 composite score 기준으로 수행함
- `POST /api/v1/gms/playlists/{id}/save`는 EMS 평가 playlist를 결정적 personal playlist id(`gms-ems-{id}`)로 PMS에 멱등 저장하고, 추가된 트랙마다 `added_to_playlist` 이벤트를 기록함. 이미 PMS에 저장된 `gms-ems-*` playlist는 이후 GMS preview 후보에서 제외됨
- `apps/web`는 `/gms-playlists`에서 사용자에게 EMS 평가 playlist 후보를 composite/affinity 점수와 6축 evidence 패널과 함께 카드로 노출하고, "Preview tracks" 모달에서 트랙 목록을 보고 개별/전체 재생으로 사전 청취한 뒤 ConfirmDialog로 PMS 저장 흐름을 승인받음. PMS 저장이 끝난 카드는 즉시 후보 목록에서 사라지고, `Remove from GMS`는 EMS 원본 삭제 없이 `ignored_recommendation` playlist 이벤트를 남기고 다음 preview 후보에서 제외함
- `services/api`는 Phase 2 metadata normalization identity pipeline을 제공함: MusicBrainz/Wikidata/Discogs lookup, `track_identity_candidate` accept/auto-accept, accepted candidate → EMS/PMS 본 테이블 ISRC/MBID 갱신, `canonical_track`/`canonical_track_identity`로 동일 곡 연결, audit log 영속 저장, 주기적 apply scheduler까지 관리자 전용 `/recommendations/metadata-admin`에서 운영함
- `services/api`의 metadata candidate save 경로는 source(`musicbrainz`/`wikidata`/`discogs`)와 무관하게 `CandidateQualityScorer`를 거쳐 normalized 0..1 `candidate_score`를 부여함. Jaccard 토큰 유사도 + 부분 문자열 보너스로 title × 0.6 + artist × 0.4를 base로 계산하고, MusicBrainz Lucene score는 0.2 weight로 blend됨. Wikidata description의 `song by X` / `album by Y` 패턴과 Discogs `Artist - Title` 포맷에서 artist를 자동 추출함. 같은 auto-accept threshold가 모든 source에서 일관되게 동작함
- `services/api`의 canonical track promote 흐름은 Discogs candidate일 때 candidate metadata의 year/country를 파싱하고, Discogs master detail의 `main_release`를 release detail로 조회해 primary label을 `canonical_track.release_label`(V39)에 fill-if-null로 보강함. 기존 release 필드는 덮어쓰지 않으며 관리자 화면 `/recommendations/metadata-admin`의 promote 결과 요약에 year/country/label이 함께 노출됨
- `services/api`의 SASRec auto-train 스케줄러는 매 tick마다 `RecommendationModelTrainingResponse`의 `metrics`/`baseline_metrics`/`metric_delta`(Hit@K, MRR@K, nDCG@K)를 추출해 `sasrec_auto_train_log`(V35)에 함께 영속 기록함. `/recommendations/sasrec-admin`의 Auto-Train 결과와 Other user lookup `latest_train_log` 패널에 SASRec vs recency baseline vs Δ를 나란히 비교하는 표가 추가되어, baseline 대비 회귀(rose) / 개선(emerald)을 한눈에 볼 수 있음. AI service의 recency baseline 비교 자동화는 이전부터 있었지만, 운영 가시성과 시계열 보존이 이번에 보강됨
- `services/api`는 Phase 6 feature coverage dashboard를 제공함: 관리자 전용 `GET /api/v1/recommendations/admin/feature-coverage?user_id&target_user_id` endpoint와 `/recommendations/feature-coverage` 화면이 PMS user library(audio/ISRC/playback target), EMS collected pool(source platform별 audio/ISRC/canonical link coverage), learning signal(user event/recommendation snapshot 수)를 한 응답으로 집계해 노출함. EMS repository가 없는 local 프로필에서는 degraded warning을 노출해 경계 부재를 숨기지 않음
- `services/api`는 feature coverage 응답에 `DriftSignalEvaluator`가 생성한 drift signal 목록을 포함함. PMS audio/playback/ISRC, EMS source별 audio/ISRC/canonical link, learning data event 수가 사전 정의 임계치 미달이면 `category`/`severity`(warn|info)/`target_scope`/`message`/`actual_value`/`threshold`/`sample_size`를 갖는 signal을 생성하고, EMS source 단위 신호는 표본 부족(예: 20 미만 track) 시 가드로 억제됨. 임계치는 `app.recommendation.drift.*` 설정으로 운영자가 튜닝하고, `/recommendations/feature-coverage` 화면이 신호 banner로 표시함 (warn 호박색, info 회색)
- `services/api`는 audio feature completion queue 1차를 제공함: `audio_feature_completion_job`(V44) 테이블과 local/JPA store를 추가했고, 관리자 전용 `POST /api/v1/recommendations/admin/audio-feature-completion/enqueue`, `GET /api/v1/recommendations/admin/audio-feature-completion/jobs`, `POST /api/v1/recommendations/admin/audio-feature-completion/process` endpoint로 PMS/EMS 누락 오디오 특성 track을 멱등하게 queue에 넣고 ReccoBeats 기반으로 처리할 수 있음. PMS import 후 user library sync 완료 시점과 EMS collected track 저장/갱신 시점에는 incomplete audio feature track을 자동 queue에 넣음. `AudioFeatureCompletionScheduler`는 기본 disabled인 opt-in worker로 `AUDIO_FEATURES_COMPLETION_SCHEDULER_ENABLED=true` 설정 시 queue를 주기 처리하고, `/api/v1/system/admin/schedules`의 `audio-feature-completion` 항목에 마지막 tick 상태를 노출함. ReccoBeats no-match는 Last.fm `track.getTopTags`/`artist.getTopTags` 기반 partial inference를 시도하고 `track_audio_feature_evidence`(V45)에 tag/result evidence를 저장함. Last.fm 추론은 측정값이 아니므로 `audio_features_filled=false`와 `unresolved` job 상태를 유지함. Last.fm 이후에도 남은 track은 `services/ai`의 `POST /v1/audio-features/infer`로 OpenAI web search + structured output 기반 `llm_search_inferred` estimate를 요청하고, evidence non-empty + confidence gate 통과 + 필수 feature 완비일 때만 snapshot/evidence를 저장하고 job을 `completed`로 바꿈. `/api/v1/recommendations/admin/feature-coverage`는 최근 completion job status count와 unresolved/retry/failed top reason 분포를 `audio_feature_completion`으로 함께 반환하고, `/recommendations/feature-coverage` 화면은 이를 Audio Completion 패널과 status/reason table로 표시함.
- `services/api`는 Audio Taste Model v1의 on-demand centroid profile/admin API/GMS preview boost를 제공한다. 첫 구현은 deep model이 아니라 설명 가능한 `AudioCentroidBaseline`이며, audio coverage/confidence가 낮으면 자동으로 no-op 또는 낮은 가중치로 동작한다. 관리자 API는 `GET/POST /api/v1/recommendations/admin/audio-taste/*`이고, GMS preview 적용 시 response warning과 `context.engine`에 `audio-taste:v1`을 남긴다.
- `services/api`의 Phase 5 fast-path 개인화 신호: `user_personalization_profile`(V38) 테이블과 local/JPA store를 추가해 사용자별 top artist / top source platform 집계를 영속 저장함. `UserPersonalizationProfileService.recompute(userId)`가 최근 user_music_event 를 행동 가중치로 합산해(`signalWeight`: 저장/추가/반복 +1.5, like +1.0, 완청 +0.7, 미드스톱 -0.3, 조기스킵 -0.5, 거부 -1.0) artist/platform 별 score+signalCount를 생성. 임계치는 `app.recommendation.personalization.*`로 튜닝 가능. 관리자 전용 `GET/POST /api/v1/recommendations/admin/personalization-profile[/recompute]` endpoint와 `/recommendations/sasrec-admin` "Personalization Profile" 패널이 조회/재계산을 제공.
- `services/api`의 Phase 5 sub-item 2: `RecommendationReranker`가 GMS preview 응답 직전에 user personalization 프로필을 적용해 candidate를 재정렬함. artist 매칭이 있으면 `score × (1 + α × normalized_artist_score)` 부스트(α 기본 0.3, `app.recommendation.rerank.artist-boost-weight`), source platform 매칭은 약한 보조 부스트(β 기본 0.1). 정렬 후 rank 1..N으로 재부여하고, 응답 `warnings`에 `"Session reranked N candidate(s) via personalization profile (order_changed=...)"` 항목이 남아 관리자가 적용 여부를 가시화함. 프로필이 없거나 비어 있으면 no-op이며 cold-start 흐름과 충돌하지 않음
- `services/api`의 Phase 5 sub-item 3 (feedback label weighting): `EventSignalWeights` @Component를 단일 source of truth로 추가해 모든 행동 이벤트의 canonical 가중치를 한 곳에서 관리함. `UserMusicEventService`는 저장 시 이 가중치를 적용하고, `UserPersonalizationProfileService.signalWeight`는 `event_weight`가 null일 때 fallback으로 사용함 — 두 곳에서 가중치가 다르던 이전 상태를 해소. 별칭(`repeat_played→replay`, `skipped_early→skip_next`, `recommendation_saved→track_saved`)도 정규화되어 어휘 불일치로 인한 누락 신호가 사라짐. 가중치 분류는 plan §4-2의 "신호 해석"을 따름(강한 긍정 +2.0 / 긍정 +1.0~1.5 / 약한 긍정 +0.3 / 약한 부정 -0.1~-0.25 / 강한 부정 -2.0)
- `services/api`의 Phase 5 sub-item 4 (cold-start user fallback): `ColdStartFallbackService` @Component가 PMS user library가 비어 있는 사용자에 대해 EMS 본 테이블의 최근 audio feature 채워진 트랙을 fallback 후보로 제공함. `GmsRecommendationPreviewService`는 AI service가 item을 반환했지만 PMS 매핑이 비어 있을 때, 이전에는 `IllegalArgumentException`("Import a real playlist...")으로 끊었지만 이제는 cold-start 사용자라면 fallback을 적용함. 사용자의 `preferredPlatformId`를 우선 사용하고, 그 플랫폼이 비어 있으면 EMS pool에서 다른 플랫폼으로 fallback. response warnings에 cold-start 메모, audit log에 `fallback_reason='cold_start_pms_empty'` 기록. `app.recommendation.cold-start.fallback-limit`(기본 12)로 갯수 튜닝 가능. EMS pool도 비어 있으면 기존 오류로 끊음(데이터 부재라 fallback도 불가능한 경우)
- `apps/web`의 `/gms-preview`는 `fetchPmsWorkspaceBootstrap` 결과 기준으로 cold-start 사용자(PMS playlists 0개 또는 모든 playlist track 0개)를 페이지 진입 즉시 감지함. preview submit 전 단계에서 페이지 상단에 "PMS Library Empty" banner와 "Open PMS Import" CTA를 노출하여 EMS fallback만 받고 끝나지 않고 실제 import 흐름으로 유도함. preview 응답이 도착한 뒤에는 banner를 숨기고 기존 Response Feed의 "Import Next" callout이 같은 역할을 이어받음
- `services/api`는 Phase 6 recommendation audit log를 제공함: `recommendation_audit_log`(V37) 테이블과 local/JPA store에 GMS `preview_generated`(user/recommendation/request id, item count, model version, dataset fingerprint, SASRec 적용 여부, fallback reason)와 `feedback_recorded`(feedback type, target track/playlist) 로그를 기록함. 관리자 전용 `GET /api/v1/recommendations/admin/audit-log/recent?user_id&target_user_id&limit` endpoint로 최근 감사 로그를 조회함
- `services/api`는 RSS editorial source 기본 12개(Pitchfork/Stereogum/NME/BrooklynVegan/FACT/FADER/Billboard/Rolling Stone/Best Fit/SPIN 등) → `services/ai` signal 추출 → Spotify/TIDAL seed → EMS pool 적재까지 잇는 EMS acquisition pipeline을 갖추고, 관리자 전용 `/ems/acquisition-admin`에서 run 트리거/seed dedupe/skipped count 가시성을 제공함. RSS HTTP client는 301/302 redirect를 따라가며, 본 테이블에는 `collection_source='acquisition_pool'`로 누적됨
- `apps/web`의 `/ems/acquisition-admin` 운영 가시성: 각 run 카드에 `scheduled`/`manual` trigger 배지 + `message`/`last_error` 줄이 노출되어 운영자가 한눈에 skip 사유를 확인 가능. 별도 Scheduler 패널이 최근 기록 기준 scheduled/manual 횟수, skipped scheduled 횟수, 마지막 scheduled 실행 시각 + 메시지를 보여주고, scheduled 기록이 0이면 `app.ems.acquisition.user-id` 미설정 또는 scheduler disabled 여부 점검 가이드를 노출함
- `services/api`는 `GET /api/v1/ems/acquisition/source-quality?days=N`(기본 14일, 최대 90일)로 `ems_acquisition_signal`을 source별로 집계해 signal_count / avg_confidence / last_signal_at을 반환함. `/ems/acquisition-admin`의 Source quality 표가 lookback 입력 + Reload 버튼과 함께 이를 표시해 운영자가 활발한 source와 산출이 막힌 source를 한눈에 식별할 수 있음. status fetch 시점에 함께 가져오고, lookback 변경 시 Reload로 단독 재조회 가능함
- `apps/web`의 `/ems/acquisition-admin`은 `completed`/`completed_with_failures` runs를 합산해 (`skipped_article_count` + `skipped_seed_count`) / (signals + seeds + skips) 비율을 별도 Skip drift 패널로 노출함. severity는 warn ≥50%, critical ≥80% (frontend-only) — backend `DriftSignalEvaluator`의 `ems_acquisition_skips` 신호(`/recommendations/feature-coverage`에서 노출)와 같은 데이터 기반이지만, 운영자가 acquisition admin 페이지를 떠나지 않고도 빠르게 확인할 수 있게 함
- `services/api`는 `GET /api/v1/ems/acquisition/source-presets`와 `POST /api/v1/ems/acquisition/run`의 `source_preset` 옵션을 제공함. `editorial-expanded` preset은 기본 source보다 큰 editorial RSS 묶음과 `max_articles_per_source=25`, `max_signals_per_run=120`, `per_seed_limit=10`을 함께 제공해 EMS pool 확대 run을 빠르게 실행할 수 있음
- `apps/web`의 `/ems/acquisition-admin`은 Source preset selector와 Collection target 계산(Sources/Articles/Signals/Seed queries/Track cap)을 제공해 운영자가 수집량 목표를 확인하고 실행값을 조정할 수 있음
- `services/api`는 FLO Special curation 을 EMS 본 테이블에 적재하는 `FloSpecialCurationService` 와 `FloSpecialCurationScheduler` 를 제공함. `music-flo.com` 공개 curation API (`/api/personal/v1/curations/contents`) 에서 PLAYLIST/CHNL 항목을 가져와 각각 playlist tracks / channel tracks 엔드포인트로 펼치고, `collection_source='flo_special'` 로 멱등 upsert 함. `POST /api/v1/ems/collection/flo-special/refresh` 수동 트리거 + `GET /api/v1/ems/collection/flo-special` 조회 (`apps/web` 의 `EmsPage` FLO 섹션으로 노출) + `GET .../flo-special/status` 마지막 실행 결과를 제공. 환경변수 `EMS_FLO_SPECIAL_ENABLED=true` 일 때 기본 24h 주기로 자동 실행
- `services/api`는 YouTube Data API v3 기반 fallback resolver 를 제공함. `YouTubeDataApiClient.searchEmbeddableVideos` 가 `videoEmbeddable=true` 후보 12개를 가져오고 `YouTubePlaybackTargetResolverService` 가 title/artist 토큰 일치, 길이 ±8s 매칭, "official"/"provided to youtube" 키워드, karaoke/instrumental/reaction/cover/tutorial 등 노이즈 키워드 패널티를 합산해 45점 이상 후보를 채택함. `POST /api/v1/platforms/playback/youtube/resolve-track` 엔드포인트로 노출. 환경변수 `YOUTUBE_DATA_API_KEY` 필수 (없으면 412 반환)
- `apps/web` 의 `PlaybackContext` 는 TIDAL/Spotify recoverable error 발생 시 `tryYouTubeFallbackForTrack` 으로 동일 트랙을 YouTube playback target 에 자동 resolve 해 `playYouTubeVideo` 로 dock 내 YouTube IFrame 플레이어에 재생함. `PlaybackDock` 의 host element 는 항상 마운트하고 `hidden` 클래스로만 표시 토글하므로, 트랙 전환 시 player iframe 이 destroy/recreate 되지 않고 `loadVideoById` 로 재사용됨 (검은 화면/blank flash 없이 다음 트랙 자동 이어듣기)
- `apps/web` 의 `MelonChartRow` 는 backend `resolveMelonHotTrack` 응답에 TIDAL/Spotify 매치가 없을 때(`resolved=false` 또는 platform id 누락) 곧바로 `playbackPlatformId='youtube'` 로 라우팅된 PlaybackMediaItem 을 `playback.playItem` 에 넘김. `PlaybackContext.playQueue` 의 최상위 catch 도 선택된 네이티브 플랫폼 분기에서 발생한 비-recoverable 에러를 잡으면 YouTube branch 가 아직 시도되지 않은 경우 `playYouTubeQueueFromIndex` 로 한 번 더 fallback 함 (예: TIDAL 미연결 사용자가 Spotify 소스 트랙을 클릭한 경우)
- `services/api`는 `GET /api/v1/main-page/magazine-articles?limit=N`(기본 8) 을 제공함. `MagazineArticleService` 가 `ems_acquisition_signal` 에서 article_url 기준 distinct 최신 레코드를 잘라, V43 마이그레이션으로 생성한 `magazine_article_cache` 테이블에 미리 enrichment 결과를 캐시 후 응답. cache miss 인 URL 만 `MagazineArticleEnricher` 로 병렬 enrich — 단 한 번의 article 페이지 fetch 로 `og:image`/`twitter:image` + `og:description`/`twitter:description`/`<meta name=description>` 추출 (HTML entity 디코드 포함), Google unofficial translate (`translate.googleapis.com/translate_a/single`) 로 title/description/rationale 한국어 번역. 결과는 `magazine_article_cache` 에 upsert 되어 재시작/멀티 인스턴스에서도 보존되고, 현재 응답 윈도우(top N) 밖 URL 은 같은 트랜잭션에서 prune 되어 테이블이 무한히 자라지 않음. 적재 주기는 기존 `EmsAcquisitionScheduler` 의 24h fixed-delay (env `EMS_ACQUISITION_ENABLED=true`). `apps/web` 의 `MagazineSection` 이 메인 페이지에서 4열(xl) 카드 그리드로 표시, 우측 상단 작은 썸네일에 텍스트가 wrap 되는 매거진 레이아웃, 본문은 publisher description(한국어) 우선·AI rationale 은 다를 때만 보조 노출, 카드 클릭 시 원문 새 창
- 현재 구현은 아직 `핵심 서비스 문서`의 전체 범위가 아니라, 그중 `PMS / EMS / GMS` 추천 흐름의 최소 검증 버전임

## 9. 참고 메모

- `docs/streaming-platforms-api/*`는 외부 음악 플랫폼 연동 참고 자료다
- 이 문서들은 제품 방향 문서가 아니라 통합 참고 문서이므로, 핵심 구조 결정은 이 가이드와 ADR 문서를 우선한다

## 10. 세션용 한 줄 지시문

새 세션은 추측으로 구조를 바꾸지 말고, 먼저 `docs/PROJECT_GUIDE.md`와 `AGENTS.md`를 읽은 뒤 현재 결정 사항에 맞춰 작업을 시작한다.
