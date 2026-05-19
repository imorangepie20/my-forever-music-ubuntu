# PMS Unified Library Design

작성일: `2026-05-19`

## 1. 목적

현재 `PMS` 페이지는 `현재 workspace`, `personal playlists`, `platform import queue`를 한 화면에 병렬로 놓은 운영형 화면에 가깝다.

이 구조는 제품 문서가 정의한 `사용자 소유의 음악 취향 홈`과 다르다.  
특히 아래 두 조건이 화면의 중심이 되어야 한다.

- 사용자가 구독 플랫폼에서 가져온 playlist
- 사이트가 추천했고 사용자가 채택한 playlist

이번 설계의 목적은 `PMS`를 위 두 종류의 playlist가 합쳐진 `최종 사용자 라이브러리 홈`으로 재정의하는 것이다.

## 2. 현재 문제

현재 구현은 아래 이유로 제품 목표와 어긋난다.

1. `PMS workspace bootstrap`이 여러 source를 합치지 않고, 첫 번째 non-empty source 하나만 선택한다.
2. `GMS`에서 사용자가 저장한 playlist는 `PmsPersonalPlaylistStore`에 들어가며, `PMS` 메인 library가 아니라 `personal playlist`처럼 취급된다.
3. 프론트 `PmsPage.tsx`는 `workspace.playlistId` 변경 시 bootstrap 재조회가 보장되지 않아, 선택된 playlist와 track shelf가 어긋날 수 있다.
4. `Platform Import Queue`가 `이미 가져온 playlist`까지 다시 강조해, 메인 라이브러리와 역할이 겹친다.

## 3. 목표 상태

`PMS` 메인 라이브러리는 `사용자 소유의 최종 playlist 홈`으로 동작해야 한다.

메인 shelf에 포함하는 대상:

- 구독 플랫폼에서 가져와 `PMS user library`에 들어온 playlist
- `GMS`에서 사용자가 채택한 playlist

메인 shelf에 포함하지 않는 대상:

- 아직 import 전인 외부 playlist 후보
- 사용자가 직접 만든 일반 personal playlist
- 개별 저장 track 그 자체

개별 저장 track은 사용자당 `1개 고정` 자동 playlist에 모은다.  
현재 기준 canonical 자동 playlist는 `Saved from GMS` 성격의 personal playlist다.

## 4. 선택한 접근

이번 단계에서는 저장 구조를 전면 교체하지 않고, `통합 읽기 모델`을 먼저 추가한다.

선택 이유:

- 제품 의미를 즉시 맞출 수 있다.
- 현재 저장 경로를 한 번에 뒤엎지 않아도 된다.
- 프론트와 bootstrap 계약을 먼저 안정화할 수 있다.
- 이후 필요하면 `GMS approved playlist`를 실제 `PMS user library` 저장 경로로 승격할 수 있다.

이번 단계에서 하지 않는 일:

- `GMS approved playlist`를 `PMS user library` 테이블/스토어로 물리 이전
- 신규 canonical 테이블 추가
- playlist 편집/병합 기능 추가
- GMS 저장 UX 전체 재설계

## 5. 데이터 모델 해석

### 5-1. PMS Main Library

`PMS Main Library`는 아래 두 source를 합친 읽기 모델이다.

- `PMS user library`
- `PmsPersonalPlaylistStore` 안의 `GMS approved playlist`

여기서 `GMS approved playlist`는 저장 위치가 personal store에 있더라도, 읽을 때는 `PMS` 메인 shelf에 포함한다.

### 5-2. Personal Playlists

`Personal Playlists`는 아래만 포함한다.

- 사용자가 직접 만든 playlist
- 자동 playlist `Saved from GMS`

여기에는 `gms-ems-*` playlist를 포함하지 않는다.  
그 playlist는 메인 library에 포함되므로 personal section에서 중복 노출되면 안 된다.

### 5-3. Platform Import Queue

`Platform Import Queue`는 아직 `PMS`에 들어오지 않은 외부 playlist 후보만 포함한다.

이미 import된 항목은 queue에서 다시 강조하지 않는다.

## 6. 식별 규칙

이번 단계의 식별 규칙은 아래처럼 고정한다.

- `gms-ems-*`
  - 의미: `GMS approved playlist`
  - 위치: `PMS Main Library`
  - 표시: 같은 shelf 안에서 `GMS approved` 출처 배지 유지

- `personal-saved-gms-recommendations`
  - 의미: 개별 저장 track이 모이는 자동 personal playlist
  - 위치: `Personal Playlists`

