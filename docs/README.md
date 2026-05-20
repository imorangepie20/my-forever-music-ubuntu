# Docs Index

이 디렉토리의 중심 문서는 [PROJECT_GUIDE.md](/Users/woosungjo/music-space/my-forever-music/docs/PROJECT_GUIDE.md) 입니다.

## 우선순위 문서

- [PROJECT_GUIDE.md](/Users/woosungjo/music-space/my-forever-music/docs/PROJECT_GUIDE.md): 새 세션과 새 작업자가 먼저 읽어야 하는 통합 가이드
- [PROJECT_KEY_SERVICE.md](/Users/woosungjo/music-space/my-forever-music/docs/PROJECT_KEY_SERVICE.md): 서비스 핵심 기능과 장기 제품 흐름의 원문 정의
- [product/USER_MUSIC_HOME_VISION.md](/Users/woosungjo/music-space/my-forever-music/docs/product/USER_MUSIC_HOME_VISION.md): 사용자가 사이트에 머무는 이유, 플랫폼 이동 후에도 보존되는 음악 취향 홈, 플랫폼 연동 이후 사용자별 음악 학습 모델 개발 순서 정의
- [product/MUSIC_DISCOVERY_AND_LISTENING_UX.md](/Users/woosungjo/music-space/my-forever-music/docs/product/MUSIC_DISCOVERY_AND_LISTENING_UX.md): PMS 중심 감상 경험, EMS Discovery Pool, 공개 플레이리스트 주기 수집/랜덤 노출 UI 구성 기준
- [architecture/REAL_IMPLEMENTATION_POLICY.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/REAL_IMPLEMENTATION_POLICY.md): mock/sandbox 없이 실제 구현된 기능만 사용자 플로우에 노출하는 기준
- [architecture/MACBOOK_LOCAL_FIRST_PLAN.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/MACBOOK_LOCAL_FIRST_PLAN.md): 현재는 MacBook 로컬에서 구현/시험 후 Ubuntu로 이전하는 실행 전략
- [architecture/REAL_IMPLEMENTATION_POLICY.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/REAL_IMPLEMENTATION_POLICY.md): MacBook 시험 서비스에서 실제 API/저장소/provider만 기본 사용자 경로로 쓰는 정책
- [architecture/REAL_IMPLEMENTATION_POLICY.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/REAL_IMPLEMENTATION_POLICY.md): 오류를 우회/회피/임시 처리하지 않고 실패 경계와 근본 원인을 수정하는 전 프로젝트 공통 기준
- [architecture/AUDIO_FEATURE_PROVIDER_STRATEGY.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/AUDIO_FEATURE_PROVIDER_STRATEGY.md): Spotify Development Mode 제약 이후 provider-neutral 오디오 특성 전략과 legacy `spotify_*` 호환 규칙
- [architecture/PERSONALIZED_RECOMMENDATION_MODEL_PLAN.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/PERSONALIZED_RECOMMENDATION_MODEL_PLAN.md): 메타데이터와 행동 신호 기반 개인화 음악 추천 모델 기획, feature store, scoring engine, feedback loop 설계
- [architecture/TECH_STACK.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/TECH_STACK.md): 현재 채택 기술 스택
- [architecture/SPOTIFY_OAUTH_SETUP.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/SPOTIFY_OAUTH_SETUP.md): Spotify OAuth Redirect URI, env, HTTPS 테스트 기준
- [architecture/HTTPS_DOMAIN_DEV_SETUP.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/HTTPS_DOMAIN_DEV_SETUP.md): `imapplepie20.tplinkdns.com` 기준 HTTPS reverse proxy와 Certbot 적용 절차
- [architecture/DESKTOP_APP_STRATEGY.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/DESKTOP_APP_STRATEGY.md): 웹 이후 Windows 데스크탑 확장 전략
- [architecture/UBUNTU_SERVER_SETUP_GUIDE.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/UBUNTU_SERVER_SETUP_GUIDE.md): Ubuntu 서버 최초 설치부터 개발 환경 준비까지의 상세 가이드
- [architecture/UBUNTU_SERVER_RUNBOOK.md](/Users/woosungjo/music-space/my-forever-music/docs/architecture/UBUNTU_SERVER_RUNBOOK.md): Ubuntu 서버 운영/개발 방향 요약
- [decisions/ADR-001-backend-stack.md](/Users/woosungjo/music-space/my-forever-music/docs/decisions/ADR-001-backend-stack.md): 메인 백엔드가 Spring Boot로 확정된 이유

## 제품 정의 문서

- [PROJECT_KEY_SERVICE.md](/Users/woosungjo/music-space/my-forever-music/docs/PROJECT_KEY_SERVICE.md): 플랫폼 연동, 오디오 특성 분석, PMS/EMS/GMS 환류, 플레이어 유지 요구사항을 담은 핵심 서비스 정의
- [product/USER_MUSIC_HOME_VISION.md](/Users/woosungjo/music-space/my-forever-music/docs/product/USER_MUSIC_HOME_VISION.md): 회원의 반복 사용 목적, 사용자 소유 PMS 라이브러리, 사용자별 음악 학습 모델, 평가/playlist/playback 중심 체류 경험 정의
- [product/MUSIC_DISCOVERY_AND_LISTENING_UX.md](/Users/woosungjo/music-space/my-forever-music/docs/product/MUSIC_DISCOVERY_AND_LISTENING_UX.md): 사용자의 목적을 `취향 추천 -> PMS 감상`으로 고정하고 EMS 공개 playlist 풀/GMS UI를 보조 흐름으로 정리하는 화면 구성 문서

