# Platform OAuth API

작성일: `2026-05-04`

이 문서는 플랫폼 연결 온보딩에서 사용하는 실제 provider OAuth 시작/완료 공개 API 계약입니다.

## 목적

- 가입 사용자의 preferred streaming platform을 실제 OAuth로 연결
- Spotify authorization code를 token endpoint와 교환해 credential로 저장
- OAuth 설정이 없거나 provider가 아직 구현되지 않은 플랫폼은 가짜 승인 화면으로 대체하지 않고 실패시키기

## 엔드포인트

- `POST /api/v1/platforms/oauth/start`
- `POST /api/v1/platforms/oauth/complete`

## OAuth 시작

### 요청

```json
{
  "user_id": "user-001",
  "platform_id": "spotify"
}
```

### 응답

```json
{
  "service": "api",
  "status": "authorization_pending",
  "generated_at": "2026-05-04T08:00:00Z",
  "user": {
    "user_id": "user-001",
    "display_name": "Forever Listener",
    "email": "listener@example.com"
  },
  "authorization": {
    "state": "oauth-4bdb2f3f-72e3-4aa0-9f04-6df2e61f9a5d",
    "platform_id": "spotify",
    "platform_display_name": "Spotify",
    "authorization_mode": "spotify-pkce-draft",
    "authorization_channel": "external_browser_redirect",
    "requested_scopes": [
      "user-read-email",
      "playlist-read-private",
      "playlist-read-collaborative"
    ],
    "expires_at": "2026-05-04T08:10:00Z",
    "callback_path": "http://localhost:5173/platforms/oauth/callback?state=oauth-4bdb2f3f-72e3-4aa0-9f04-6df2e61f9a5d",
    "external_authorization_url": "https://accounts.spotify.com/authorize?...",
    "redirect_uri": "http://localhost:5173/platforms/oauth/callback"
  }
}
```

## OAuth 완료

Spotify가 `redirect_uri`로 돌려준 `code`와 `state`를 웹앱이 넘깁니다.

### 요청

```json
{
  "user_id": "user-001",
  "platform_id": "spotify",
  "state": "oauth-4bdb2f3f-72e3-4aa0-9f04-6df2e61f9a5d",
  "authorization_code": "provider-code-from-spotify"
}
```

### 응답

```json
{
  "service": "api",
  "status": "authorization_completed",
  "processed_at": "2026-05-04T08:01:00Z",
  "authorization": {
    "state": "oauth-4bdb2f3f-72e3-4aa0-9f04-6df2e61f9a5d",
    "platform_id": "spotify",
    "platform_display_name": "Spotify",
    "authorization_mode": "spotify-pkce-draft",
    "requested_scopes": [
      "user-read-email",
      "playlist-read-private",
      "playlist-read-collaborative"
    ],
    "completed_at": "2026-05-04T08:01:00Z"
  },
  "connection": {
    "user_id": "user-001",
    "platform_id": "spotify",
    "connected": true,
    "connection_status": "connected",
    "connection_mode": "spotify-pkce-draft",
    "external_account_label": "Forever Listener Spotify account",
    "scope_summary": "user-read-email, playlist-read-private, playlist-read-collaborative",
    "sync_ready": true,
    "connected_at": "2026-05-04T08:01:00Z"
  },
  "next_step": {
    "path": "/pms",
    "message": "Preferred platform connected. Continue to PMS import."
  }
}
```

## 현재 구현 메모

- 현재 사용자 플로우에서 OAuth start가 가능한 PMS source는 `spotify`입니다. `tidal`은 `/api/v1/platforms/oauth/tidal/device/*` device authorization 경로를 사용합니다.
- `SPOTIFY_OAUTH_ENABLED`, `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`, `SPOTIFY_REDIRECT_URI` 설정이 필요합니다.
- Spotify OAuth가 설정되지 않으면 내부 승인 화면이나 mock credential로 대체하지 않고 `400 Bad Request`로 실패합니다.
- OAuth session의 `approval_code`는 외부 provider flow에서는 사용하지 않으며, DB에서는 nullable입니다.
- TIDAL 표준 OAuth 2.1 + PKCE 경로는 provider 승인 오류가 반복되어 사용자 연결 경로에서 제외하고, device authorization 경로를 사용합니다. `POST /api/v1/platforms/oauth/start`에 `platformId=tidal`을 보내면 `400`으로 device-start 경로를 안내하며 거절합니다.
- TIDAL device authorization의 현재 허용 scope는 `r_usr,w_usr,w_sub`입니다. `user.read`, `collection.read`, `playlists.read`, `playback`, `entitlements.read`, `r_stream`를 device-start에 섞으면 provider가 `invalid_scope`로 거절합니다. `TIDAL_SCOPES`에 허용 목록 밖 값을 넣으면 무시되며 서버 로그에 경고가 남습니다.
- TIDAL 설정이 없으면 내부 승인 화면이나 mock credential로 대체하지 않고 실패합니다.
- YouTube Music은 TIDAL 안정화 이후 진행하고, Apple Music은 개발자 계정 준비 전까지 보류합니다.
- TIDAL 설정 키는 `TIDAL_OAUTH_ENABLED`, `TIDAL_CLIENT_ID`, `TIDAL_CLIENT_SECRET`, `TIDAL_REDIRECT_URI`, `TIDAL_AUTHORIZATION_URI`, `TIDAL_TOKEN_URI`, `TIDAL_API_BASE_URI`, `TIDAL_COUNTRY_CODE`, `TIDAL_SCOPES`입니다.
- TIDAL 공식 Authorization 문서: https://developer.tidal.com/documentation/api-sdk/api-sdk-authorization

## 다음 연결 지점

1. Spotify OAuth 운영 에러와 사용자 재시도 메시지 정교화
2. TIDAL 실제 계정 PMS import 반복 검증과 운영 관측 보강
3. provider별 scope/권한 안내 UI 분리
4. YouTube Music 실제 provider 구현
