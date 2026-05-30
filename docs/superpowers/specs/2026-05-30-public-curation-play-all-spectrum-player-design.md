# 공개 큐레이션 Play All Spectrum Player 설계

## 요약

공개 큐레이션 공유 페이지에 `Play All` 순차 재생과 대형 bar spectrum player를 추가한다.

공개 페이지의 hero CTA는 인증이 끝난 뒤 `첫 곡 재생`이 아니라 `Play All`로 표시한다. 방문자가 누르면 첫 곡부터 시작하고, 곡이 끝날 때마다 다음 곡으로 자동 진행한다. 트랙 목록에서 개별 곡을 누르면 해당 곡을 바로 재생하되 `Play All` 순서는 그 곡부터 이어서 진행한다.

hero와 전체 트랙 목록 사이에는 일반 compact player보다 약 네 배 높은 대형 player를 배치한다. player는 현재 곡, artist, 진행 상태, 이전/재생·일시정지/다음 제어, track position, 128개 bar 기반 spectrum EQ를 표시한다.

## 패키지 검토

### 검토한 패키지

`audioMotion-analyzer`를 우선 검토했다.

- 공식 문서: <https://audiomotion.dev/>
- 공식 저장소: <https://github.com/hvianna/audioMotion-analyzer>

장점:

- Web Audio와 Canvas 기반 고해상도 spectrum analyzer.
- `1/24 octave`를 포함한 band mode.
- `log`, `mel`, `bark`, `linear` frequency scale.
- peak, amplitude sensitivity, min/max frequency, gradient 설정.

### 이번 단계에서 직접 연결하지 않는 이유

현재 TIDAL 재생은 cross-origin CDN stream을 사용한다. 프로젝트의 `tidalStreamPlayback.ts`는 media element에 `crossOrigin`을 의도적으로 설정하지 않는다. 일반적인 `audioMotion-analyzer` 사용법처럼 media element를 `AudioContext.createMediaElementSource()`에 직접 연결하면 TIDAL CDN CORS 제약 때문에 재생이나 분석이 깨질 수 있다.

프로젝트에는 이미 별도 PCM capture 경계가 있다.

- `tidalAudioCapture.ts`: HLS segment capture.
- `useTidalAudioAnalyser.ts`: captured segment decode, ring buffer, FFT read handle.
- `BarsVisualizer.tsx`: PCM FFT 결과를 bar로 표현.

이번 단계는 검증된 PCM capture 경계를 유지한다. `audioMotion-analyzer`의 시각 설계를 참고해 bar spectrum player를 만든다. package 직접 연결은 captured PCM을 `AudioNode`로 공급하는 adapter와 FFT 성능 측정을 함께 수행하는 후속 단계로 분리한다.

## UI 구성

### Hero CTA

- 공개 세션이 없으면 기존처럼 TIDAL device 인증을 시작한다.
- 인증 대기 중이면 완료 확인 상태를 표시한다.
- 공개 세션이 있으면 CTA label은 `Play All`.
- 클릭하면 첫 번째 track부터 순차 재생한다.

### 대형 Spectrum Player

위치:

- hero 아래.
- 전체 track list 위.
- 외부 공유 페이지의 중심 구간.

크기:

- desktop spectrum 영역 높이 약 `240px`.
- mobile spectrum 영역 높이 약 `176px`.
- 일반 compact player보다 약 네 배 높은 시각 영역.

표시:

- `PUBLIC MIX PLAYER` label.
- 현재 track order, title, artist.
- 진행 시간과 전체 시간.
- 이전 track, 재생·일시정지, 다음 track.
- 저역, 중역, 고역 label.
- amplitude guide line.
- 128개 log-distributed bar.
- bar별 현재 amplitude와 짧은 peak hold.

### Track List

- 개별 track 재생 버튼은 유지한다.
- 현재 재생 track은 강조한다.
- 개별 track 클릭 시 해당 index부터 순차 재생한다.

## 컴포넌트 경계

### `PublicMixSpectrumPlayer.tsx`

공개 페이지 전용 UI component다.

책임:

- 대형 player layout.
- bar spectrum 렌더링.
- progress 표시.
- 이전/재생·일시정지/다음 button.
- 현재 track metadata 표시.

입력:

- `track`
- `trackIndex`
- `trackCount`
- `snapshot`
- `analyser`
- `isPlaying`
- `onPrevious`
- `onTogglePlayback`
- `onNext`

### `PublicCurationSharePage.tsx`

공개 mix 재생 orchestration을 담당한다.

책임:

- `Play All` 시작.
- active track index 관리.
- track 종료 시 다음 track 자동 시작.
- TIDAL 공개 세션 인증.
- `getTidalAudioElement()`와 `useTidalAudioAnalyser()`를 spectrum player에 연결.
- progress snapshot 주기 갱신.

### 기존 재생 모듈

`tidalStreamPlayback.ts`의 stream 재생, pause, resume, snapshot API를 그대로 사용한다. 공개 player 때문에 TIDAL stream 경계를 변경하지 않는다.

## 재생 흐름

1. 방문자가 `Play All`을 누른다.
2. 세션이 없으면 새 탭 TIDAL 인증을 시작한다.
3. 세션이 있으면 track index `0`을 재생한다.
4. track 재생 callback이 player snapshot을 갱신한다.
5. `onEnded`가 발생하면 다음 index를 재생한다.
6. 마지막 track이 끝나면 재생 상태를 종료하고 `전체 재생이 끝났습니다.`를 표시한다.
7. 방문자가 track list의 곡을 누르면 해당 index를 active index로 설정하고 같은 순차 재생 흐름을 이어간다.

## Spectrum 표현

현재 `useTidalAudioAnalyser()`는 128-bin FFT byte data를 제공한다. 이번 player는 이 handle을 그대로 사용한다.

- bar count: `128`.
- horizontal distribution: logarithmic band mapping.
- amplitude: 현재 높이와 peak hold를 분리.
- smoothing: 빠른 attack, 느린 release.
- frequency guide: `LOW`, `MID`, `HIGH`.
- amplitude guide: 25%, 50%, 75%.

현재 `simpleFft.ts`는 순수 TypeScript DFT다. FFT size를 크게 늘리면 프레임 비용이 급격히 증가한다. 이번 단계에서는 기존 128-bin을 유지한다.

## 오류 처리

- analyzer가 아직 PCM을 받지 못하면 player는 유지하고 bar를 낮은 idle 상태로 표시한다.
- 인증이 없으면 control 클릭도 인증 시작 흐름으로 연결한다.
- 다음 track 재생이 실패하면 오류를 표시하고 자동 진행을 중단한다.
- 마지막 track 종료는 오류가 아니라 완료 상태로 처리한다.

## 테스트

### 정적 회귀 하네스

`public-curation-share-page-harness.mjs`에서 다음을 검증한다.

- hero CTA에 `Play All`이 존재한다.
- `PublicMixSpectrumPlayer`가 공개 페이지에 연결된다.
- 종료 callback에서 다음 track 자동 진행 함수가 호출된다.
- `tidalPause`, `tidalResume`, `getTidalCurrentSnapshot`, `getTidalAudioElement`를 사용한다.

### 빌드

- `node scripts/public-curation-share-page-harness.mjs`
- `node scripts/public-curation-tidal-oauth-harness.mjs`
- `npm run build`

## 후속 단계

실제 브라우저에서 CPU 사용량과 animation frame 안정성을 측정한다. 더 높은 FFT resolution이 필요하면 `audioMotion-analyzer` 직접 연결이 아니라 captured PCM adapter 또는 FFT 전용 package 교체를 별도 설계한다.
