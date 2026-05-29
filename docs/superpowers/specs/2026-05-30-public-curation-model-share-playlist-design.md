# 공개 큐레이션 모델 공유 플레이리스트 설계

## 요약

공개 큐레이션은 모델이 생성한 외부 공유용 플레이리스트를 다루는 별도 제품 도메인이다.

운영자는 자연어 테마와 구조화된 조건을 입력한다. 시스템은 내부에서 사용할 수 있는 트랙 풀에서 후보 트랙을 모으고, Public Curation Model로 평가한 뒤, TIDAL에서 재생 가능한 약 30곡을 선별한다. 이후 제목, 소개 문구, 트랙별 추천 이유를 생성하고 시각적으로 풍부한 공개 공유 페이지를 발행한다.

방문자는 My Forever Music 계정 없이 공유 페이지를 열 수 있다. 사이트 안에서 재생하려면 방문자 본인의 TIDAL 계정으로 임시 공개 재생 세션을 인증한다. 공개 페이지 재생은 YouTube fallback을 사용하지 않으며, 사용자의 PMS/GMS 학습 데이터에도 기록하지 않는다.

## 제품 의도

이 기능은 운영자가 수동으로 곡을 골라 플레이리스트를 만드는 도구가 아니다.

운영자는 편집 의도를 정의한다.

- 테마 프롬프트
- 무드
- 장르/태그 필터
- 오디오 특성 범위
- 언어 또는 지역
- 발매 연도 범위
- 목표 트랙 수
- TIDAL 재생 가능 조건
- 아티스트/장르 중복 제한
- 잔잔한 시작, 강한 중반, 부드러운 마무리 같은 흐름 의도

큐레이션 작업은 모델이 담당한다.

- 후보 트랙 수집
- 트랙 평가
- 중복 제어
- 30곡 선별
- 재생 순서 구성
- 제목, 부제, 설명, 트랙별 추천 이유 생성

최종 플레이리스트는 네이버 카페 글, 블로그 글, 소셜 링크 등 외부 채널에 공유하는 것을 목표로 한다.

## 범위

### 첫 제품 단위에 포함

- 운영자 전용 공개 큐레이션 생성 화면.
- 자연어 프롬프트와 구조화된 필터.
- 내부 트랙 풀 기반 후보 추출.
- FastAPI를 통한 Public Curation Model 평가.
- FastAPI를 사용할 수 없을 때 Spring Boot fallback 평가.
- 공개 큐레이션 플레이리스트 저장.
- `/share/playlists/{slug}` 공개 공유 페이지.
- 일반 앱 shell과 다른 매거진/포스터 스타일 공개 페이지 디자인.
- 공개 재생 세션을 위한 TIDAL OAuth redirect.
- 기존 `/platforms/oauth/callback` redirect URI 재사용과 state 기반 흐름 분기.
- 이 공개 페이지용 TIDAL 인증을 마친 방문자의 사이트 내 재생.
- 사용자 음악 이벤트와 분리된 익명 공개 재생 이벤트 저장.

### 뒤로 미루는 항목

- 공개 재생용 TIDAL device-code fallback.
- 공개 공유 페이지의 YouTube fallback 재생.
- 공개 큐레이션 플레이리스트를 방문자의 PMS로 가져오기.
- 공개 댓글, 좋아요, 랭킹, 소셜 기능.
- 여러 운영자의 승인 워크플로.
- CMS 형태의 전체 페이지 레이아웃 편집기.

## 기존 도메인과의 관계

공개 큐레이션은 GMS와 분리된다.

GMS는 사용자 개인화 추천이다. 공개 큐레이션은 외부 공유를 위한 공개 편집형 생성이다. 두 도메인은 같은 트랙과 일부 오디오 특성 데이터를 사용할 수 있지만, 평가 의도와 학습 피드백은 공유하면 안 된다.

공개 큐레이션은 법적, 기술적으로 사용할 수 있는 범위에서 EMS, PMS 기반 전역 트랙 후보, GMS 후보 결과, acquisition 트랙, search pool 트랙, 가져온 플랫폼 트랙을 읽을 수 있다. 최종 발행 트랙 목록은 TIDAL 재생 준비 상태를 강하게 우선하거나 필수 조건으로 삼아야 한다. 공개 페이지의 실제 재생은 방문자 본인의 TIDAL 인증으로 이루어지기 때문이다.

