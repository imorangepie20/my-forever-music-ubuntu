# Public Curation Player Shuffle, Repeat, EQ Preload Design

## 목적

공개 큐레이션 페이지의 `Public Mix Player`에 일반 음악 플레이어에서 기대하는 `Shuffle`과 `Repeat` 조작을 추가한다. 동시에 TIDAL 재생 시작 후 EQ가 늦게 움직이는 현상을 줄이기 위해 EQ 분석 audio를 재생 전에 준비하고 다음 곡도 미리 준비한다.

실제 음악 재생은 기존처럼 TIDAL CDN URL을 브라우저 `<audio>`에 직접 연결한다. 서버는 EQ 분석용 audio 응답만 담당한다.

## 범위

### 포함

- 공개 큐레이션 플레이어의 `Shuffle` 켜기/끄기
- 공개 큐레이션 플레이어의 `Repeat` 모드 순환
  - `off`
  - `all`
  - `one`
- 현재 곡을 유지한 채 남은 재생 순서만 섞는 queue 동작
- 이전 곡, 다음 곡, 자동 넘김이 실제 재생 queue 순서를 따르도록 정리
- public session이 준비되면 첫 곡 EQ 분석 audio preload
- 현재 곡 재생 시 다음 재생 곡 EQ 분석 audio preload
- public session과 track id 기준의 제한된 EQ 분석 cache
- preload 실패 시 음악 재생은 유지하고 EQ 분석만 다음 기회에 재시도
- 외부 공유 페이지 트랙 카드에서 반복되는 `추천 이유` 문구 제거

### 제외

- TIDAL 음악 재생 자체를 same-origin streaming proxy로 전환
- 일반 로그인 사용자용 `PlaybackContext` 변경
- Shuffle 또는 Repeat 상태의 서버 저장
- 브라우저를 닫은 뒤에도 공개 플레이어 상태 유지
- 새로운 EQ 패키지 도입
- API의 track `reason` 필드 제거
- 관리자 분석 화면의 추천 근거 데이터 제거

## 플레이어 UX

`PublicMixSpectrumPlayer`의 기존 이전 곡, 재생/일시정지, 다음 곡 버튼 옆에 `Shuffle`과 `Repeat` icon button을 배치한다.

- `Shuffle`
  - 기본 상태는 꺼짐이다.
  - 누르면 켜지고, 현재 곡은 그대로 둔 채 아직 재생하지 않은 나머지 곡 순서만 섞는다.
  - 다시 누르면 꺼진다. 이미 만들어진 현재 queue는 유지한다.
  - 활성 상태는 cyan border와 text 색으로 표시한다.
- `Repeat`
  - 버튼을 누를 때마다 `off → all → one → off` 순으로 바뀐다.
  - `all`은 마지막 곡 종료 후 queue 첫 곡으로 돌아간다.
  - `one`은 현재 곡 종료 후 같은 곡을 다시 재생한다.
  - `one` 상태에서는 `Repeat1`, 나머지 상태에서는 `Repeat` icon을 쓴다.
  - 활성 상태는 cyan border와 text 색으로 표시한다.

버튼은 icon-only로 유지하고 `aria-label`과 `title`을 한글로 제공한다.

## 외부 공유 트랙 카드

공개 큐레이션의 트랙 카드에서는 `추천 이유` 단락을 표시하지 않는다. 모델이 생성한 추천 근거 문장이 곡마다 유사해 외부 공유 페이지의 시각적 밀도만 높이기 때문이다.

트랙 카드에는 다음 정보만 유지한다.

- 순서
- 길이
- 점수
- 제목
- 아티스트
- 앨범
- TIDAL track id
- 재생 버튼

API의 track `reason` 필드는 유지한다. 관리자 분석과 이후 모델 개선에 다시 활용할 수 있으며, 이번 변경은 외부 공유 UI의 표시 범위만 줄인다.

## Queue 모델

공개 페이지는 playlist 원본 배열과 별도로 `playbackQueue`를 관리한다. queue 항목은 playlist track index이다.

```ts
type PublicMixRepeatMode = 'off' | 'all' | 'one'

const playbackQueue: number[] = [0, 1, 2, ...]
const activeQueuePosition: number | null = null
```

현재 화면에 표시할 track은 다음처럼 계산한다.

```ts
const activeTrackIndex =
    activeQueuePosition === null
        ? null
        : playbackQueue[activeQueuePosition] ?? null
```

