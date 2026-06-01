# EMS TIDAL Home 전체 목록 수집 및 페이징 설계

## 목적

EMS 화면의 TIDAL 영역은 TIDAL 공개 home source별 playlist를 일부만 보여 주고 있다.
현재 화면은 source별 최대 12개로 잘라 표시하며, discovery scheduler도 query별 최대 10개만
수집한다. `Popular Playlists`처럼 실제 공개 목록이 100개를 넘는 source는 일부 playlist만
EMS DB에 저장된다.

이 작업의 목적은 다음과 같다.

- TIDAL 공개 home source가 제공하는 playlist 목록을 끝까지 조회한다.
- provider 응답을 화면에 직접 노출하지 않고 먼저 EMS DB에 저장한다.
- EMS 화면은 저장된 목록을 source별로 12개씩 독립 페이징한다.
- 많은 playlist의 track 수집은 background batch로 분리하여 discovery 요청 timeout과
  provider 과부하 가능성을 낮춘다.

## 대상 source

TIDAL 공개 home source는 기존 네 종류를 유지한다.

| source id | 화면 제목 |
| --- | --- |
| `POPULAR_PLAYLISTS` | `Popular Playlists` |
| `THE_HITS` | `The Hits` |
| `POPULAR_MIXES` | `Popular Mixes` |
| `FROM_OUR_EDITORS` | `From our editors` |

2026-06-01 KR 공개 endpoint 확인 결과는 다음과 같았다.

- `POPULAR_PLAYLISTS`: 164개
- `THE_HITS`: 10개
- `POPULAR_MIXES`: 0개
- `FROM_OUR_EDITORS`: 38개

provider 목록은 수시로 달라질 수 있으므로 이 개수는 구현 상수로 사용하지 않는다.

## 핵심 원칙

EMS 사용자 화면에 표시하는 playlist는 항상 `ems_collected_playlist` 저장본이다.
TIDAL provider 호출 결과를 React 화면에 바로 전달하지 않는다.

목록 동기화와 track 보강은 분리한다.

1. 목록 동기화는 playlist metadata를 빠르게 EMS DB에 upsert한다.
2. track 보강은 별도 background batch가 아직 track link가 없는 TIDAL home playlist를
   제한된 개수씩 처리한다.
3. track 보강 실패는 기존 playlist metadata를 삭제하지 않는다.

같은 playlist가 여러 TIDAL home source에 포함될 수 있다. 기존
`ems_collected_playlist.search_query` 단일 값만으로 source 소속을 표현하면 마지막
동기화 source가 이전 source를 덮어쓴다. 따라서 TIDAL home source membership은 별도
매핑 테이블에 저장한다.

## backend 구조

### TIDAL 공개 목록 전체 순회

`TidalWebApiClient`에 TIDAL 공개 home source 전체를 읽는 메서드를 둔다.

- endpoint: `/v2/home/pages/{sourceId}/view-all`
- page size: 50
- 첫 요청: `offset=0`
- 다음 요청: 이전 응답에서 받은 item 개수만큼 `offset` 증가
- 종료 조건: 빈 page 또는 50개보다 작은 page
- 안전 제한: 최대 20 page. 20번째 page도 50개로 가득 차면 조용히 자르지 않고 실패로
  기록한다.

응답에 playlist가 아닌 `MIX` 등 다른 item이 섞여 있어도 offset은 원본 item 개수를
기준으로 증가시킨다. 저장 대상은 `PLAYLIST` item만 유지한다.

### metadata sync

기존 `EmsPublicPlaylistDiscoveryScheduler`는 TIDAL home source에 대해 query별 10개 제한을
적용하지 않는다. 각 source의 전체 playlist metadata를 순회하고
`ems_collected_playlist`에 upsert한다.

`ems_collected_playlist_source` 테이블은 playlist와 source membership을 저장한다.

| column | 의미 |
| --- | --- |
| `ems_collected_playlist_id` | EMS playlist FK |
| `source_platform` | `tidal` |
| `collection_source` | `public_pool` |
| `source_id` | `POPULAR_PLAYLISTS` 등 TIDAL home source |
| `collected_at` | source membership을 마지막으로 확인한 시간 |

unique key는 `(ems_collected_playlist_id, source_platform, collection_source, source_id)`다.
source 하나의 provider 목록 전체를 정상적으로 받은 뒤 기존 membership을 지우고 현재
목록을 다시 upsert한다. provider 요청이 실패하면 기존 membership은 유지한다.

Spotify discovery와 일반 TIDAL search는 기존 제한과 동작을 유지한다.

### track background batch

새로운 `EmsTidalHomeTrackBackfillScheduler`를 추가한다.