공개 큐레이션 이벤트는 `user_music_event`에 기록하지 않는다. 예외는 방문자가 My Forever Music에 로그인한 사용자이고, 별도의 사용자 라이브러리 기능에서 사용자 귀속 액션을 명시적으로 선택한 경우뿐이다. 첫 제품 단위의 재생 이벤트는 공개 분석 이벤트 테이블에 저장한다.

## 핵심 흐름

### 운영자 생성 흐름

1. 운영자가 `/admin/public-curations`를 연다.
2. 운영자가 "비 오는 밤에 듣기 좋은 한국 인디와 재즈 감성. 너무 처지지 않고 카페에서 공유하기 좋은 30곡." 같은 프롬프트를 입력한다.
3. 운영자가 구조화된 필터를 설정한다.
   - 목표 곡 수, 기본값 30
   - 장르/태그
   - 무드 태그
   - energy, valence, acousticness, danceability, tempo 범위
   - 언어/지역
   - 발매 연도 범위
   - 아티스트별 최대 포함 수
   - TIDAL 재생 가능 필수 여부
4. Spring Boot가 `public_curation_run`을 만든다.
5. Spring Boot가 후보 트랙을 모아 FastAPI로 보낸다.
6. FastAPI가 평가된 후보, 선별 트랙, 순서, 생성된 제목/문구, 트랙별 이유를 반환한다.
7. Spring Boot가 플레이리스트 초안을 저장한다.
8. 운영자가 초안을 미리 본다.
9. 운영자가 발행하면 안정적인 slug를 생성하거나 활성화한다.
10. 공개 페이지가 `/share/playlists/{slug}`에서 접근 가능해진다.

### 공개 재생 흐름

1. 방문자가 외부 글에서 `/share/playlists/{slug}`를 연다.
2. 페이지가 공개 메타데이터, 편집 문구, 커버 표현, 트랙 목록을 렌더링한다.
3. 방문자가 "TIDAL로 여기서 듣기"를 누른다.
4. 유효한 공개 재생 세션이 있으면 바로 재생을 시작한다.
5. 공개 재생 세션이 없으면 Spring Boot가 공개 TIDAL OAuth 흐름을 시작한다.
6. 브라우저가 TIDAL 로그인으로 이동한다.
7. TIDAL이 기존 `/platforms/oauth/callback`으로 되돌려 보낸다.
8. callback은 저장된 OAuth state를 읽고 `flow=public-curation`을 감지한다.
9. Spring Boot가 인증 code를 교환하고 짧게 유지되는 `public_playback_session`을 만든 뒤 `/share/playlists/{slug}?playback=ready`로 되돌린다.
10. 공개 페이지는 세션 재생 자격 정보를 불러와 사이트 안에서 플레이리스트를 재생한다.

## 공개 페이지 디자인

공개 페이지는 앱 대시보드보다 음악 매거진 특집 기사에 가까운 인상을 줘야 한다.

권장 시각 방향은 다음을 섞은 형태다.

- 첫 화면은 영화 포스터 같은 hero.
- 모델이 생성한 제목과 부제.
- 강한 커버 이미지 또는 생성형 비주얼 처리.
- "TIDAL로 여기서 듣기" 기본 CTA.
- 트랙 수와 대략적인 재생 시간.
- 짧은 큐레이션 노트.
- 모델이 고른 하이라이트.
- 전체 30곡 목록.
- 트랙별 한 줄 추천 이유.
- TIDAL 재생 준비 상태 표시.

공개 페이지는 일반 로그인 앱의 sidebar/header shell을 사용하지 않는다. 필요한 경우 낮은 수준의 디자인 토큰, 플레이어 primitive, playback context 로직은 재사용할 수 있지만, 화면 자체는 공유 가능한 공개 콘텐츠처럼 보여야 한다.

## 운영자 페이지 디자인

운영자 페이지는 장식적이기보다 작업 중심이고 밀도 있게 구성한다.

예상 패널은 다음과 같다.

- 프롬프트 편집기.
- 구조화된 필터.
- 후보 풀 요약.
- 모델 평가 축 가중치.
- 실행 상태와 오류.
- 초안 결과 미리보기.
- 선별된 30곡과 점수, 이유, 출처.
- 발행 제어.
- 공개 공유 URL 복사.