## 서비스 가이드

- [services/api/README.md](/Users/woosungjo/music-space/my-forever-music/services/api/README.md): Spring Boot 메인 API 가이드
- [services/ai/README.md](/Users/woosungjo/music-space/my-forever-music/services/ai/README.md): FastAPI AI 서비스 가이드

## API 문서

- [api/README.md](/Users/woosungjo/music-space/my-forever-music/docs/api/README.md): `docs/api` 전체 진입점과 읽는 순서
- [api/AUTH_REGISTER_API.md](/Users/woosungjo/music-space/my-forever-music/docs/api/AUTH_REGISTER_API.md): 회원가입과 기본 플랫폼 선택 계약
- [api/AUTH_LOGIN_API.md](/Users/woosungjo/music-space/my-forever-music/docs/api/AUTH_LOGIN_API.md): 기존 계정 로그인과 온보딩 복원 계약
- [api/PLATFORM_CONNECTION_ONBOARDING_API.md](/Users/woosungjo/music-space/my-forever-music/docs/api/PLATFORM_CONNECTION_ONBOARDING_API.md): 가입 직후 플랫폼 연결 상태와 connect/disconnect 계약
- [api/LASTFM_SIGNAL_PREVIEW_API.md](/Users/woosungjo/music-space/my-forever-music/docs/api/LASTFM_SIGNAL_PREVIEW_API.md): Last.fm 공개 사용자명 기준 청취 신호 preview 계약
- [api/LASTFM_PROFILE_CONNECTION_API.md](/Users/woosungjo/music-space/my-forever-music/docs/api/LASTFM_PROFILE_CONNECTION_API.md): Last.fm 사용자명을 계정에 저장하는 signal profile 연결 계약
- [api/LASTFM_SCROBBLE_SYNC_API.md](/Users/woosungjo/music-space/my-forever-music/docs/api/LASTFM_SCROBBLE_SYNC_API.md): Last.fm 최근 scrobble 저장과 snapshot 재사용 계약
- [api/PLATFORM_OAUTH_API.md](/Users/woosungjo/music-space/my-forever-music/docs/api/PLATFORM_OAUTH_API.md): 실제 provider OAuth 시작/callback 완료 계약
- [api/PMS_PLAYLIST_IMPORT_API.md](/Users/woosungjo/music-space/my-forever-music/docs/api/PMS_PLAYLIST_IMPORT_API.md): 실제 플랫폼 playlist import와 PMS 적재 계약
- [api/AI_RECOMMENDATION_PREVIEW.md](/Users/woosungjo/music-space/my-forever-music/docs/api/AI_RECOMMENDATION_PREVIEW.md): AI 추천 preview 내부 계약
- [api/AUDIO_FEATURE_COMPLETION_ADMIN_API.md](/Users/woosungjo/music-space/my-forever-music/docs/api/AUDIO_FEATURE_COMPLETION_ADMIN_API.md): PMS/EMS 누락 오디오 특성 completion queue 관리자 적재/조회/처리와 opt-in scheduler 계약
- [api/EMS_WORKSPACE_ANALYSIS_API.md](/Users/woosungjo/music-space/my-forever-music/docs/api/EMS_WORKSPACE_ANALYSIS_API.md): EMS workspace 추천값 분석 계약
- [api/TIDAL_PLAYBACK_TARGET_API.md](/Users/woosungjo/music-space/my-forever-music/docs/api/TIDAL_PLAYBACK_TARGET_API.md): 타 플랫폼 트랙을 TIDAL 재생 target으로 resolve 하고 full stream 재생을 시작하는 계약
- [api/GMS_RECOMMENDATION_PREVIEW_API.md](/Users/woosungjo/music-space/my-forever-music/docs/api/GMS_RECOMMENDATION_PREVIEW_API.md): Spring Boot API의 GMS preview 엔드포인트 초안
- [api/PLATFORM_CATALOG_API.md](/Users/woosungjo/music-space/my-forever-music/docs/api/PLATFORM_CATALOG_API.md): 플랫폼 선택과 제품 역할 설명용 카탈로그 계약
- [api/PMS_TRACK_AUDIO_FEATURE_STORAGE.md](/Users/woosungjo/music-space/my-forever-music/docs/api/PMS_TRACK_AUDIO_FEATURE_STORAGE.md): PMS track의 provider-neutral 오디오 특성 저장 기준과 legacy `spotify_*` 호환 규칙
- [api/PMS_WORKSPACE_BOOTSTRAP_API.md](/Users/woosungjo/music-space/my-forever-music/docs/api/PMS_WORKSPACE_BOOTSTRAP_API.md): PMS 화면 bootstrap 데이터 계약

## 참고 문서

- [streaming-platforms-api/spotify.md](/Users/woosungjo/music-space/my-forever-music/docs/streaming-platforms-api/spotify.md)
- [streaming-platforms-api/applemusic.md](/Users/woosungjo/music-space/my-forever-music/docs/streaming-platforms-api/applemusic.md)
- [streaming-platforms-api/tidal.md](/Users/woosungjo/music-space/my-forever-music/docs/streaming-platforms-api/tidal.md)
- [streaming-platforms-api/reccobeats.md](/Users/woosungjo/music-space/my-forever-music/docs/streaming-platforms-api/reccobeats.md): ReccoBeats 조회형/업로드형 오디오 특성 API, 실제 호출 확인 결과, 현재 PMS 저장 기준과의 충돌 메모