- 대상: `ems_collected_playlist_source`에 TIDAL home membership이 있고 아직
  playlist-track link가 없는 playlist
- 기본 batch size: 3
- 기본 실행 간격: 60초
- 각 playlist는 TIDAL 공개 track endpoint로 전체 track을 조회하여 기존 EMS track과 link
  테이블에 저장한다.
- playlist 하나의 실패가 다른 playlist 처리를 중단시키지 않는다.
- 실패는 기존 `ApplicationErrorLogService`에 scheduler error로 기록한다.

이미 track link가 있는 기존 playlist는 이 작업에서 반복 조회하지 않는다. 향후 stale
metadata refresh가 필요해지면 별도 정책으로 추가한다.

### DB 기반 페이징 API

다음 endpoint를 추가한다.

```text
GET /api/v1/ems/collection/tidal-home/playlists
  ?source_id=POPULAR_PLAYLISTS
  &page=0
  &size=12
```

규칙:

- `source_id`는 대상 네 source 중 하나만 허용한다.
- `page`는 0 이상이다.
- `size` 기본값은 12이며 최대값도 12다.
- 정렬은 source membership의 `collected_at desc`, `ems_collected_playlist_id desc` 순서다.
- provider를 호출하지 않고 EMS DB만 조회한다.

응답은 다음 정보를 포함한다.

```json
{
  "service": "api",
  "status": "ok",
  "generated_at": "2026-06-01T00:00:00Z",
  "source_id": "POPULAR_PLAYLISTS",
  "page": 0,
  "size": 12,
  "total_elements": 164,
  "total_pages": 14,
  "playlists": []
}
```

## frontend 구조

`TidalHomePageSections`는 범용 `/playlists?platform_id=tidal` endpoint에서 최대 50개를
받아 client-side로 자르는 방식을 제거한다.

각 source는 독립적인 page state를 가진다.

- 최초 page: 0
- page size: 12
- 각 source는 전용 DB 페이징 API를 호출한다.
- 한 source의 page 이동은 다른 source의 현재 page에 영향을 주지 않는다.
- 총 page가 1 이하이면 이전/다음 controls를 숨긴다.
- 빈 source는 section 자체를 표시하지 않는다.
- loading 중에는 기존 card grid와 같은 크기의 skeleton을 표시한다.
- page 변경 중에는 해당 source section만 loading 상태가 된다.

페이징 controls는 section header 오른쪽에 배치한다.

```text
[<]  2 / 14  [>]
```

좌우 이동은 `ChevronLeft`, `ChevronRight` icon button과 tooltip을 사용한다. 첫 page에서는
이전 버튼, 마지막 page에서는 다음 버튼을 비활성화한다.

## 오류 처리

- provider 전체 목록 동기화 실패는 기존 discovery failure와 application error log에 남긴다.
- track background batch 실패는 playlist 단위로 기록하고 다음 대상을 계속 처리한다.
- frontend의 특정 source 조회 실패는 다른 TIDAL section 렌더링을 막지 않는다.
- 실패한 section에는 사용자에게 provider 내부 상태를 노출하지 않고 저장 목록을 불러오지
  못했다는 짧은 안내만 표시한다.

## 테스트

### backend

- TIDAL 공개 목록 client가 `offset`을 증가시키며 여러 page를 끝까지 읽는지 검증한다.
- page에 `MIX`가 섞여 있어도 offset 증가와 playlist filtering이 정확한지 검증한다.
- metadata sync가 TIDAL home source 전체 metadata를 저장하고 track 조회를 즉시 실행하지
  않는지 검증한다.
- 동일 playlist가 여러 TIDAL home source에 포함되어도 membership이 각각 유지되는지
  검증한다.
- track background batch가 link 없는 대상만 제한된 개수로 처리하는지 검증한다.
- 한 playlist track 조회 실패 후 다음 playlist 처리가 계속되는지 검증한다.
- DB 페이징 API가 source filter, 12개 제한, total metadata를 반환하는지 검증한다.
- 허용되지 않은 `source_id`, 음수 `page`, 12를 넘는 `size`를 거부하는지 검증한다.

### frontend

- TIDAL section이 전용 DB 페이징 API를 사용하는지 regression harness로 확인한다.
- source별 page state가 독립적으로 유지되는지 확인한다.
- 첫 page와 마지막 page에서 이전/다음 버튼이 올바르게 비활성화되는지 확인한다.
- 빈 source가 렌더링되지 않는지 확인한다.

## 범위 제외

- TIDAL home source 종류를 관리자 화면에서 동적으로 편집하는 기능
- 기존 track link가 있는 playlist의 주기적 stale refresh
- Spotify, FLO, Melon section의 페이징 변경
- provider 응답을 browser에 직접 전달하는 live browse 기능