운영자의 역할은 의도와 발행을 제어하는 것이다. 개별 곡을 수동으로 고르는 방식이 주 흐름이 되어서는 안 된다.

## 데이터 모델

### `public_curation_playlist`

생성된 공개 플레이리스트를 저장한다.

필드:

- `id`
- `slug`
- `title`
- `subtitle`
- `description`
- `prompt`
- `filter_snapshot_json`
- `status`: `draft`, `published`, `archived`
- `cover_style`
- `model_version`
- `track_count`
- `duration_ms`
- `published_at`
- `created_by_admin_user_id`
- `created_at`
- `updated_at`

### `public_curation_playlist_track`

최종 정렬된 트랙 목록과 화면 표시용 메타데이터를 저장한다.

필드:

- `id`
- `playlist_id`
- `track_order`
- `source_track_scope`: `ems_collected_track`, `pms_user_track`, `gms_candidate`, `search_pool` 또는 다른 명시적 출처
- `source_track_id`
- `title`
- `artist_name`
- `album_title`
- `image_url`
- `duration_ms`
- `isrc`
- `tidal_track_id`
- `tidal_uri`
- `tidal_external_url`
- `score`
- `score_breakdown_json`
- `reason`
- `created_at`

### `public_curation_run`

생성 시도와 진단 정보를 저장한다.

필드:

- `id`
- `playlist_id`
- `prompt`
- `filter_snapshot_json`
- `candidate_count`
- `selected_count`
- `model_version`
- `status`: `running`, `completed`, `failed`
- `score_summary_json`
- `error_message`
- `started_at`
- `completed_at`

### `public_playback_session`

방문자 본인 소유의 임시 TIDAL 재생 자격 정보를 저장한다.

필드:

- `session_id`
- `playlist_id`
- `tidal_account_label`
- `access_token_encrypted`
- `refresh_token_encrypted`
- `scope_summary`
- `expires_at`
- `created_at`
- `last_used_at`

세션은 공개 재생에만 한정된다. 일반 플랫폼 연결을 만들면 안 되며, PMS 사용자 라이브러리 상태에 붙어서도 안 된다.

### `public_playlist_play_event`

공개 페이지의 익명 재생 분석 이벤트를 저장한다.

필드:

- `id`
- `playlist_id`
- `public_session_id`
- `track_id`
- `event_type`: `play_started`, `play_completed`, `skipped`, `play_failed`
- `position_ms`
- `duration_ms`
- `occurred_at`
- `received_at`

## 모델 계약

Spring Boot가 후보 트랙을 FastAPI로 보낸다.

요청 필드:

- `prompt`
- `filters`
- `target_track_count`
- `candidate_tracks[]`
  - source scope/id
  - title
  - artist
  - album
  - duration
  - ISRC
  - source platform
  - 사용 가능한 경우 TIDAL 식별자
  - 오디오 특성
  - 장르/태그
  - 사용 가능한 경우 popularity/freshness 신호

FastAPI 반환:

- 생성된 `title`
- 생성된 `subtitle`
- 생성된 `description`
- 선별된 `tracks[]`
  - source reference
  - order
  - score
  - score breakdown
  - reason
- 실행 단위 score summary
- model version

## 평가 축

첫 Public Curation Model은 다음 축을 사용한다.

- `theme_fit`: 운영자 프롬프트와의 정렬도.
- `tidal_readiness`: TIDAL track id, URI, 재생 준비 상태.
- `audio_fit`: 요청된 오디오 특성 범위와의 일치도.
- `coherence`: 최종 30곡 시퀀스의 흐름.
- `diversity`: 아티스트, 장르, 출처, 시대, 무드의 다양성.
- `freshness`: 너무 뻔한 곡만이 아닌 발견 가치.
- `redundancy`: 반복 아티스트, 중복 트랙, 지나치게 비슷한 인접 곡에 대한 패널티.
- `shareability`: 공개 페이지에서 설명하기 쉽고 매력적으로 보이는 정도.

## OAuth 전략

첫 제품 단위는 TIDAL OAuth redirect만 사용한다.

기존에 등록된 callback 경로를 재사용한다.

`/platforms/oauth/callback`

state는 일반 플랫폼 연결과 공개 큐레이션 재생을 구분해야 한다.

- `flow=public-curation`
- `slug`
- `playlist_id`
- `return_path`
- `csrf_nonce`

