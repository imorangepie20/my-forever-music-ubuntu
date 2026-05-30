# my-forever-music

MusicSpace 재구축용 새 프로젝트 루트입니다.

가장 먼저 봐야 하는 통합 가이드는 [docs/PROJECT_GUIDE.md](/Users/woosungjo/music-space/my-forever-music/docs/PROJECT_GUIDE.md) 입니다.

핵심 서비스 정의 원문은 [docs/PROJECT_KEY_SERVICE.md](/Users/woosungjo/music-space/my-forever-music/docs/PROJECT_KEY_SERVICE.md) 에 정리되어 있습니다.

회원이 이 사이트를 반복적으로 이용하는 이유와 장기 제품 방향은 [docs/product/USER_MUSIC_HOME_VISION.md](/Users/woosungjo/music-space/my-forever-music/docs/product/USER_MUSIC_HOME_VISION.md) 에 정리되어 있습니다.

현재 작업 전략은 [docs/architecture/MACBOOK_LOCAL_FIRST_PLAN.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/MACBOOK_LOCAL_FIRST_PLAN.md) 을 기준으로 합니다. 우선 이 MacBook 로컬 시스템에서 실서비스 기능을 구현하고 시험한 뒤 Ubuntu 서버로 이전합니다.

## 서비스 한 줄 정의

`my-forever-music`은 사용자가 구독 중인 음악 스트리밍 플랫폼의 플레이리스트를 가져와 자기 소유의 음악 취향 라이브러리로 보존하고, 플랫폼을 옮겨도 유지되는 개인화 추천과 음악 감상 경험을 제공하는 서비스입니다.

핵심 도메인은 아래 3개 공간으로 나뉩니다.

- `PMS`: 사용자의 플레이리스트, 선호, 평가, 행동 데이터를 보존하는 개인 음악 공간
- `EMS`: 외부 플랫폼의 공개 플레이리스트와 트렌드를 수집하는 탐색 공간
- `GMS`: 사용자 모델이 평가해 통과시킨 추천 결과가 모이는 게이트웨이 공간

## 디렉토리 구조

```text
my-forever-music/
├── apps/
│   ├── web/                  # 사용자용 프론트엔드
│   └── desktop/              # Windows 데스크탑 앱(Tauri 예정)
├── services/
│   ├── api/                  # Spring Boot 메인 API 백엔드
│   └── ai/                   # AI/추천 서비스
├── packages/
│   ├── shared-types/         # 공통 타입
│   ├── shared-utils/         # 공통 유틸
│   └── design-tokens/        # 디자인 토큰
├── infra/
│   ├── docker/               # 컨테이너 관련 파일
│   ├── nginx/                # 리버스 프록시 설정
│   ├── db/                   # 마이그레이션/시드
│   └── scripts/              # 운영/개발 스크립트
├── docs/
│   ├── architecture/         # 시스템 설계 문서
│   ├── product/              # 제품/기능 문서
│   ├── api/                  # API 계약 문서
│   ├── diagrams/             # draw.io / mermaid 다이어그램
│   └── decisions/            # ADR, 의사결정 기록
├── data/
│   ├── samples/              # 샘플 데이터
│   ├── imports/              # 초기 적재용 데이터
│   └── backups/              # 로컬 백업
└── .github/
    └── workflows/            # CI/CD 워크플로
```

## 설계 의도

- `apps/web`: PMS / EMS / GMS 중심 사용자 UI
- `apps/desktop`: 웹 UI와 도메인 로직을 최대한 재사용하는 Windows 앱 셸
- `services/api`: Spring Boot 기반 인증, 사용자설정, 플레이리스트, 트랙, 플랫폼 연동, 추천 오케스트레이션
- `services/ai`: 오디오 특성 보강, 사용자 취향 모델, EMS/GMS 추천 계산 같은 AI 계열 기능
- `packages/*`: 여러 서비스에서 재사용할 코드
- `infra/*`: 배포와 운영 구성
- `docs/*`: 재구축 문서와 다이어그램

## 선정 스택

- 프론트엔드: `React + TypeScript + Vite`
- 데스크탑: `Tauri 2`
- 메인 API: `Spring Boot 3.5.x + Java 21 + Gradle`
- AI 서비스: `FastAPI + Python 3.12`
- 데이터베이스: `PostgreSQL`
- 캐시/큐 보조: `Redis`
- API 계약: `OpenAPI`
- 마이그레이션: `Flyway`

## 현재 반영 상태

