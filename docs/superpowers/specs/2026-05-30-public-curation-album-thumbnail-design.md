# Public Curation Album Thumbnail Design

## 목적

외부 공유 playlist의 각 track card와 큰 public player에 실제 album image thumbnail을 표시한다. 이미지가 없는 track도 비어 보이지 않도록 곡 제목의 첫 글자를 사용한 fallback 박스를 제공한다.

## 현재 상태

- 공개 공유 페이지의 track card에는 이미 `64x64` thumbnail 영역이 있다.
- `PublicCurationShareTrack.image_url`과 DB의 `public_curation_playlist_track.image_url` 컬럼도 존재한다.
- 그러나 후보 pool에서 AI scorer를 거쳐 playlist track을 저장하는 경로에 album image가 전달되지 않는다.
- `PublicCurationGenerationService`는 `TrackDraft.imageUrl`에 항상 `null`을 넣는다.
- 큰 `PublicMixSpectrumPlayer`에는 현재 곡의 album thumbnail 영역이 없다.

## 선택한 접근

playlist 생성 시점에 album image URL을 track metadata와 함께 저장한다.

1. `pms_user_track.album_image_url`과 `ems_collected_track.album_image_url`을 candidate pool SQL에서 조회한다.
2. Spring candidate DTO에 `imageUrl`을 추가한다.
3. FastAPI scorer request와 selected track response에 `image_url`을 유지한다.
4. Spring AI response DTO와 `TrackDraft.imageUrl`에 값을 전달한다.
5. 기존 `public_curation_playlist_track.image_url` 컬럼에 저장한다.
6. 공유 페이지는 저장된 URL을 그대로 사용한다.

공유 페이지 진입이나 재생 시점에 TIDAL API를 다시 호출하지 않는다. 첫 화면 렌더링 속도와 provider 의존성을 불필요하게 늘리지 않기 위해서다.

## UI 표시 규칙

### Track Card

- 기존 좌측 `64x64` 영역에 `track.image_url`이 있으면 album thumbnail을 표시한다.
- 이미지가 없으면 곡 제목의 첫 글자를 대문자로 표시한다.
- 제목이 비어 있는 예외 상황에서는 `?`를 표시한다.
- fallback은 album thumbnail과 동일한 정사각형 크기를 유지한다.

### Public Mix Player

- player 상단의 현재 곡 제목 왼쪽에 정사각형 thumbnail을 추가한다.
- 현재 곡에 `image_url`이 있으면 album thumbnail을 표시한다.
- 이미지가 없으면 track card와 동일한 첫 글자 fallback을 사용한다.
- 아직 곡을 재생하지 않은 상태에서는 `FM` fallback을 표시한다.
- thumbnail 추가로 제목과 control 영역이 밀리지 않도록 player header layout의 안정적인 크기를 유지한다.

## 데이터 흐름

```text
pms_user_track.album_image_url
ems_collected_track.album_image_url
        |
        v
JdbcPublicCurationCandidatePoolStore
        |
        v
PublicCurationCandidatePoolStore.CandidateTrack.imageUrl
        |
        v
AiPublicCurationCandidateTrack.imageUrl
        |
        v
FastAPI PublicCurationCandidateTrack.image_url
        |
        v
FastAPI PublicCurationSelectedTrack.image_url
        |
        v
AiPublicCurationSelectedTrack.imageUrl
        |
        v
PublicCurationPlaylistStore.TrackDraft.imageUrl
        |
        v
public_curation_playlist_track.image_url
        |
        v
PublicCurationShareTrack.image_url
        |
        +--> track card thumbnail
        +--> Public Mix Player thumbnail
```

## 기존 Playlist 처리

이미 생성된 공개 playlist는 `image_url`이 `null`로 저장되어 있을 수 있다. 이번 단계에서는 별도 migration이나 provider 재조회로 과거 데이터를 보강하지 않는다.

- 기존 playlist는 첫 글자 fallback으로 정상 표시한다.
- 운영자가 새 draft를 생성하면 현재 PMS/EMS metadata의 album image가 저장된다.
- 기존 공개 playlist를 이미지 포함 상태로 교체하려면 새 draft를 생성하고 publish한다.

## 오류 처리

- album image URL이 없더라도 playlist 생성과 공유 페이지 렌더링은 실패하지 않는다.
- 원격 이미지 로딩 실패 시 브라우저의 깨진 이미지 아이콘을 노출하지 않고 첫 글자 fallback으로 전환한다.
- album image는 metadata 보강 요소이며 재생 성공 여부와 분리한다.

## 테스트

### Backend

- candidate pool SQL이 PMS와 EMS의 `album_image_url`을 조회하는지 확인한다.
- admin candidate 변환 시 `imageUrl`이 AI request로 전달되는지 확인한다.
- FastAPI scorer가 candidate의 `image_url`을 selected track에 유지하는지 확인한다.
- generation service가 AI selected track의 `imageUrl`을 `TrackDraft`에 저장하는지 확인한다.

### Frontend

- 공유 페이지 track card가 `track.image_url`을 thumbnail로 사용한다.
- 이미지가 없으면 제목의 첫 글자 대문자 fallback을 사용한다.
- 큰 player 제목 왼쪽에 현재 곡 thumbnail이 표시된다.
- player에서 아직 재생 곡이 없으면 `FM` fallback이 표시된다.
- 원격 이미지 `onError` 발생 시 fallback으로 전환된다.

## 범위 제외

- TIDAL API를 통한 실시간 image 재조회
- 기존 공개 playlist의 일괄 backfill
- 별도 album detail page
- 이미지 CDN proxy 또는 resize pipeline
