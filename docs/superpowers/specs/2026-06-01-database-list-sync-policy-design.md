# Database List Sync Policy Design

## Purpose

PMS, EMS, Public curation 화면에서 "목록"이라는 말이 서로 다른 의미로 섞이지 않도록 DB 갱신 기준을 고정한다.

핵심 원칙은 하나다.

- 화면에 표시되는 playlist/track 목록은 반드시 우리 DB에 저장된 뒤 조회된 데이터여야 한다.

외부 provider/API/scraper 결과는 화면 표시용 데이터가 아니다. 화면에 보여야 한다면 먼저 sync/import/collect/generate 경계를 통과해 PostgreSQL에 저장하고, 저장된 row를 다시 읽어 표시한다.

## Vocabulary

### DB 목록

우리 PostgreSQL에 저장된 확정 상태다.

- PMS: `pms_user_playlist`, `pms_user_track`, `pms_user_playlist_track`
- EMS: `ems_collected_playlist`, `ems_collected_track`, playlist-track link
- Public curation: `public_curation_playlist`, `public_curation_playlist_track`

화면에서는 "보관함", "저장됨", "마지막 DB 갱신" 같은 표현을 쓴다.

### 외부 원천 데이터

TIDAL, Spotify, FLO, Melon, Spotify Featured Charts 등 외부 provider/API/scraper에서 현재 읽어온 후보 상태다.

이 데이터는 화면 표시용 목록이 아니다. 외부 원천 데이터가 화면에 필요하면 먼저 DB에 저장한 뒤 DB 목록으로 표시한다.

### Sync

외부 원천 데이터 또는 모델 결과를 우리 DB에 저장하는 명시적 작업이다.

예:

- 플랫폼 playlist를 PMS에 저장 또는 갱신
- EMS discovery 결과를 EMS pool에 저장
- Public curation draft를 생성/발행

## Product Rules

### Rule 1. Page display reads DB only

사용자가 PMS/EMS/GMS/Public page에서 보는 playlist/track 목록은 모두 우리 DB에서 읽는다. 외부 provider 응답을 화면 목록으로 직접 렌더링하지 않는다.

### Rule 2. External fetch is ingestion, not display

외부 provider를 호출하는 작업은 ingestion 단계다. 결과를 화면에 보여야 한다면 같은 명령 안에서 DB에 저장하고, 저장된 DB 목록을 다시 조회해서 표시한다.

### Rule 3. Save/Sync writes DB

`PMS에 저장/갱신`, `EMS POOL 수집/갱신`, `GMS 승인 저장`, `공개 리스트 생성/발행` 같은 명령만 DB를 바꾼다.

### Rule 4. Same external playlist updates same DB playlist

PMS import에서 같은 `source_platform + external_playlist_id`를 다시 저장하면 기존 PMS playlist를 갱신한다.

- 트랙 목록과 순서는 외부 원본 스냅샷을 그대로 반영한다.
- `last_synced_at` 또는 `imported_at`을 최신 시각으로 갱신한다.
- 사용자가 만든 personal PMS playlist와 GMS approved playlist는 건드리지 않는다.

### Rule 5. Playback stream is not list sync

TIDAL/YouTube playback target, stream URL, quality fallback은 재생 시점의 runtime 해석이다. 목록 DB 갱신과 분리한다.

## PMS and GMS Screen Rule

PMS와 GMS는 별도 예외 없이 DB 기준이다.

- PMS 화면은 `pms_user_playlist`, `pms_user_track`, `pms_user_playlist_track` 또는 PMS catalog/personal DB row에서만 목록을 표시한다.
- GMS 화면은 GMS preview/approval 결과가 DB에 저장된 뒤 표시한다.
- 외부 provider playlist를 PMS에 반영하려면 import command가 먼저 DB를 갱신해야 한다.

## PMS Screen Design

PMS 화면은 우리 DB에 저장된 playlist만 보여준다.

표시 문구:

- 제목: `내 PMS 보관함`
- 설명: `우리 DB에 저장된 개인 음악 기준점입니다.`
- 카드 메타: `마지막 DB 갱신: yyyy-MM-dd HH:mm`

## EMS Screen Design

EMS는 이 원칙을 가장 엄격히 적용한다.

- EMS 페이지에 보이는 playlist/track은 모두 `ems_collected_*` DB row에서 읽는다.
- Spotify Featured Charts, FLO, Melon, TIDAL, Spotify search 결과도 화면에 보이기 전에 EMS POOL에 저장한다.
- EMS page의 버튼은 외부 live list를 보여주는 버튼이 아니라 `EMS POOL 수집/갱신` 버튼이다.
- 수집이 끝나면 EMS page는 DB를 다시 조회한다.

표시 문구:

- 제목: `EMS POOL`
- 설명: `외부 원천에서 수집해 우리 DB에 저장한 추천 후보입니다.`
- 버튼: `EMS POOL 수집/갱신`
- 카드 메타: `마지막 DB 저장: yyyy-MM-dd HH:mm`

## Public Curation Screen Design

관리 화면의 저장 목록은 항상 DB 기준이다.

- 초안 생성: 모델 결과를 DB draft로 저장
- 발행: draft/published 상태를 DB에 반영
- 삭제: DB에서 숨김 또는 삭제 정책에 따라 제거
- 목록 새로고침: DB 목록을 다시 조회

공유 페이지는 published 상태의 DB playlist만 읽는다.

## API Boundary

### Read APIs

DB 조회:

- `GET /api/v1/pms/workspace/bootstrap`
- `GET /api/v1/pms/personal-playlists/bootstrap`
- `GET /api/v1/ems/collection/playlists...`
- `GET /api/v1/public-curations/admin/playlists`
- `GET /api/v1/public-curations/share/{slug}`

외부 원천 조회:

- `GET /api/v1/pms/import/bootstrap`
- EMS 외부 search/detail APIs

이 API들은 사용자 화면 목록을 직접 만들면 안 된다. 표시가 필요한 결과는 먼저 DB 저장 API 또는 ingestion command를 거쳐야 한다.

### Write APIs

DB 갱신:

- `POST /api/v1/pms/import/playlists`
- `POST /api/v1/pms/import/preferred-platform`
- EMS queue/process/save APIs
- Public curation generate/publish/delete APIs

## Error Handling

- 외부 provider refresh 실패는 DB 목록을 지우지 않는다.
- sync 실패는 기존 DB snapshot을 유지한다.
- provider token 만료는 reconnect required로 명확히 표시한다.
- partial import가 생기면 저장된 playlist 수, track 수, 실패 playlist를 응답에 포함한다.

## Testing

### API tests

- 외부 원천 조회 API는 사용자 화면 목록을 직접 만들지 않는다.
- EMS에 표시되는 외부 수집 결과는 먼저 `ems_collected_*` DB row로 저장된다.
- 같은 external playlist를 다시 import하면 같은 DB playlist가 갱신된다.
- personal/GMS playlist는 platform re-sync에 의해 삭제되거나 덮어써지지 않는다.

### Web harness

- PMS/GMS/Public 화면은 DB 기준 문구를 표시한다.
- EMS 화면에는 외부 live 후보 목록 문구가 없다.
- EMS 화면의 playlist/track list는 `ems_collected_*` 조회 API 결과만 사용한다.
- 저장된 playlist/card에 마지막 DB 갱신 또는 저장 시각이 표시된다.

## Out of Scope

- 외부 플랫폼과의 자동 주기 sync scheduler
- user-facing conflict resolver
- playback stream URL 저장
- provider별 playlist diff visualization

이 기능들은 이후 단계에서 별도 spec으로 다룬다.