- 제품 목표는 `스트리밍 플랫폼 플레이리스트 수집 -> 오디오 특성 분석 -> 사용자 취향 모델 -> EMS/GMS 추천 루프` 구조로 정의됨
- 제품 중심 가치는 `스트리밍 플랫폼이 바뀌어도 유지되는 사용자 소유 playlist/taste library`로 정의됨
- `apps/web`는 Vite 기반 최소 제품 셸과 `GMS preview` 테스트 화면까지 정리 완료
- `apps/web`는 `/signup` 화면에서 회원가입과 기본 스트리밍 플랫폼 선택 가능
- `apps/web`는 `/login` 화면에서 기존 계정 재로그인과 온보딩 복원 가능 (회원가입과 동일하게 인증 직후 추가 클릭 없이 `/platforms?connect=<선호플랫폼>`으로 자동 이동 → 미인증이면 선호 플랫폼 인증이 자동으로 뜸: TIDAL은 device 인증 화면, Spotify는 OAuth 리다이렉트)
- `apps/web`는 `/platforms` 화면에서 스트리밍 플랫폼 카탈로그, 가입 사용자 세션, 실제 Spotify OAuth redirect 흐름과 Last.fm signal profile 저장을 제공
- `apps/web`는 `/platforms` 화면에서 Last.fm 공개 사용자명 기준 signal preview와 EMS seed artist 반영을 제공
- `apps/web`는 `/platforms` 화면에서 Last.fm username 저장, 최근 scrobble sync, 저장 snapshot 확인까지 제공
- `apps/web`는 `/pms` 화면에서 사용자별 PMS bootstrap과 platform playlist import를 제공
- `apps/web`는 `PMS import/bootstrap -> EMS workspace -> GMS preview` 흐름까지 반영 완료
- TIDAL은 device authorization(레거시 scope)으로 연결되며, PMS 플레이리스트 목록 조회도 레거시 v1 엔드포인트를 우선 사용해 device 토큰으로 정상 동작 (OpenAPI v2는 PKCE 토큰용 fallback)
- TIDAL은 Spotify처럼 OAuth 2.1 + PKCE redirect(`login.tidal.com/authorize`)로 연결하며, 로그인 직후 미연결이면 그 인증 페이지로 자동 이동. v2 목록 조회가 참조만 받고 메타데이터를 못 풀면 shortcut으로 fallback
- EMS는 TIDAL 공개 home-page(POPULAR_PLAYLISTS·THE_HITS·POPULAR_MIXES·FROM_OUR_EDITORS)를 **OAuth 없이** 공개 웹 엔드포인트(`tidal.com/v2/home/pages/.../view-all`)로 수집(트랙·ISRC 오디오피처 포함). 기존 discovery 스케줄러로 주기 수집되고 스케줄 관리페이지에 노출되며, "Popular playlists on TIDAL" 섹션을 EMS 페이지와 메인에 노출
- `apps/web`의 `EMS` 화면은 provider 검색, 검색 playlist track detail/playback, EMS DB 공개 playlist pool을 한 화면 흐름으로 제공
- `apps/web` 공통 플레이어는 새 재생 시작 전 초기화한 뒤 TIDAL resolve/stream 준비 상태를 spinner와 메시지로 표시
- `apps/web`는 TIDAL 재생 중일 때 `PlaybackDock` 의 확장 버튼으로 `/visualizer` 풀스크린 Visual EQ 플레이어 진입. 실제 오디오 신호 기반 FFT(`captureStream` → `AnalyserNode`) 비주얼라이저 3종(`bars`/`radial`/`particle`) 을 트랙별 랜덤 선택해 표시하며, `captureStream` 미지원/zero data 환경에서는 procedural fallback 으로 자동 전환
- `apps/web` 메인 페이지 상단 `HeroEqBanner` 가 `/api/v1/main-page/hero-tracks?limit=5` 에서 5곡 큐를 받아 30초 preview 를 3초 간격으로 순서대로 재생함. 무음 자동재생 + ▶ unmute, 5곡 종료 후 "Replay all", 사용자가 "전체 듣기" 누르면 회전 정지 + dock 으로 현재 트랙 전체 재생. 단일 트랙용 `GET /api/v1/main-page/hero-track` 도 backward-compat 유지
- `apps/web` 메인 페이지 `LatestTracksSection` 가 `/api/v1/main-page/latest-tracks?limit=10` 의 acquisition_pool 최신 카드 grid 표시 (preview 유무 무관). 카드 클릭 시 로그인 사용자는 dock 전체 재생, 비로그인은 `/signin` 으로 이동
- `apps/web` 의 `PlaybackDock` 가 현재 트랙에 대한 좋아요(하트) 버튼을 제공. 로그인 사용자에만 노출되며 토글 시 `POST /api/v1/user/likes` 를 호출해 `user_track_like` 테이블에 (user_id, source_platform, external_track_id) 단위로 저장/삭제하고 dock 의 하트 아이콘은 즉시 optimistic update 됨. 페이지 진입/트랙 변경 시 `GET /api/v1/user/likes/state` 로 현재 상태 재조회
- `apps/web` 메인 페이지 `PopularPlaylistsSection` 가 `/api/v1/main-page/popular-playlists?limit=6` 의 응답을 6 카드 grid 로 표시. 정렬 기준은 `ems_collected_playlist.track_count` 내림차순 (proxy of popularity). 카드 클릭 시 기존 `/playlists/ems/{playlistId}` 상세 페이지로 이동
- `apps/web` 메인 페이지 `GmsRecommendedPlaylistsSection` 가 로그인 사용자에 한해 `/api/v1/gms/playlists/preview?limit=5` 응답을 top-5 카드로 표시. cold-start (409) 면 "Build your taste library first" CTA, 비로그인이면 "Sign in for personalized picks" CTA
- `services/api` 의 `HeroTrackService` 가 GMS top 후보가 DB 에 있지만 `preview_url` 이 비어 있을 때 `SpotifyPublicCatalogClient.getTrack` (Client Credentials) 로 preview 를 보강해 hero 큐에 포함시킴. DB 는 변경하지 않으며, Spotify 응답에 preview 가 없거나 호출이 실패하면 다음 후보로 fallback
- `services/api` 의 `MelonChartScraper` (Jsoup) + `MelonChartService` 가 `melon.com/chart/index.htm` 을 스크래핑해 `melon_chart_track` 테이블에 최신 100곡 snapshot 을 적재함. `POST /api/v1/admin/melon/scrape` 로 수동 트리거하며, `MelonChartScraperScheduler` (env `MELON_SCRAPE_ENABLED=true` 일 때 24h 주기 자동 실행). `GET /api/v1/main-page/melon-hot-100?limit=10`, `/full`, `/{rank}/resolve` 세 엔드포인트로 노출. resolve 는 `SpotifyPublicCatalogClient` (Client Credentials) 로 title+artist 를 Spotify 트랙으로 매칭. `apps/web` 메인 페이지 `MelonHot100Section` + `MelonHot100Page` 에서 각 행에 Play 버튼 (resolve → `PlaybackContext.playItem` → dock) + 외부 Melon 링크 둘 다 제공. 비로그인 사용자가 Play 누르면 `/signin` 으로 이동
- `apps/web` 메인 페이지 `AlgorithmIntroSection` (§4) 가 PMS / EMS / GMS 3단계 추천 파이프라인과 6-axis 스코어링을 간단히 소개하고, `/about/recommendation` 의 `RecommendationAlgorithmPage` 가 각 단계 + 6-axis 상세 + 피드백 루프 정책을 정리한 정적 페이지를 제공
- `services/api` 와 `apps/web` 는 FLO Special curation 을 EMS 로 적재해 `EmsPage` 의 FLO Special 섹션으로 노출. `FloSpecialCurationService` 가 `music-flo.com` 공개 curation API 에서 playlist/channel 리스트를 가져와 트랙까지 펼치고 `ems_collected_*` 에 멱등 upsert. `FloSpecialCurationScheduler` (env `EMS_FLO_SPECIAL_ENABLED=true`, 기본 24h 주기) 와 `POST /api/v1/ems/collection/flo-special/refresh` 수동 트리거, `GET /api/v1/ems/collection/flo-special` 조회 엔드포인트 제공
- `services/api` 와 `apps/web` 는 TIDAL/Spotify 재생 실패 시 YouTube Data API v3 기반 자동 fallback 경로를 제공. backend `YouTubePlaybackTargetResolverService` 가 `searchEmbeddableVideos` 로 후보를 가져와 제목/아티스트/길이/공식 키워드/노이즈(karaoke 등) 휴리스틱 점수로 매칭하고 `POST /api/v1/platforms/playback/youtube/resolve-track` 으로 노출. frontend `PlaybackContext` 는 TIDAL/Spotify recoverable error 시 동일 트랙을 YouTube IFrame 플레이어로 자동 전환해 dock 내 영구 마운트된 iframe host 에서 재생 (트랙 전환 시 `loadVideoById` 로 같은 player 재사용)
- `apps/web` 의 Melon Hot 100 행은 backend 가 TIDAL/Spotify 매치를 못 찾아 `resolved=false` 로 응답해도 곧바로 YouTube routing 으로 재생을 시도함. 또한 `PlaybackContext.playQueue` 는 선택된 네이티브 플랫폼(TIDAL/Spotify) 의 setup/playback 이 비-recoverable 에러로 실패한 경우(예: 해당 플랫폼 credential 미연결) catch 단계에서 한 번 더 YouTube fallback 을 자동 시도해 다른 playlist 콘텐츠도 가능하면 YouTube 로 들을 수 있게 함
- `apps/web` 메인 페이지 `MagazineSection` 이 `GET /api/v1/main-page/magazine-articles?limit=8` 응답으로 RSS 매거진(Pitchfork/Stereogum/NME/BrooklynVegan/FACT/FADER/Billboard/Rolling Stone 등) 최신 기사 8건을 4열 카드 그리드(우측 상단 작은 썸네일에 텍스트 wrap 레이아웃)로 노출. 데이터는 기존 `EmsAcquisitionScheduler` 가 `ems_acquisition_signal` 에 적재한 article_url/title/rationale 위에 V43 `magazine_article_cache` 테이블이 영속 캐시 레이어로 얹혀 있어, 첫 요청 시에만 article 페이지에서 `og:image`+`og:description` 스크래핑 + Google unofficial translate 로 title/description/rationale 한국어 번역을 수행하고 결과를 upsert. 이후 요청은 DB 캐시 hit, 재시작/멀티 인스턴스에도 보존. 현재 응답 윈도우(top N) 밖의 URL 은 같은 요청에서 prune 되어 무한히 쌓이지 않음. 본문은 publisher description(한국어) 우선·AI rationale 은 다를 때만 보조 텍스트로 노출
- `apps/desktop`는 향후 Windows 앱 개발을 위한 예약 구조 생성 완료
- `services/api`는 `GET /api/v1/platforms/catalog` 엔드포인트로 플랫폼 역할과 온보딩 흐름 제공
- 플랫폼 카탈로그에는 `TIDAL`, `YouTube Music`, `Apple Music`, `Last.fm`까지 포함되며, 확장 순서는 `Spotify -> TIDAL -> YouTube Music`, Apple Music은 개발자 계정 준비 전까지 보류로 고정됨
- `services/api`는 `GET /api/v1/platforms/lastfm/preview` 엔드포인트로 최근 scrobble, top artist, top track preview를 제공
- `services/api`는 `POST /api/v1/platforms/lastfm/profile` 엔드포인트로 Last.fm signal profile 저장과 EMS 재사용 경로를 제공
- `services/api`는 `GET/POST /api/v1/platforms/lastfm/scrobbles/*` 엔드포인트로 scrobble snapshot 저장과 bootstrap 조회를 제공
- `services/api`는 저장된 Last.fm scrobble snapshot을 `EMS workspace analysis`와 `GMS recommendation preview`에 우선 반영하고, 없으면 live top artist 조회로 fallback 함
- `services/api`와 `apps/web`는 GMS 추천 후보의 like/dislike/save 평가를 저장해 PMS 학습 신호로 환류할 수 있음
- `services/api`와 `apps/web`는 PMS personal playlist 생성과 GMS 추천 후보 저장을 제공함
- `services/api`는 `POST /api/v1/auth/register` 엔드포인트로 회원가입과 기본 플랫폼 선택 제공
- `services/api`는 `POST /api/v1/auth/login` 엔드포인트로 기존 계정 로그인과 현재 온보딩 복원을 제공
- `services/api`는 `GET/POST /api/v1/platforms/connections/*` 엔드포인트로 가입 직후 플랫폼 연결 온보딩 제공
- `services/api`는 `GET/POST /api/v1/pms/import/*` 엔드포인트로 PMS playlist import 제공
- `services/api`는 platform credential 저장과 playlist provider 추상화를 통해 실제 Spotify playlist import를 처리함
- `services/api`는 Spotify PKCE draft 시작 URL, external callback, authorization code token exchange까지 반영됨
- `services/api`는 실제 Spotify playlist 목록과 playlist item import 경로를 추가함
- `services/api`는 Spotify access token 만료 시 refresh token 기반 자동 갱신을 지원함
- `services/api`와 `apps/web`는 refresh 실패 시 `reconnect_required` 상태와 재연결 UX까지 반영함
- `services/api`는 DB 활성 프로필에서 PMS import 결과를 `pms_imported_*` 테이블로 영속 저장함
- `services/api`는 PMS import 직후 정식 `PMS user library` sync를 수행하고, DB 활성 프로필에서는 `pms_user_*` 테이블로도 영속 저장함
- `services/api`의 `PMS workspace bootstrap`는 현재 정식 `PMS user library`를 raw import snapshot보다 우선 사용함
- `services/api`는 PMS workspace bootstrap과 `services/ai` preview 호출용 GMS 브리지 엔드포인트 생성 완료
- `services/api`는 PMS seed 기반 `EMS workspace analysis` 엔드포인트 추가 완료
- `services/api`는 EMS provider search 결과를 `search_pool`에 저장하고, search playlist track detail, EMS collection browse/detail, EMS overview, TIDAL playback target resolve endpoint를 제공
- `services/api`는 `local` 프로필 기준으로 DB 없이 로컬 부팅 검증 완료
- `services/api`의 `PMS bootstrap`과 `GMS preview -> services/ai` 브리지 응답 검증 완료
- `services/api`는 import 전 PMS workspace가 가짜 seed를 노출하지 않도록 빈 라이브러리 상태를 반환함
- `services/api`의 `pms_track`는 Spotify 오디오 특성 전체 스냅샷을 저장할 수 있게 확장됨
- PMS import 시 Spotify 오디오 특성 전체 저장 기준 문서가 추가됨
- `services/api`는 DB 활성 프로필에서 `pms_playlist / pms_track / pms_playlist_track` 기반 bootstrap 응답 가능
- `services/api`는 EMS search 결과 적재의 silent skip을 제거함: `getSearchPlaylistTracks`가 진입 시 `ems_collected_playlist` row를 항상 보장하고, `storeSearchPlaylistTracks`는 playlist 인자를 필수로 받아 null이면 실패시킴
- `services/api`는 EMS search 적재 후 트랙이 0개로 확인된 신규 playlist는 본 테이블에 남기지 않으며, 이미 적재된 빈 playlist는 `POST /api/v1/ems/collection/admin/playlists/cleanup-empty`로 일괄 정리할 수 있음
- `services/api`는 TIDAL `getPlaylistTracks`가 legacy 2xx 빈 결과를 진짜 0개로 신뢰하고, legacy 실패 시에만 OpenAPI fallback을 호출하며 N+1 fallback에서 track detail이 drop된 개수를 WARN으로 로깅함
- `services/api`는 EMS pool worker race를 `FOR UPDATE SKIP LOCKED` 기반 entry claim과 별도 `EmsPoolEntryClaimer` 빈으로 차단함 (self-invocation으로 `@Transactional`이 무시되던 문제 해소)
- `services/api`의 EMS pool entry processor 가드는 `running` 상태 entry도 처리하도록 보완되고, admin "다시 실행" 진입 시 stuck `running` entry를 `queued`로 reset해 복구 가능함
- `services/api`는 EMS pool admin 경로에 entry 단위 재시도(`POST .../entries/{entryId}/retry`), run 삭제(`DELETE .../pool/runs/{runId}`, FK CASCADE), 빈 EMS 플레이리스트 일괄 정리 엔드포인트를 제공함
- `apps/web`의 `/ems/pool-admin`은 polling을 AbortController로 race-safe하게 처리하고 페이지 hidden 시 polling을 중단하며, entry 단위 재시도, run 삭제, 빈 EMS 플레이리스트 일괄 정리, last_error tooltip 표시를 제공함
- `apps/web`의 공통 컴포넌트로 HUD 템플릿 스타일의 재사용 가능한 `ConfirmDialog`가 추가되어 pool-admin의 위험 동작을 모두 동일한 다이얼로그로 확인받음
- `services/api`는 `GET /api/v1/gms/playlists/preview`, `POST /api/v1/gms/playlists/{id}/save`, `POST /api/v1/gms/playlists/{id}/dismiss` 엔드포인트로 EMS 평가 플레이리스트를 사용자 PMS 라이브러리에 저장하거나 GMS 후보에서 제거하는 흐름을 제공함 (cold-start 사용자는 409, 결정적 personal playlist id 사용으로 멱등 추가, 이미 PMS에 저장된 `gms-ems-*` playlist는 GMS 후보에서 제외)
- `services/api`의 GMS playlist preview는 후보마다 6축(affinity/novelty/coherence/diversity/redundancy/confidence) evidence와 composite score를 계산해 응답에 포함하고, composite score 기준으로 후보를 재정렬함
- `apps/web`는 `/gms-playlists` 화면에서 사용자에게 EMS 평가 플레이리스트 후보를 composite/affinity 점수와 6축 evidence 패널과 함께 카드로 노출하고, "Preview tracks" 모달에서 트랙 목록과 개별/전체 재생을 시청한 뒤 ConfirmDialog로 PMS 저장을 승인받음. PMS 저장이 끝난 카드는 즉시 목록에서 사라지고, 카드 단위 `Remove from GMS`는 EMS 원본을 삭제하지 않고 `ignored_recommendation` playlist 이벤트를 남겨 다음 preview 후보에서 제외함
- `services/api`는 Phase 2 metadata normalization identity pipeline을 갖춤: MusicBrainz/Wikidata/Discogs lookup → `track_identity_candidate` accept/reject/auto-accept → 적용 시 실제 EMS/PMS track 행의 ISRC/MBID 갱신 + canonical track identity 연결, audit log 영속 저장, 주기적 apply scheduler까지 관리자 화면 `/recommendations/metadata-admin`에 노출
- `services/api`는 MusicBrainz/Wikidata/Discogs candidate에 source별로 다르게 들어오던 raw score 대신, title/artist Jaccard 토큰 유사도 + 부분 문자열 보너스 + raw score blend로 계산한 normalized 0..1 `candidate_score`를 일관되게 부여함. 동일 auto-accept threshold(`>=0.95`)가 모든 source에서 같은 의미로 동작함
- `services/api`의 canonical track promote 흐름은 Discogs candidate일 때 metadata의 year/country를 `canonical_track.release_year` / `release_country`(V34)에 함께 적재하고, Discogs master/detail의 main release label을 `release_label`(V39)에 fill-if-null로 보강함
- `services/api`의 SASRec auto-train 스케줄러는 매 tick마다 학습 모델의 Hit@K/MRR@K/nDCG@K 측정값과 recency baseline 비교값을 `sasrec_auto_train_log`(V35)에 함께 영속 기록함. `/recommendations/sasrec-admin`의 Auto-Train 결과 패널과 Other user lookup 패널이 SASRec/Baseline/Δ를 나란히 비교하는 표(개선 emerald, 회귀 rose 색)로 표시함
- `services/api`는 RSS editorial source(Pitchfork, Stereogum, NME 등 12개 기본) → `services/ai` signal 추출 → Spotify/TIDAL seed → EMS pool 적재까지 한 번에 잇는 EMS acquisition pipeline을 제공함. 관리자 화면 `/ems/acquisition-admin`에서 run 트리거/모니터링이 가능하며 `collection_source='acquisition_pool'`로 본 테이블에 누적됨
- `apps/web`의 `/ems/acquisition-admin`은 scheduler 운영 상태를 surface함. 각 run 카드에 `scheduled`/`manual` trigger 배지와 message/last_error가 표시되고, 별도 Scheduler 패널이 최근 기록의 scheduled vs manual 횟수, skipped scheduled 횟수, 마지막 scheduled 실행 시각 + 메시지를 한눈에 보여줌. scheduled 기록이 0이면 `app.ems.acquisition.user-id` 누락/disabled 여부 확인 가이드를 노출함
- `services/api`는 `GET /api/v1/ems/acquisition/source-quality?days=N` 엔드포인트로 최근 N일(기본 14일) 동안 source별 signal 수, 평균 confidence, 마지막 signal 시각을 집계해 반환함. `/ems/acquisition-admin`의 Source quality 표가 lookback 입력과 함께 노출되어 운영자가 어떤 RSS source가 활발하고 어떤 source가 산출이 막혔는지 한눈에 판단함
- `apps/web`의 `/ems/acquisition-admin`은 완료된 최근 runs를 합산해 skip ratio (skipped articles + skipped seeds / total attempts)를 계산해 별도 Skip drift 패널로 표시함. 임계치(warn ≥50%, critical ≥80%)에 따라 패널 색이 amber/rose로 바뀌고 source 품질·AI cutoff·dedupe 기준 점검 가이드를 노출함
- `services/api`는 `GET /api/v1/ems/acquisition/source-presets`와 `source_preset` 실행 옵션을 제공함. 기본 configured source 외에 `editorial-expanded` preset이 더 많은 editorial RSS source와 큰 run limit을 묶어 EMS pool 확대 실행에 사용됨
- `apps/web`의 `/ems/acquisition-admin`은 source preset 선택과 collection target(Sources/Articles/Signals/Seed queries/Track cap) 계산을 제공해 운영자가 수집량 목표를 보고 실행값을 조정할 수 있음
- `services/api`는 관리자 전용 `/recommendations/feature-coverage`로 PMS user library (audio feature/ISRC/playback target 보유율), EMS collected pool (source platform별 audio/ISRC/canonical link), learning signal (user event/recommendation snapshot 수) 커버리지를 한 화면에서 확인하게 함. EMS repository 없는 프로필에서는 degraded warning을 노출해 저장소 부재를 숨기지 않음
- `services/api`는 feature coverage 응답에 drift signal 목록(`category`/`severity`/`target_scope`/`message`)을 함께 반환함. PMS audio/playback/ISRC, EMS source별 audio/ISRC/canonical link, learning event 수가 설정 임계치 미달이면 자동으로 신호를 생성하며, 임계치는 `app.recommendation.drift.*` 속성으로 운영자가 튜닝 가능함. `/recommendations/feature-coverage` 페이지가 신호를 status banner로 노출 (warn 호박색, info 회색)
- `services/api`는 사용자별 가벼운 개인화 프로필을 `user_personalization_profile`(V38)에 영속 저장함. 최근 user_music_event 를 행동 별 가중치(저장/추가/반복 +1.5, like +1.0, 완청 +0.7, 미드스톱 -0.3, 조기스킵 -0.5, 거부 -1.0)로 집계해 top artist / top source platform 신호로 변환. 관리자 전용 `GET/POST /api/v1/recommendations/admin/personalization-profile[/recompute]` endpoint와 `/recommendations/sasrec-admin` Other user lookup 패널의 "Personalization Profile" 섹션에서 조회/재계산 가능. Phase 5의 fast-path 신호 기반으로, 후속 reranker가 이 프로필을 입력으로 사용
- `services/api`의 GMS preview는 personalization 프로필이 있으면 `RecommendationReranker`를 통해 후보 순서를 즉시 재조정함. 후보 artist가 프로필 top artist에 매칭되면 `1 + artistBoostWeight × (matched_score / max_score)`로 score를 부스트하고(가중치 기본 0.3), source platform도 약한 보조 부스트(기본 0.1)를 받음. 재정렬 후 rank를 다시 매기고, 변경이 있으면 응답 warnings에 `"Session reranked N candidate(s)..."` 메모를 남겨 관리자가 가시화함. SASRec 재학습 없이도 fast-path 신호가 추천 순서에 반영됨
- `services/api`는 행동 가중치를 `EventSignalWeights` 단일 source of truth로 통합함. `UserMusicEventService`(저장 시 가중치)와 `UserPersonalizationProfileService`(fallback)가 같은 canonical 가중치를 공유하고, `repeat_played`/`replay`, `skipped_early`/`skip_next`, `recommendation_saved`/`track_saved` 같은 별칭은 자동 정규화됨
- `services/api`의 GMS preview는 PMS user library가 비어 있는 cold-start 사용자에 대해 더 이상 "Import a real playlist..." 오류로 끊지 않고, EMS 본 테이블의 최근 audio-feature 채워진 트랙을 fallback 후보로 보여줌. 사용자의 `preferredPlatformId`를 우선 사용하고, 그 플랫폼이 비어 있으면 다른 플랫폼으로 fallback. response warnings에 `Cold-start fallback applied: N tracks...` 메모를 남기고, `recommendation_audit_log.fallback_reason='cold_start_pms_empty'`로 기록함
- `apps/web`의 `/gms-preview`는 PMS 라이브러리가 비어 있는 사용자를 페이지 진입 시점부터 가시화함. preview를 submit하기 전에 상단 banner로 "PMS Library Empty" + "Open PMS Import" CTA를 노출해 EMS fallback만 보고 끝나지 않고 실제 import 흐름으로 넘어가게 유도. preview 응답 후에는 기존의 "Import Next" callout이 같은 역할을 이어받음
- `services/api`는 GMS preview 생성과 feedback 저장 시 `recommendation_audit_log`(V37)에 user/recommendation/request id, model version, dataset fingerprint, SASRec 적용 여부, fallback reason, feedback type/target을 감사 row로 남김. 관리자 전용 `GET /api/v1/recommendations/admin/audit-log/recent` endpoint로 최근 로그를 조회 가능
- `services/ai`는 최소 FastAPI 스캐폴드와 추천 preview API 초안 생성 완료
- `infra/nginx`는 로컬/운영용 리버스 프록시 설정 템플릿 생성 완료
- Ubuntu 서버 기준 런북과 Docker/Nginx 템플릿 생성 완료
- 불필요한 서브프로젝트, 빌드 결과물, 의존성 폴더는 제외
- 새 프로젝트 전용 폴더 구조는 그대로 유지
- 웹 우선 개발 후 데스크탑 확장을 전제로 문서화 시작
- 현재 1차 구현/시험 서비스 환경은 `MacBook 로컬`, Ubuntu는 다음 이전 단계로 정리됨

