# 메인 페이지 Spotify Featured Charts 섹션 설계

작성일: `2026-06-01`

## 목적

EMS에 이미 저장된 Spotify Featured Charts 플레이리스트를 메인 페이지에서도 바로 탐색할 수 있게 한다.

## 범위

- 메인 페이지에 `Spotify Featured Charts` 전용 섹션을 추가한다.
- 신규 provider 호출이나 신규 API는 추가하지 않는다.
- `GET /api/v1/ems/collection/playlists?platform_id=spotify` 응답에서
  `collection_source === "spotify_featured_charts"`인 플레이리스트만 사용한다.
- 최대 4개 카드를 표시한다.
- 데이터가 없거나 조회에 실패하면 섹션을 숨긴다.
- 카드를 선택하면 기존 EMS 플레이리스트 상세 경로
  `/playlists/ems/{playlistId}`로 이동한다.

## 배치

메인 페이지의 전체 EMS 인기 플레이리스트 다음, TIDAL 공개 플레이리스트 전에 배치한다.

```text
Latest Tracks
Popular playlists
Spotify Featured Charts
Popular playlists on TIDAL
Algorithm Intro
...
```

## UI

기존 `PopularTidalPlaylistsSection` 카드 패턴을 따른다.

- 로딩 중: 4개의 square skeleton 카드
- 준비됨: cover image, title, curator, track count
- 빈 데이터 또는 오류: 렌더링하지 않음
- 카드 grid: mobile 2열, medium 4열

## 데이터 원칙

외부 Spotify 응답을 메인 화면에서 직접 조회하지 않는다. EMS 수집 작업이 DB에 저장한
플레이리스트만 표시한다. 이로써 사용자 화면은 항상 우리 DB를 기준으로 렌더링한다.

## 검증

- 회귀 하네스가 메인 페이지에서 새 전용 섹션 import와 렌더링을 확인한다.
- 회귀 하네스가 새 섹션에서 Spotify 플랫폼 필터와
  `spotify_featured_charts` 출처 필터 사용을 확인한다.
- `npm run test:product-flow`, `npm run build`, `npm run lint`를 실행한다.
