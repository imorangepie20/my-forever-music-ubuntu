# Shared Spectrum Visualizer Frame Design

## 목표

공개 큐레이션 mix player에서 사용 중인 bar spectrum EQ 표현을 일반 서비스 화면에도
적용한다. 홈 상단 preview EQ, `/visualizer` 전체 화면 EQ, 공개 큐레이션 mix player가
같은 시각 언어를 사용하도록 통일한다.

## 범위

- 공용 `SpectrumVisualizerFrame` 컴포넌트를 추가한다.
- 공용 frame 안에서 `BarsVisualizer`, 수평 guide line, `LOW · MID · HIGH` label을 렌더링한다.
- 공개 큐레이션 mix player의 기존 EQ markup을 공용 frame 사용으로 교체한다.
- 홈 상단 `HeroEqBanner`의 EQ를 compact 공용 frame으로 교체한다.
- `/visualizer` 전체 화면 EQ를 full-screen 공용 frame으로 교체한다.

다음 항목은 변경하지 않는다.

- TIDAL stream 재생 로직
- preview audio 재생 로직
- analyser 생성과 audio capture 경로
- queue, shuffle, repeat 동작
- track metadata와 API 계약

## 공용 컴포넌트

`SpectrumVisualizerFrame`은 아래 props를 받는다.

- `analyser`: 기존 audio analyser handle
- `accentHex`: 막대 색상
- `isPlaying`: animation 활성 상태
- `className`: 화면별 높이와 배경 조절
- `showLabels`: `LOW · MID · HIGH` label 표시 여부. 기본값은 `true`

컴포넌트는 화면별 재생 상태를 소유하지 않는다. 전달받은 analyser를
`BarsVisualizer`에 연결하고, 세 개의 수평 guide line과 하단 label만 함께 렌더링한다.

## 화면별 적용

### 공개 큐레이션 mix player

현재의 큰 EQ 영역 크기와 배경을 유지한다. 중복된 guide line과 label markup만 공용
frame으로 옮긴다.

### 홈 상단 preview EQ

현재 카드 하단 EQ 영역 높이를 유지한다. compact 영역에서도 label이 막대와 겹치지
않도록 하단 여백을 확보한다.

### `/visualizer` 전체 화면

album cover 위 overlay 영역에 full-screen 공용 frame을 사용한다. 기존 queue rail,
controls bar, diagnostics는 유지한다.

## 검증

- public curation harness에서 공개 mix가 공용 frame을 사용하는지 확인한다.
- sitewide frontend harness에서 홈과 `/visualizer`가 공용 frame을 사용하는지 확인한다.
- `pnpm run build`로 TypeScript와 production bundle을 검증한다.
- 브라우저에서 세 화면의 guide line, `LOW · MID · HIGH` label, bar animation을 확인한다.