동작 규칙:

1. `Play All`은 queue 첫 위치부터 시작한다.
2. 목록에서 특정 곡을 누르면 해당 track index가 있는 queue 위치부터 시작한다.
3. 다음 곡과 이전 곡은 queue 위치를 기준으로 이동한다.
4. 곡 종료 시 `one`이면 현재 queue 위치를 다시 재생한다.
5. 마지막 곡 종료 시 `all`이면 queue 첫 위치로 이동한다.
6. Shuffle을 켜면 현재 queue 위치 앞부분과 현재 곡은 유지하고 이후 위치만 Fisher-Yates 방식으로 섞는다.

## EQ preload 구조

현재 공개 EQ 경로는 다음 순서로 동작한다.

```text
public stream 재생 시작
→ public analysis-audio 요청
→ 전체 mp4 응답 수신
→ decodeAudioData()
→ PCM ring buffer 저장
→ EQ bars 동작
```

지연은 전체 audio 수신과 decode가 재생 시작 뒤에 수행되기 때문에 발생한다.

개선 후에는 `useTidalAudioAnalyser`가 작은 in-memory cache를 관리한다.

```ts
type PublicCurationAnalysisSource = {
    slug: string
    publicSessionId: string
    publicTrackId: number
    quality: string
}
```

cache key:

```text
{slug}:{publicSessionId}:{publicTrackId}:{quality}
```

cache 값은 동일 요청의 중복 fetch와 decode를 피할 수 있도록 decode 결과 또는 진행 중인 promise를 보관한다. cache 크기는 현재 곡과 다음 곡 준비에 필요한 소수 항목으로 제한한다.

### preload 시점

1. public session이 준비되고 playlist가 있으면 queue 첫 곡을 preload한다.
2. 특정 곡을 재생하기 직전에 해당 곡 preload 결과를 재사용한다.
3. 특정 곡 재생이 시작되면 queue 규칙상 다음에 재생할 곡을 background preload한다.
4. Shuffle 또는 Repeat 상태가 바뀌어 다음 곡이 달라지면 새 다음 곡을 preload한다.

### 실패 처리

- preload 실패는 음악 재생 실패로 승격하지 않는다.
- 해당 곡이 실제로 시작될 때 EQ 분석 fetch를 다시 시도한다.
- 실제 분석 fetch도 실패하면 analyzer는 기존처럼 `error` mode와 reason을 남긴다.
- 다음 곡에서는 새로운 cache key로 다시 준비를 시도한다.

## 파일 경계

- `apps/web/src/pages/PublicCurationSharePage.tsx`
  - 공개 queue, Shuffle, Repeat 상태와 이동 규칙 관리
  - session 준비 및 queue 변화 시 EQ preload 요청
  - 외부 트랙 카드에서 반복되는 추천 이유 문구 제거
- `apps/web/src/components/public-curation/PublicMixSpectrumPlayer.tsx`
  - Shuffle, Repeat icon button 표시
  - 활성 상태와 접근성 label 표시
- `apps/web/src/hooks/useTidalAudioAnalyser.ts`
  - public analysis audio preload API 제공
  - 제한된 PCM cache와 실제 분석 시 cache 재사용
- `apps/web/scripts/public-curation-share-page-harness.mjs`
  - UI controls, queue 기반 자동 이동, preload 연결, 추천 이유 비노출을 정적 회귀 검사

## 검증

### 자동 검증

```bash
cd apps/web
node scripts/public-curation-share-page-harness.mjs
node scripts/public-curation-playback-stream-harness.mjs
node scripts/public-curation-tidal-oauth-harness.mjs
npm run build
```

### 브라우저 검증

1. public session 준비 후 `Play All`을 누른다.
2. 첫 곡 EQ가 기존보다 빠르게 반응하는지 확인한다.
3. Shuffle을 켜고 다음 곡 이동 순서가 원래 playlist 순서와 달라지는지 확인한다.
4. Repeat `one`으로 현재 곡이 다시 재생되는지 확인한다.
5. Repeat `all`로 마지막 곡 이후 queue 첫 곡이 재생되는지 확인한다.
6. 모바일과 데스크탑에서 control button이 겹치지 않는지 확인한다.
7. 트랙 카드에 반복되는 `추천 이유` 문구가 표시되지 않는지 확인한다.
