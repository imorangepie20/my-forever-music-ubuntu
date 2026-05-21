# GMS Preview Taste Mode Affinity UI Design

작성일: `2026-05-21`

## 1. Purpose

GMS preview는 이미 백엔드에서 `items[].taste_mode_affinity`를 내려줄 수 있다. 이 값은 heavy audio taste profile 사용자의 추천 후보가 어떤 taste mode와 가까운지 보여주는 inspection-only 신호다.

이번 단계의 목적은 새 관리자 페이지를 만들지 않고, 기존 `/gms-preview` 후보 카드 안에서 이 신호를 바로 확인하게 하는 것이다. 추천 품질에 영향을 주기 전, 실제 후보 카드 위에서 mode label, similarity, distance, token을 눈으로 검토할 수 있어야 한다.

## 2. Product Decision

사용자가 선택한 방향은 `A · GMS Preview 확장형`이다.

- 기존 `/gms-preview` 화면 안에 표시한다.
- 별도 Audio Taste Lab 페이지는 만들지 않는다.
- `taste_mode_affinity`가 있는 후보 카드에만 작은 진단 영역을 추가한다.
- `taste_mode_affinity`가 없으면 아무 UI도 표시하지 않는다.
- ranking boost, backend API, context engine, audit model version은 변경하지 않는다.

## 3. Scope

이번 구현에서 하는 것:

- `apps/web/src/types/api.ts`에 `taste_mode_affinity` 타입을 추가한다.
- `apps/web/src/pages/GmsPreviewPage.tsx`의 GMS 후보 카드 아래에 affinity panel을 추가한다.
- panel에는 mode label, mode id, similarity, distance, tokens를 표시한다.
- 기존 `include_explanations` toggle이 켜져 있을 때 백엔드가 내려주는 값을 그대로 보여준다.

이번 구현에서 하지 않는 것:

- 새 route 또는 새 admin page 생성
- 백엔드 ranking score 변경
- taste mode affinity boost 적용
- affinity run history 저장
- 차트/트렌드/승격 게이트 구현

## 4. UI Contract

`GmsRecommendationPreviewResponse.items[]`에 아래 optional field를 추가한다.

```ts
type TasteModeAffinity = {
    applied: boolean
    mode_id: string
    label: string
    similarity: number
    distance: number
    tokens: string[]
}
```

Render rules:

| Condition | UI |
| --- | --- |
| `item.taste_mode_affinity` exists | affinity panel 표시 |
| `item.taste_mode_affinity` is null/undefined | 표시하지 않음 |
| `tokens` empty | token chip 영역 생략 |
| `similarity`/`distance` number | 소수 2자리로 표시 |

## 5. Visual Layout

후보의 `TrackFeatureCard` 바로 아래, axis evidence 목록 위에 배치한다. 카드 자체의 play/save/like/pass 작업 흐름을 방해하지 않아야 한다.

Panel structure:

- 왼쪽: `Taste mode` badge와 `label`
- 보조 텍스트: `mode_id`
- 오른쪽: `similarity`와 `distance` 작은 metric
- 아래: `tokens` chip list

Visual tone:

- 기존 HUD palette와 Tailwind utility를 따른다.
- dominant color는 `hud-accent-primary`를 약하게 사용한다.
- compact diagnostic block으로 유지한다.
- 카드 안에 또 큰 카드처럼 보이지 않게, 얇은 border와 낮은 배경 대비만 사용한다.

## 6. Data Flow

1. 사용자가 `/gms-preview`에서 `include_explanations`를 켠 채 preview를 요청한다.
2. API가 heavy profile 후보에 `taste_mode_affinity`를 채워 내려준다.
3. web type이 field를 수용한다.
4. `GmsPreviewPage`는 item에 affinity가 있으면 panel을 렌더링한다.
5. affinity가 없으면 기존 UI와 동일하게 동작한다.

## 7. Error Handling

- API 응답에 field가 없어도 기존 GMS preview는 정상 렌더링한다.
- field가 null이면 조용히 숨긴다.
- token이 null로 들어오는 경우를 대비해 빈 배열처럼 처리한다.
- 숫자 포맷은 number일 때만 `toFixed(2)`를 사용한다.

## 8. Testing

Minimum verification:

- TypeScript build 또는 typecheck가 통과해야 한다.
- `GmsPreviewPage`가 `taste_mode_affinity` 없는 기존 응답에서도 깨지지 않아야 한다.
- affinity가 있는 mock/fixture 응답에서 label, similarity, distance, token이 렌더링되어야 한다.

Preferred verification:

- GMS preview 관련 regression harness 또는 Playwright smoke test에 affinity 표시 assertion을 추가한다.

## 9. Completion Criteria

- `/gms-preview` 후보 카드에서 taste mode affinity를 볼 수 있다.
- affinity가 없는 후보는 기존 UI와 같은 밀도로 보인다.
- 추천 순위, score, feedback, save, playback 흐름에는 변화가 없다.
- 설계와 실제 구현 범위가 일치한다.