아직 실제 provider 구현 전이라 사용자 플로우에 열지 않는 핵심 서비스 문서의 목표:

- Spotify 장기 세션 운영 고도화와 refresh 실패 관측/운영 정책
- TIDAL 실제 계정 E2E 회귀와 provider 운영 관측
- YouTube Music 실제 PMS provider 연동
- Apple Music 실제 PMS provider 연동은 개발자 계정 준비 후 진행
- Last.fm scrobble 주기 동기화와 시계열 취향 변화 반영
- 사용자 행동 데이터 기반 개인화 모델 업데이트
- 사용자별 음악 학습 모델 개발
- 정식 `PMS user library` 이후의 사용자 편집/평가 도메인 확장
- 사용자 제작 playlist와 추천 결과 저장/평가 도메인 확장
- 사이트 내부 음악 감상 행동 이벤트 저장
- EMS 외부 플레이리스트 수집, GMS 추천 통과, 사용자 평가의 PMS 환류
- 페이지 이동 간 유지되는 공통 음악 플레이어의 행동 이벤트 저장

## 1차 마감 작업

문서 정리, TIDAL PMS provider 오류 경계 보강, Discogs label enrichment, cold-start import 유도 UX, EMS acquisition 운영 확장까지 반영했습니다.
이제 남은 것은 새 기능 구현이 아니라 최종 검증입니다.

