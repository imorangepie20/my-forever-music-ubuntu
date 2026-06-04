# Public Curation Expanded Track Pool Design

작성일: 2026-05-31

## 목적

public curation playlist 생성 시 후보 track pool을 현재의 TIDAL-ready 중심 후보에서 더 넓은 전체 track pool로 확장한다.

단, 공개 공유 페이지는 사이트 안에서 직접 재생되어야 하므로, 최종 후보는 반드시 재생 가능한 TIDAL playback target을 가져야 한다. 후보 풀은 넓히되, 재생 안정성은 낮추지 않는다.

## 현재 상태

현재 public curation 후보는 `JdbcPublicCurationCandidatePoolStore`에서 아래 두 소스를 합친다.

- `pms_user_track`
  - `tidal_track_id is not null`
  - `tidal_uri is not null`
- `ems_collected_track`
  - `source_platform = 'tidal'`
  - `external_track_id is not null`

`ems_collected_track`는 `ems_collected_playlist_track`과 `left join`되어 있으므로, playlist에 속하지 않은 개별 EMS track도 TIDAL 조건만 만족하면 이미 후보에 들어올 수 있다.

## 문제

현재 구조는 재생 안정성은 높지만, 후보 풀이 TIDAL-ready track으로 좁다.

아래 track들은 공개 playlist 후보로 쓰기 어렵거나 제외된다.

- Spotify 기반 EMS/PMS track
- acquisition pipeline에서 들어온 track 중 TIDAL id가 아직 없는 track
- Melon/검색/외부 source에서 들어온 metadata-only track
- audio feature는 있지만 TIDAL playback target이 없는 track

이 때문에 모델이 평가할 수 있는 후보 다양성이 줄고, 같은 곡이 반복 추천될 가능성이 높아진다.

## 방향

후보 풀을 두 단계로 분리한다.

1. **Raw Candidate Pool**
   - PMS/EMS 전체 track 중 metadata 품질이 충분한 track을 넓게 가져온다.
   - TIDAL id가 없어도 title, artist, album, ISRC, duration, image, audio feature가 있으면 raw candidate가 될 수 있다.

2. **Playable Candidate Pool**
   - raw candidate 중 TIDAL playback target이 이미 있거나, 사전 resolve로 TIDAL target을 찾은 track만 최종 AI scoring 후보로 넘긴다.
   - resolve 실패 track은 public curation 후보에서 제외한다.

## 후보 소스

1차 구현의 raw pool은 아래를 사용한다.

- `pms_user_track`
- `ems_collected_track`

각 track은 아래 상태 중 하나를 가진다.

- `tidal_ready`
  - 이미 `tidal_track_id`와 `tidal_uri`가 있음
- `resolve_candidate`
  - TIDAL id는 없지만 `title`, `artist_name`, `isrc` 또는 `duration_ms`가 있어 TIDAL resolve를 시도할 수 있음
- `unplayable`
  - metadata가 부족하거나 resolve 실패로 공개 재생 후보가 될 수 없음

## TIDAL Resolve Gate

public curation 후보 조회 단계에서 `resolve_candidate` track을 바로 AI로 넘기지 않는다.

대신 backend에서 다음 순서로 TIDAL target을 확인한다.

1. ISRC가 있으면 ISRC 우선 검색
2. ISRC가 없거나 실패하면 title + artist 검색
3. duration이 있으면 길이 차이로 match confidence 보정
4. match confidence가 기준 미만이면 제외
5. 성공 시 public curation 후보 응답에는 resolved `tidal_track_id`, `tidal_uri`, `tidal_external_url`을 포함

초기 구현은 DB canonical row를 바로 수정하지 않고, public curation run 안에서만 resolved target을 사용한다. 이후 안정화되면 별도 backfill job으로 PMS/EMS row의 TIDAL target을 저장할 수 있다.

## Scoring 입력 변화

AI scoring service에는 최종 playable candidate만 전달한다.

candidate에는 아래 신호를 유지한다.

- source scope/id
- title, artist, album, image
- source platform
- resolved TIDAL target
- audio features
- audio feature confidence/evidence
- source playlist signals
- public audience response
- freshness

추가로 `playback_resolution_status`를 넣을 수 있다.

- `native_tidal`
- `resolved_to_tidal`

모델은 `resolved_to_tidal` 후보에 약한 confidence penalty를 줄 수 있다. 단, 점수 차이가 너무 커져 새 후보 확장 효과를 죽이지 않도록 penalty는 작게 둔다.

## 중복 방지

전체 track pool을 넓히면 같은 곡이 source만 다르게 중복될 수 있다.

중복 제거 키 우선순위는 아래로 둔다.

1. ISRC
2. resolved TIDAL track id
3. normalized title + normalized artist

AI service의 reranker는 기존처럼 artist/album 반복도 추가로 줄인다.

## 운영 정책

처음부터 모든 raw candidate를 resolve하면 느려질 수 있으므로, 운영 입력의 `candidate_limit`을 다음처럼 해석한다.

- `raw_candidate_limit`: DB에서 넓게 가져올 후보 수
- `playable_candidate_limit`: AI scoring에 넘길 최종 후보 수

초기값:

- raw candidate: `candidate_limit * 3`
- playable candidate: `candidate_limit`

예: admin UI 후보 풀 크기가 220이면 최대 660개 raw 후보를 보고, TIDAL resolve를 통과한 220개까지만 scoring에 넘긴다.

## 실패 처리

- TIDAL resolve API 실패: 해당 후보만 제외하고 run은 계속 진행
- playable candidate가 target track count보다 부족: 409 또는 명확한 admin error 반환
- resolve rate가 낮음: 응답 summary에 `raw_count`, `resolved_count`, `excluded_count`, `resolve_success_ratio`를 포함

## 구현 범위

1차 구현:

- public curation candidate query를 TIDAL-ready-only에서 expanded raw pool로 확장
- backend TIDAL resolve gate 추가
- resolved candidate만 AI scoring에 전달
- admin response에 후보 확장/resolve summary 표시
- SQL ambiguity/real DB smoke test 강화

후속 구현:

- resolved TIDAL target을 PMS/EMS canonical row에 backfill
- admin UI에서 raw/playable 후보 수 분리 입력
- resolve 실패 top reason 운영 패널 추가

## 검증

- TIDAL-ready 후보만 있던 기존 테스트는 계속 통과해야 한다.
- TIDAL id 없는 EMS/PMS track도 resolve 성공 시 candidate로 들어와야 한다.
- resolve 실패 track은 AI scoring 요청에 포함되지 않아야 한다.
- 실제 PostgreSQL 후보 SQL smoke가 통과해야 한다.
- 공개 공유 페이지의 모든 track은 TIDAL playback target을 가져야 한다.
