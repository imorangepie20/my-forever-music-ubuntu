# EMS DB-First Display Implementation Plan

## Goal

EMS 화면에서 playlist/track 목록을 외부 provider 검색 응답으로 직접 표시하지 않고, 반드시 `ems_collected_*` DB에 저장된 뒤 조회된 데이터만 표시한다.

## Tasks

- [ ] Add regression checks that fail when EMS user pages render live provider search result cards or playback items.
- [ ] Change EMS main page search into a DB collection request/status flow and refresh DB-backed EMS sections after collection.
- [ ] Replace hard-coded Spotify Featured Charts cards with DB-backed collected playlist cards once they are stored.
- [ ] Make `/ems/search/playlists/:platformId/:externalPlaylistId` a storage bridge that redirects to `/playlists/ems/:playlistId`.
- [ ] Return the stored EMS playlist id from the backend playlist-track collection endpoint.
- [ ] Run focused web/API verification.