1. API/Web 전체 회귀 테스트
2. 실제 TIDAL 계정 import 회귀
3. EMS acquisition runbook 재실행, 가능하면 `editorial-expanded` preset 포함

## 로컬 데이터베이스 설정

### 전체 MacBook 스택 재시작

Docker DB/Redis, HTTPS 도메인 프록시, FastAPI AI, Spring Boot API, Vite 웹 서버를 함께 다시 시작하려면:

```bash
./infra/scripts/restart-macbook-stack.sh
```

실행 후 주요 URL:
- TIDAL SDK 테스트 페이지: `https://imapplepie20.tplinkdns.com/tidal-playlist-test`
- API health: `http://127.0.0.1:8081/actuator/health`
- AI health: `http://127.0.0.1:8000/health`
- 로컬 실행 로그: `tmp/local-stack/logs/`

### Docker로 PostgreSQL/Redis 실행

```bash
cd infra/docker
docker compose -f docker-compose.local-db.yml up -d
```

포트 설정:
- PostgreSQL: `127.0.0.1:5433` (다른 서비스와의 충돌 방지)
- Redis: `127.0.0.1:6379`

### Spring Boot database 프로필 실행

```bash
cd services/api
SPRING_PROFILES_ACTIVE=database DB_PORT=5433 ./gradlew bootRun
```