- 사용자가 직접 만든 일반 personal playlist
  - 위치: `Personal Playlists`

- 아직 import 안 된 platform playlist
  - 위치: `Platform Import Queue`

## 7. 화면 구조

`PMS` 페이지는 아래 3구역 구조로 재배치한다.

### 7-1. Main PMS Library

- 플랫폼 import playlist와 `GMS approved playlist`를 같은 shelf에 보여준다.
- 각 카드에는 출처 배지를 둔다.
  - `Platform import`
  - `GMS approved`
- 사용자가 playlist를 선택하면 detail과 track shelf가 같은 playlist 기준으로 갱신되어야 한다.

### 7-2. Personal Playlists

- 사용자가 직접 만든 playlist
- 자동 playlist `Saved from GMS`

이 영역은 메인 library와 다른 성격의 보조 보관함이다.

### 7-3. Platform Import Queue

- 아직 import하지 않은 후보만 노출한다.
- 역할은 메인 library 본문이 아니라 보조 액션 영역이다.

## 8. 백엔드 설계

### 8-1. Workspace Bootstrap

`PmsWorkspaceBootstrapService`의 현재 `first non-empty source` 구조는 유지하되, 가장 높은 우선순위의 통합 source를 새로 추가한다.

새 source의 책임:

- `PMS user library` playlist 수집
- `gms-ems-*` personal playlist 수집
- 두 집합을 하나의 `playlists` 응답으로 합성
- 요청된 `playlist_id` 또는 기본 playlist를 기준으로 workspace defaults 계산
- 둘 다 비어 있으면 empty 반환하여 기존 fallback source에 위임

이 접근은 기존 fallback chain을 깨지 않으면서, 제품 의미에 맞는 우선 읽기 모델을 추가한다.

### 8-2. Playlist Detail

`PmsPlaylistDetailService`는 `gms-ems-*` playlist를 단순 `pms-personal-playlist`로 식별하지 않는다.

필요 응답 해석:

- `source_collection`: `pms-gms-approved-playlist`
- `source_platform`: 가능하면 track/source 기반 값 사용, 최소한 화면에서는 `GMS approved`로 식별 가능해야 함
- `curator`: `gms approved`

일반 personal playlist와 자동 `Saved from GMS` playlist는 기존 personal 해석을 유지한다.

## 9. 프론트엔드 설계

`apps/web/src/pages/PmsPage.tsx`는 아래 동작으로 정리한다.

1. 메인 shelf 데이터는 통합 bootstrap 결과를 기준으로 렌더링한다.
2. `workspace.playlistId`가 바뀌면 bootstrap이 그 playlist 기준으로 다시 정렬되거나 재조회되어야 한다.
3. bootstrap 응답 후 현재 `playlistId`가 비어 있거나 유효하지 않으면, 서버 default playlist에 맞춰 workspace를 재동기화한다.
4. `Personal Playlists` 영역에서는 `gms-ems-*`를 제외한다.
5. `Platform Import Queue`에서는 `already_imported` 항목을 다시 카드로 보여주지 않는다.

이 변경의 핵심은 `선택된 카드`, `상세 정보`, `track shelf`가 항상 같은 playlist 컨텍스트를 공유하게 만드는 것이다.

## 10. 검증 기준

구현 완료 후 아래가 모두 만족되어야 한다.

1. `PMS Main Library`에 플랫폼 import playlist와 `GMS approved playlist`가 함께 보인다.
2. 같은 `gms-ems-*` playlist가 `Personal Playlists`에 중복 표시되지 않는다.
3. `Saved from GMS`는 `Personal Playlists`에 남는다.
4. `Platform Import Queue`에는 아직 가져오지 않은 외부 playlist만 보인다.
5. 메인 shelf에서 playlist를 바꾸면 detail/track shelf도 같은 playlist 기준으로 바뀐다.
6. playlist detail API에서 `gms-ems-*`는 일반 personal playlist가 아니라 `GMS approved` 계열로 식별된다.

## 11. 리스크와 후속 단계

이번 단계의 한계:

- `GMS approved playlist`의 물리 저장 위치는 아직 personal store에 남는다.
- 따라서 장기적으로는 `PMS user library`로의 승격 여부를 다시 결정해야 한다.

후속 후보:

1. `GMS approved playlist`를 canonical `PMS user library` 저장 경로로 이전
2. `Saved from GMS` 자동 playlist의 명명 규칙과 UI 고도화
3. 메인 library filter/sort 추가
4. `GMS approved` playlist 생성 시 metadata 규칙 정교화