callback에서 frontend/backend 경로는 인증 자격 정보를 일반 사용자 플랫폼 연결로 저장하지 않아야 한다. 대신 `public_playback_session`을 만든다.

TIDAL 정책이나 redirect 제약 때문에 OAuth redirect가 안정적으로 동작하지 않으면, 두 번째 제품 단위에서 같은 공개 페이지에 device-code fallback을 추가한다.

## 재생 정책

공개 공유 페이지는 YouTube fallback을 사용하지 않는다.

이유:

- 공개 트래픽은 YouTube Data API quota를 빠르게 소진할 수 있다.
- 공개 페이지의 대상은 TIDAL 청취자다.
- 최종 큐레이션 목록은 TIDAL 재생 준비 상태를 요구한다.
- fallback 재생은 의도한 음질과 권리 경계를 흐릴 수 있다.

재생은 공개 재생 세션을 통해 방문자 본인의 TIDAL 인증을 사용한다.

## API 초안

운영자 API:

- `POST /api/v1/public-curations/runs`
- `GET /api/v1/public-curations/runs/{runId}`
- `GET /api/v1/public-curations/playlists/{playlistId}`
- `POST /api/v1/public-curations/playlists/{playlistId}/publish`
- `POST /api/v1/public-curations/playlists/{playlistId}/archive`

공개 API:

- `GET /api/v1/public-curations/share/{slug}`
- `POST /api/v1/public-curations/share/{slug}/tidal/oauth/start`
- `GET /api/v1/public-curations/share/{slug}/playback/session`
- `POST /api/v1/public-curations/share/{slug}/playback/events`

기존 OAuth callback은 저장된 state에 따라 분기하는 backend 완료 endpoint를 호출할 수 있다.

## 오류 경계

- 후보 트랙 없음: 운영자에게 보이는 오류와 함께 실행을 실패 처리한다.
- 목표 수보다 TIDAL-ready 트랙이 적음: 운영자가 설정한 최소 발행 가능 수를 만족하면 초안만 만들고, 그렇지 않으면 실행을 실패 처리한다.
- FastAPI 사용 불가: Spring Boot fallback 평가를 사용하고, 실행을 fallback-generated로 표시한다.
- OAuth state 없음: 재연결 CTA가 있는 공개 페이지를 보여준다.
- TIDAL 토큰 교환 실패: 명시적인 TIDAL 인증 오류를 공개 페이지에 보여준다.
- 공개 재생 세션 만료: TIDAL 인증을 다시 요구한다.
- 트랙 재생 실패: 플레이리스트에 다른 TIDAL-ready 트랙이 있을 때만 건너뛰고, 공개 재생 실패 이벤트를 기록한다.

## 테스트 전략

Backend:

- 공개 큐레이션 실행이 prompt/filter snapshot을 저장한다.
- 후보 추출이 TIDAL readiness로 필터링한다.
- FastAPI 응답이 playlist와 playlist track 테이블에 저장된다.
- FastAPI 실패 시 Spring Boot fallback이 결정론적 점수를 만든다.
- 발행 시 안정적인 slug와 공개 페이지 payload가 만들어진다.
- 공개 OAuth state는 사용자 플랫폼 연결이 아니라 공개 재생 세션을 만든다.
- 공개 재생 이벤트는 `user_music_event`가 아니라 `public_playlist_play_event`에 기록된다.

Frontend:

- 운영자 페이지가 프롬프트와 필터를 제출한다.
- 운영자 페이지가 실행 상태와 생성된 초안을 보여준다.
- 발행된 공개 페이지가 일반 앱 shell 없이 렌더링된다.
- 공개 재생 세션이 없을 때 공개 페이지가 TIDAL OAuth를 시작한다.
- OAuth 이후 공개 페이지가 같은 slug로 돌아온다.
- 공개 페이지가 YouTube fallback을 비활성화한다.
- 공개 페이지가 공개 재생 세션으로 재생한다.

## 출시 계획

1. Schema와 backend domain.
2. 최소 결정론 모델을 가진 FastAPI scoring endpoint.
3. Spring Boot 후보 추출과 fallback scoring.
4. 운영자 생성 페이지.
5. 공개 공유 페이지.
6. 공개 TIDAL OAuth session 흐름.
7. 공개 playback event logging.
8. 실제 TIDAL 계정 end-to-end 검증.
9. OAuth redirect가 안정적이지 않을 경우 device-code fallback 설계와 구현.