`database` 프로필은:
- PostgreSQL 연결 (port 5433)
- Flyway 마이그레이션 자동 실행
- JPA/Hibernate 영속성 활성화

### 데이터베이스 직접 접속

```bash
docker exec -it my-forever-music-local-postgres psql -U postgres -d my_forever_music
```

### 컨테이너 상태 확인

```bash
cd infra/docker
docker compose -f docker-compose.local-db.yml ps
```

## 로컬 DB 참고

- [infra/docker/README.md](/Users/woosungjo/music-space/my-forever-music/infra/docker/README.md)
- [infra/docker/docker-compose.local-db.yml](/Users/woosungjo/music-space/my-forever-music/infra/docker/docker-compose.local-db.yml)

## 구현 기준 문서

- [docs/PROJECT_KEY_SERVICE.md](/Users/woosungjo/music-space/my-forever-music/docs/PROJECT_KEY_SERVICE.md)
- [docs/architecture/MACBOOK_LOCAL_FIRST_PLAN.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/MACBOOK_LOCAL_FIRST_PLAN.md)
- [docs/architecture/SPOTIFY_OAUTH_SETUP.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/SPOTIFY_OAUTH_SETUP.md)
- [docs/api/README.md](/Users/woosungjo/music-space/my-forever-music/docs/api/README.md)
- [docs/api/AUTH_REGISTER_API.md](/Users/woosungjo/music-space/my-forever-music/docs/api/AUTH_REGISTER_API.md)
- [docs/api/PLATFORM_CONNECTION_ONBOARDING_API.md](/Users/woosungjo/music-space/my-forever-music/docs/api/PLATFORM_CONNECTION_ONBOARDING_API.md)
- [docs/api/PMS_PLAYLIST_IMPORT_API.md](/Users/woosungjo/music-space/my-forever-music/docs/api/PMS_PLAYLIST_IMPORT_API.md)
- [docs/api/PMS_TRACK_AUDIO_FEATURE_STORAGE.md](/Users/woosungjo/music-space/my-forever-music/docs/api/PMS_TRACK_AUDIO_FEATURE_STORAGE.md)
