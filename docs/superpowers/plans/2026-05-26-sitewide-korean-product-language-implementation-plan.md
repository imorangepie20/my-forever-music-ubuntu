# Sitewide Korean Product Language Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the approved Korean product language pass to the real music service surfaces while separating user guidance from operator diagnostics.

**Architecture:** Add a small frontend copy source of truth, then reuse it from navigation, page explainers, recommendation labels, and operator diagnostic blocks. Keep backend contracts unchanged and map backend tokens to Korean labels in the UI.

**Tech Stack:** React 18, TypeScript, Vite, Tailwind CSS, Playwright, Node regression harness scripts.

---

## File Structure

- Create `apps/web/src/lib/productLanguage.ts`
  - Single source for PMS/EMS/GMS terms, action labels, recommendation signal labels, and page explanation copy.
- Create `apps/web/src/components/common/PageExplanation.tsx`
  - Compact explanatory block for product pages. No card nesting. Use as a light, full-width section inside page content.
- Create `apps/web/src/components/common/OperatorDiagnosticsNotice.tsx`
  - Reusable header/notice for raw recommendation or admin-only diagnostic data.
- Create `apps/web/scripts/sitewide-korean-product-language-harness.mjs`
  - Static regression harness that checks required Korean product terms and catches major English leftovers in in-scope files.
- Modify `apps/web/package.json`
  - Add `test:product-language`.
- Modify layout and navigation:
  - `apps/web/src/components/layout/Header.tsx`
  - `apps/web/src/components/layout/Sidebar.tsx`
- Modify shared music components:
  - `apps/web/src/components/music/PlaybackDock.tsx`
  - `apps/web/src/components/music/PlaylistFeatureCard.tsx`
  - `apps/web/src/components/music/TrackFeatureCard.tsx`
- Modify real product pages:
  - `apps/web/src/pages/HomePage.tsx`
  - `apps/web/src/components/home/AlgorithmIntroSection.tsx`
  - `apps/web/src/components/home/GmsRecommendedPlaylistsSection.tsx`
  - `apps/web/src/components/home/HeroEqBanner.tsx`
  - `apps/web/src/components/home/LatestTracksSection.tsx`
  - `apps/web/src/components/home/MelonHot100Section.tsx`
  - `apps/web/src/pages/auth/Login.tsx`
  - `apps/web/src/pages/auth/Register.tsx`
  - `apps/web/src/pages/platforms/PlatformOAuthCallbackPage.tsx`
  - `apps/web/src/pages/PlatformsPage.tsx`
  - `apps/web/src/pages/PmsPage.tsx`
  - `apps/web/src/pages/PmsPlaylistDetailPage.tsx`
  - `apps/web/src/pages/EmsPage.tsx`
  - `apps/web/src/pages/EmsPlaylistDetailPage.tsx`
  - `apps/web/src/pages/EmsSearchPlaylistDetailPage.tsx`
  - `apps/web/src/pages/GmsPlaylistsPage.tsx`
  - `apps/web/src/pages/GmsPreviewPage.tsx`
  - `apps/web/src/pages/ArtistDetailPage.tsx`
  - `apps/web/src/pages/MelonHot100Page.tsx`
  - `apps/web/src/pages/RecommendationAlgorithmPage.tsx`
- Modify active operator/admin pages:
  - `apps/web/src/pages/SchedulingAdminPage.tsx`
  - `apps/web/src/pages/EmsAcquisitionAdminPage.tsx`
  - `apps/web/src/pages/EmsPoolAdminPage.tsx`
  - `apps/web/src/pages/PlaylistQualityAdminPage.tsx`
  - `apps/web/src/pages/FeatureCoverageAdminPage.tsx`
  - `apps/web/src/pages/SasrecModelAdminPage.tsx`
  - `apps/web/src/pages/MetadataNormalizationAdminPage.tsx`
- Modify/add Playwright tests:
  - `apps/web/tests/e2e/recommendation-algorithm-page.spec.ts`
  - `apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts`
  - Create `apps/web/tests/e2e/sitewide-korean-product-language.spec.ts`

---

### Task 1: Add Shared Product Language Helpers And Static Harness

**Files:**
- Create: `apps/web/src/lib/productLanguage.ts`
- Create: `apps/web/scripts/sitewide-korean-product-language-harness.mjs`
- Modify: `apps/web/package.json`

- [ ] **Step 1: Write the failing static harness**

Create `apps/web/scripts/sitewide-korean-product-language-harness.mjs`:

```js
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'

const root = process.cwd()

const requiredByFile = new Map([
  ['src/lib/productLanguage.ts', ['내 음악 보관함(PMS)', '음악 탐색 풀(EMS)', '추천 게이트(GMS)', '운영자 전용 진단']],
  ['src/components/layout/Sidebar.tsx', ['내 음악(PMS)', '음악 탐색(EMS)', '추천 검토(GMS)']],
  ['src/components/layout/Header.tsx', ['내 음악 보관함(PMS)', '음악 탐색 풀(EMS)', '추천 게이트(GMS)']],
  ['src/pages/GmsPreviewPage.tsx', ['추천 게이트(GMS)', '운영자 전용 진단', '추천 모델과 게이트 상태를 점검하기 위한 정보입니다.']],
  ['src/pages/PmsPage.tsx', ['내 음악 보관함(PMS)']],
  ['src/pages/EmsPage.tsx', ['음악 탐색 풀(EMS)']],
  ['src/pages/auth/Login.tsx', ['로그인', '이어서 듣고 추천받기']],
  ['src/pages/auth/Register.tsx', ['회원가입', '내 음악 보관함(PMS)']],
])

const forbiddenByFile = new Map([
  ['src/pages/GmsPreviewPage.tsx', ['Recommendation Candidates', 'Response Feed', 'Request GMS Preview', 'Gate dry run']],
  ['src/pages/HomePage.tsx', ['Delivery Snapshot', 'Continue Onboarding', 'Open Platform Intake', 'Open GMS Preview']],
  ['src/pages/auth/Login.tsx', ['Session Restore', 'Sign back in', 'Continue local testing', 'Signing In...']],
  ['src/components/music/PlaybackDock.tsx', ['Preparing playback...', 'Shuffle on', 'Repeat queue', 'Open visualizer']],
])

const read = (relativePath) => fs.readFileSync(path.join(root, relativePath), 'utf8')

const failures = []

for (const [relativePath, requiredTexts] of requiredByFile) {
  const content = read(relativePath)
  for (const text of requiredTexts) {
    if (!content.includes(text)) {
      failures.push(`${relativePath} is missing required Korean copy: ${text}`)
    }
  }
}

for (const [relativePath, forbiddenTexts] of forbiddenByFile) {
  const content = read(relativePath)
  for (const text of forbiddenTexts) {
    if (content.includes(text)) {
      failures.push(`${relativePath} still contains old English product copy: ${text}`)
    }
  }
}

if (failures.length > 0) {
  console.error('[sitewide-korean-product-language] failed')
  for (const failure of failures) {
    console.error(`- ${failure}`)
  }
  process.exit(1)
}

console.log('[sitewide-korean-product-language] ok')
```

- [ ] **Step 2: Add package script**

Modify `apps/web/package.json` scripts:

```json
"test:product-language": "node scripts/sitewide-korean-product-language-harness.mjs"
```

Keep the existing scripts unchanged. Place it near `test:product-flow`.

- [ ] **Step 3: Run the harness and verify it fails**

Run:

```bash
cd apps/web
npm run test:product-language
```

Expected: FAIL with missing `src/lib/productLanguage.ts` and old English copy in current files.

- [ ] **Step 4: Create product language helper**

Create `apps/web/src/lib/productLanguage.ts`:

```ts
export const PRODUCT_TERMS = {
    pmsFull: '내 음악 보관함(PMS)',
    pmsShort: '내 음악(PMS)',
    emsFull: '음악 탐색 풀(EMS)',
    emsShort: '음악 탐색(EMS)',
    gmsFull: '추천 게이트(GMS)',
    gmsShort: '추천(GMS)',
} as const

export const ACTION_LABELS = {
    import: '가져오기',
    connect: '연결하기',
    preview: '미리보기',
    save: '저장하기',
    like: '좋아요',
    pass: '넘기기',
    play: '재생',
    playAll: '전체 재생',
    open: '열기',
    retry: '다시 시도',
    refresh: '새로고침',
    approve: '승인',
    reject: '거절',
    resolve: '해결',
} as const

export const OPERATOR_DIAGNOSTICS = {
    title: '운영자 전용 진단',
    description: '추천 모델과 게이트 상태를 점검하기 위한 정보입니다. 일반 사용자가 선택해야 하는 항목은 아닙니다.',
} as const

export const PAGE_EXPLANATIONS = {
    pms: {
        eyebrow: PRODUCT_TERMS.pmsFull,
        title: '플랫폼을 바꿔도 남는 내 음악 기준점',
        body: '연결한 스트리밍 플랫폼에서 가져온 플레이리스트와 사이트에서 저장한 추천곡이 모이는 개인 음악 보관함입니다.',
        flow: '플랫폼 연결 -> 플레이리스트 가져오기 -> 오디오 특성 보강 -> 취향 모델 학습',
    },
    ems: {
        eyebrow: PRODUCT_TERMS.emsFull,
        title: '외부 음악 후보를 모으는 탐색 공간',
        body: '외부 공개 플레이리스트와 트렌드에서 새로운 추천 후보를 찾는 탐색 공간입니다.',
        flow: '검색/수집 -> 후보 확인 -> 재생/상세 확인 -> GMS 추천 후보로 활용',
    },
    gmsPreview: {
        eyebrow: PRODUCT_TERMS.gmsFull,
        title: '추천 후보를 듣고 저장하기 전에 검토합니다',
        body: 'PMS 보관함과 EMS 후보를 비교해 지금 저장하거나 들어볼 만한 추천 곡을 검토합니다.',
        flow: '기준 선택 -> 추천 미리보기 생성 -> 후보 듣기/좋아요/넘기기/저장 -> PMS 취향 신호에 반영',
    },
    gmsPlaylists: {
        eyebrow: PRODUCT_TERMS.gmsFull,
        title: '취향 모델이 통과시킨 플레이리스트',
        body: 'EMS에서 수집한 플레이리스트 중 내 취향 모델을 통과한 묶음을 확인하고 PMS에 저장합니다.',
        flow: 'EMS 후보 수집 -> 취향 모델 평가 -> 추천 플레이리스트 확인 -> PMS 저장',
    },
} as const

export const RECOMMENDATION_SIGNAL_LABELS: Record<string, { label: string; description: string }> = {
    affinity: {
        label: '취향 일치도',
        description: '사용자 취향 신호와 후보가 얼마나 가까운지 봅니다.',
    },
    novelty: {
        label: '새로움',
        description: '최근 청취 패턴과 적당히 다른 발견인지 봅니다.',
    },
    coherence: {
        label: '흐름 안정성',
        description: '플레이리스트 안에서 분위기와 출처 흐름이 자연스러운지 봅니다.',
    },
    diversity: {
        label: '다양성',
        description: '아티스트, 장르, 플랫폼 분포가 한쪽으로 치우치지 않는지 봅니다.',
    },
    redundancy: {
        label: '반복 위험',
        description: '이미 비슷한 아티스트나 곡이 너무 많지 않은지 봅니다.',
    },
    confidence: {
        label: '근거 신뢰도',
        description: 'trackId, 오디오 특성, 출처 플레이리스트 단서가 충분한지 봅니다.',
    },
}

export const recommendationSignalLabel = (axis: string) =>
    RECOMMENDATION_SIGNAL_LABELS[axis]?.label ?? axis

export const recommendationSignalDescription = (axis: string) =>
    RECOMMENDATION_SIGNAL_LABELS[axis]?.description ?? '추천 계산에 사용된 내부 신호입니다.'
```

- [ ] **Step 5: Run harness and verify the helper part passes but UI copy still fails**

Run:

```bash
cd apps/web
npm run test:product-language
```

Expected: FAIL only for UI files that have not been converted yet.

- [ ] **Step 6: Commit**

```bash
git add apps/web/package.json apps/web/scripts/sitewide-korean-product-language-harness.mjs apps/web/src/lib/productLanguage.ts
git commit -m "test: add sitewide Korean product language harness"
```

---

### Task 2: Add Explanation And Operator Diagnostic Components

**Files:**
- Create: `apps/web/src/components/common/PageExplanation.tsx`
- Create: `apps/web/src/components/common/OperatorDiagnosticsNotice.tsx`

- [ ] **Step 1: Create page explanation component**

Create `apps/web/src/components/common/PageExplanation.tsx`:

```tsx
import { ArrowRight, Info } from 'lucide-react'

interface PageExplanationProps {
    eyebrow: string
    title: string
    body: string
    flow?: string
}

const PageExplanation = ({ eyebrow, title, body, flow }: PageExplanationProps) => {
    const flowSteps = flow?.split('->').map((step) => step.trim()).filter(Boolean) ?? []

    return (
        <section className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/70 p-5">
            <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
                <div className="max-w-3xl">
                    <div className="inline-flex items-center gap-2 rounded-full border border-hud-border-primary bg-hud-accent-primary/10 px-3 py-1 text-[11px] font-semibold text-hud-accent-primary">
                        <Info size={13} />
                        {eyebrow}
                    </div>
                    <h2 className="mt-3 text-xl font-semibold text-hud-text-primary">{title}</h2>
                    <p className="mt-2 text-sm leading-6 text-hud-text-secondary">{body}</p>
                </div>

                {flowSteps.length > 0 && (
                    <ol className="flex min-w-0 flex-wrap items-center gap-2 text-xs text-hud-text-muted">
                        {flowSteps.map((step, index) => (
                            <li key={`${step}-${index}`} className="inline-flex items-center gap-2">
                                <span className="rounded-full border border-hud-border-secondary bg-hud-bg-primary px-2.5 py-1 text-hud-text-secondary">
                                    {step}
                                </span>
                                {index < flowSteps.length - 1 && <ArrowRight size={13} />}
                            </li>
                        ))}
                    </ol>
                )}
            </div>
        </section>
    )
}

export default PageExplanation
```

- [ ] **Step 2: Create operator diagnostics notice component**

Create `apps/web/src/components/common/OperatorDiagnosticsNotice.tsx`:

```tsx
import { ShieldCheck } from 'lucide-react'
import { OPERATOR_DIAGNOSTICS } from '@/lib/productLanguage'

interface OperatorDiagnosticsNoticeProps {
    compact?: boolean
}

const OperatorDiagnosticsNotice = ({ compact = false }: OperatorDiagnosticsNoticeProps) => (
    <div className={`rounded-xl border border-amber-300/30 bg-amber-300/10 ${compact ? 'p-3' : 'p-4'}`}>
        <div className="flex items-start gap-3">
            <span className="mt-0.5 rounded-lg bg-amber-300/15 p-2 text-amber-200">
                <ShieldCheck size={compact ? 14 : 16} />
            </span>
            <div>
                <p className="text-xs font-semibold text-amber-100">{OPERATOR_DIAGNOSTICS.title}</p>
                <p className="mt-1 text-xs leading-5 text-hud-text-muted">{OPERATOR_DIAGNOSTICS.description}</p>
            </div>
        </div>
    </div>
)

export default OperatorDiagnosticsNotice
```

- [ ] **Step 3: Run TypeScript build to catch import/path issues**

Run:

```bash
cd apps/web
npm run build
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add apps/web/src/components/common/PageExplanation.tsx apps/web/src/components/common/OperatorDiagnosticsNotice.tsx
git commit -m "feat: add Korean page explanation components"
```

---

### Task 3: Update Layout, Navigation, Auth, Home, And Shared Playback Copy

**Files:**
- Modify: `apps/web/src/components/layout/Sidebar.tsx`
- Modify: `apps/web/src/components/layout/Header.tsx`
- Modify: `apps/web/src/pages/auth/Login.tsx`
- Modify: `apps/web/src/pages/auth/Register.tsx`
- Modify: `apps/web/src/pages/HomePage.tsx`
- Modify: `apps/web/src/components/home/AlgorithmIntroSection.tsx`
- Modify: `apps/web/src/components/home/GmsRecommendedPlaylistsSection.tsx`
- Modify: `apps/web/src/components/music/PlaybackDock.tsx`
- Modify: `apps/web/src/components/music/PlaylistFeatureCard.tsx`
- Modify: `apps/web/src/components/music/TrackFeatureCard.tsx`

- [ ] **Step 1: Run product-language harness to verify current failures**

Run:

```bash
cd apps/web
npm run test:product-language
```

Expected: FAIL on layout/auth/home/playback copy.

- [ ] **Step 2: Update Sidebar route policy**

In `apps/web/src/components/layout/Sidebar.tsx`, import:

```ts
import { PRODUCT_TERMS } from '@/lib/productLanguage'
```

Use these primary menu item labels:

```ts
const menuItems: MenuItem[] = [
    {
        label: '홈',
        description: '오늘 들을 음악과 추천 흐름',
        icon: <Home size={20} />,
        path: '/',
    },
    {
        label: '플랫폼 연결',
        description: '스트리밍 계정과 플레이리스트 가져오기',
        icon: <Radio size={20} />,
        path: '/platforms',
    },
    {
        label: PRODUCT_TERMS.pmsShort,
        description: '플랫폼을 바꿔도 남는 내 음악',
        icon: <Music2 size={20} />,
        path: '/pms',
    },
    {
        label: PRODUCT_TERMS.emsShort,
        description: '외부 공개 플레이리스트 후보',
        icon: <SlidersHorizontal size={20} />,
        path: '/ems',
    },
    {
        label: '추천 플레이리스트',
        description: '취향 모델이 고른 묶음',
        icon: <Sparkles size={20} />,
        path: '/gms-playlists',
    },
    {
        label: '추천 검토(GMS)',
        description: '후보를 듣고 저장하거나 넘기기',
        icon: <Sparkles size={20} />,
        path: '/gms-preview',
    },
]
```

Remove `/playback-harness` and `/tidal-playlist-test` from `menuItems`. Keep their routes in `App.tsx`.

- [ ] **Step 3: Update Header page copy**

In `apps/web/src/components/layout/Header.tsx`, import:

```ts
import { PRODUCT_TERMS } from '@/lib/productLanguage'
```

Replace `pageCopy` entries for product routes with Korean product terms:

```ts
'/pms': {
    title: PRODUCT_TERMS.pmsFull,
    subtitle: '가져온 플레이리스트와 저장한 추천곡이 쌓이는 개인 음악 기준점입니다.',
},
'/ems': {
    title: PRODUCT_TERMS.emsFull,
    subtitle: '외부 공개 플레이리스트와 트렌드에서 새로운 후보를 찾습니다.',
},
'/gms-playlists': {
    title: '추천 플레이리스트',
    subtitle: '추천 게이트를 통과한 플레이리스트를 확인하고 내 음악 보관함에 저장하세요.',
},
'/gms-preview': {
    title: '추천 검토(GMS)',
    subtitle: '후보를 듣고 좋아요, 넘기기, 저장하기로 취향 신호를 남깁니다.',
},
```

Keep test routes but label them as diagnostic:

```ts
'/playback-harness': {
    title: '재생 진단 화면',
    subtitle: 'Spotify, TIDAL, YouTube 재생 경계를 운영자가 확인합니다.',
},
'/tidal-playlist-test': {
    title: 'TIDAL 재생 진단',
    subtitle: 'TIDAL 플레이리스트 재생을 격리해서 확인합니다.',
},
```

- [ ] **Step 4: Convert auth pages**

In `Login.tsx`, replace the main visible copy:

```tsx
<ShieldCheck size={15} />
세션 복원
```

```tsx
<h1 className="mt-6 text-4xl font-semibold tracking-tight text-hud-text-primary sm:text-5xl">
    이어서 듣고 추천받기 위해 다시 로그인하세요.
</h1>
<p className="mt-5 max-w-xl text-base leading-7 text-hud-text-secondary">
    로그인하면 연결한 플랫폼, 가져온 플레이리스트, 추천 검토 상태를 이어서 사용할 수 있습니다.
</p>
```

Use these three guide cards:

```ts
[
    { title: '계정 복원', body: '이메일과 비밀번호로 내 음악 보관함을 다시 불러옵니다.' },
    { title: '진행 단계 이어가기', body: '플랫폼 연결, PMS 가져오기, 추천 검토 중 멈춘 곳으로 돌아갑니다.' },
    { title: '취향 신호 유지', body: '좋아요, 저장, 재생 기록이 추천 모델에 계속 이어집니다.' },
]
```

Replace button/label copy:

```tsx
Sign In -> 로그인
Signing In... -> 로그인 중...
Back to workspace -> 홈으로 돌아가기
Continue -> 이어서 진행
Open Control Room -> 홈 열기
Need a fresh account? -> 처음 오셨나요?
Create one here -> 회원가입하기
Email -> 이메일
Password -> 비밀번호
```

Apply the same language style to `Register.tsx`: use `회원가입`, `내 음악 보관함(PMS)`, `플랫폼 연결`, and `추천 받을 준비`.

- [ ] **Step 5: Convert HomePage operational copy**

In `HomePage.tsx`, replace the “Delivery Snapshot” section with Korean product language:

```tsx
<p className="text-xs font-semibold uppercase tracking-[0.28em] text-hud-accent-primary">
    제품 흐름
</p>
<h2 className="mt-4 max-w-3xl text-3xl font-semibold tracking-tight text-hud-text-primary sm:text-4xl">
    플랫폼에서 가져온 음악을 내 보관함에 남기고, 추천으로 다시 이어갑니다.
</h2>
<p className="mt-4 max-w-2xl text-base leading-7 text-hud-text-secondary">
    My Forever Music은 PMS, EMS, GMS 흐름으로 내 음악을 보존하고 새로운 후보를 추천합니다.
</p>
```

Replace home CTA labels:

```tsx
Continue Onboarding -> 이어서 설정하기
Start Signup -> 회원가입 시작
Sign In -> 로그인
Open Platform Intake -> 플랫폼 연결 열기
Open GMS Preview -> 추천 검토 열기
Spring Boot Docs -> API 문서
FastAPI Docs -> AI 문서
System Signal -> 시스템 상태
Timestamp -> 확인 시각
```

- [ ] **Step 6: Convert PlaybackDock labels**

In `PlaybackDock.tsx`, replace English labels:

```ts
const repeatLabel =
    repeatMode === 'one' ? '현재 곡 반복'
    : repeatMode === 'all' ? '재생목록 반복'
    : '반복 끄기'
```

```ts
const repeatShortLabel = (repeatMode: string) =>
    repeatMode === 'one' ? '1곡' : repeatMode === 'all' ? '전체' : '끔'
```

Replace button aria labels/titles:

```tsx
label={shuffleEnabled ? '무작위 재생 켜짐' : '무작위 재생 꺼짐'}
label="이전 곡"
label={isPlaying ? '일시정지' : '재생 계속'}
label="다음 곡"
aria-label="재생 음량"
aria-label={likeController.liked ? '좋아요 취소' : '좋아요'}
title={likeController.liked ? '좋아요 취소' : '좋아요'}
aria-label="비주얼라이저 열기"
title="비주얼라이저 열기"
aria-label="플랫폼에서 열기"
aria-label="플레이어 닫기"
```

Replace fallback text:

```tsx
{error ?? notice ?? '재생 준비 중...'}
```

- [ ] **Step 7: Run harness and build**

Run:

```bash
cd apps/web
npm run test:product-language
npm run build
```

Expected: both PASS for the files covered by this task.

- [ ] **Step 8: Commit**

```bash
git add apps/web/src/components/layout/Sidebar.tsx apps/web/src/components/layout/Header.tsx apps/web/src/pages/auth/Login.tsx apps/web/src/pages/auth/Register.tsx apps/web/src/pages/HomePage.tsx apps/web/src/components/home/AlgorithmIntroSection.tsx apps/web/src/components/home/GmsRecommendedPlaylistsSection.tsx apps/web/src/components/music/PlaybackDock.tsx apps/web/src/components/music/PlaylistFeatureCard.tsx apps/web/src/components/music/TrackFeatureCard.tsx
git commit -m "feat: localize core shell and playback copy"
```

---

### Task 4: Update PMS, EMS, And GMS Product Pages

**Files:**
- Modify: `apps/web/src/pages/PmsPage.tsx`
- Modify: `apps/web/src/pages/PmsPlaylistDetailPage.tsx`
- Modify: `apps/web/src/pages/EmsPage.tsx`
- Modify: `apps/web/src/pages/EmsPlaylistDetailPage.tsx`
- Modify: `apps/web/src/pages/EmsSearchPlaylistDetailPage.tsx`
- Modify: `apps/web/src/pages/GmsPlaylistsPage.tsx`
- Modify: `apps/web/src/pages/GmsPreviewPage.tsx`
- Modify: `apps/web/src/pages/ArtistDetailPage.tsx`
- Modify: `apps/web/src/pages/MelonHot100Page.tsx`
- Modify: `apps/web/src/pages/RecommendationAlgorithmPage.tsx`
- Modify: `apps/web/tests/e2e/recommendation-algorithm-page.spec.ts`
- Modify: `apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts`

- [ ] **Step 1: Add page explainers to PMS, EMS, and GMS pages**

Import where used:

```ts
import PageExplanation from '@/components/common/PageExplanation'
import { PAGE_EXPLANATIONS } from '@/lib/productLanguage'
```

At the top of each page content:

```tsx
<PageExplanation {...PAGE_EXPLANATIONS.pms} />
```

```tsx
<PageExplanation {...PAGE_EXPLANATIONS.ems} />
```

```tsx
<PageExplanation {...PAGE_EXPLANATIONS.gmsPlaylists} />
```

```tsx
<PageExplanation {...PAGE_EXPLANATIONS.gmsPreview} />
```

- [ ] **Step 2: Convert GmsPreviewPage labels**

In `GmsPreviewPage.tsx`, replace form and response labels:

```tsx
GMS Approval Request -> 추천 검토 요청
Generate candidates from the current PMS playlist and EMS pool. -> 현재 PMS 보관함과 EMS 후보를 기준으로 추천을 미리 생성합니다.
Mood -> 분위기
Focus -> 집중
Calm -> 차분함
Upbeat -> 밝고 에너지 있게
Melancholy -> 차분한 감성
Discovery -> 새로운 발견
Energy -> 에너지
Bias -> 익숙함
Limit -> 후보 수
Include explanation strings in the preview response -> 추천 근거 함께 보기
Back to EMS -> EMS로 돌아가기
Generating Preview -> 추천 생성 중
Preview EMS Fallback -> EMS 후보 미리보기
Request GMS Preview -> 추천 미리보기 요청
Response Feed -> 추천 처리 결과
Recommendation Candidates -> 추천 후보
```

Use `ACTION_LABELS.like`, `ACTION_LABELS.pass`, and `ACTION_LABELS.save` for card buttons.

- [ ] **Step 3: Move raw GMS technical evidence behind operator diagnostics notice**

Import:

```ts
import OperatorDiagnosticsNotice from '@/components/common/OperatorDiagnosticsNotice'
import { recommendationSignalDescription, recommendationSignalLabel } from '@/lib/productLanguage'
```

Inside `RecommendationExplanationPanel`, keep user-facing headline first, then wrap raw axis/gate/taste-mode content in:

```tsx
<div className="mt-4 space-y-3">
    <OperatorDiagnosticsNotice compact />
    <div className="grid gap-2 text-xs text-hud-text-muted">
        {topEvidence(item.axis_evidence).map((evidence) => (
            <div key={evidence.axis} className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/60 p-3">
                <p className="font-semibold text-hud-text-secondary">
                    {evidence.axis} · {recommendationSignalLabel(evidence.axis)}
                </p>
                <p className="mt-1 leading-5">
                    {recommendationSignalDescription(evidence.axis)}
                </p>
            </div>
        ))}
    </div>
</div>
```

Preserve the existing evidence data fields, but enforce this display order in the component: Korean user reason first, `OperatorDiagnosticsNotice` second, raw tokens third.

- [ ] **Step 4: Convert RecommendationAlgorithmPage test expectations**

Update `apps/web/tests/e2e/recommendation-algorithm-page.spec.ts` to Korean expectations:

```ts
await expect(page.getByRole('heading', { name: '추천 운영 시스템' })).toBeVisible()
await expect(page.getByRole('heading', { name: 'PMS 입력' })).toBeVisible()
await expect(page.getByRole('heading', { name: '후보 정제' })).toBeVisible()
await expect(page.getByRole('heading', { name: '피드백 순환' })).toBeVisible()
await expect(page.getByLabel('플레이리스트 추천 신호 흐름 다이어그램')).toBeVisible()
await expect(page.getByLabel('추천 색상 범례')).toBeVisible()
await expect(page.getByText('취향 그래프')).toBeVisible()
await expect(page.getByText('스코어링 엔진')).toBeVisible()
await expect(page.getByText('GMS 플레이리스트 출력')).toBeVisible()
await expect(page.getByText('피드백 루프', { exact: true })).toBeVisible()
await expect(page.getByRole('heading', { name: '6축 평가 보드' })).toBeVisible()
await expect(page.getByRole('link', { name: '추천 플레이리스트 보기' })).toHaveAttribute('href', '/gms-playlists')
```

- [ ] **Step 5: Update GMS preview affinity test expectations**

In `apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts`, keep fixtures but update visible text assertions to expect:

```ts
await expect(page.getByText('추천 게이트(GMS)')).toBeVisible()
await expect(page.getByText('운영자 전용 진단')).toBeVisible()
await expect(page.getByText('추천 모델과 게이트 상태를 점검하기 위한 정보입니다.')).toBeVisible()
await expect(page.getByText('취향 일치도')).toBeVisible()
await expect(page.getByText('근거 신뢰도')).toBeVisible()
```

- [ ] **Step 6: Run targeted tests**

Run:

```bash
cd apps/web
npm run test:product-language
npm run test:e2e -- recommendation-algorithm-page.spec.ts gms-preview-taste-mode-affinity.spec.ts
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add apps/web/src/pages/PmsPage.tsx apps/web/src/pages/PmsPlaylistDetailPage.tsx apps/web/src/pages/EmsPage.tsx apps/web/src/pages/EmsPlaylistDetailPage.tsx apps/web/src/pages/EmsSearchPlaylistDetailPage.tsx apps/web/src/pages/GmsPlaylistsPage.tsx apps/web/src/pages/GmsPreviewPage.tsx apps/web/src/pages/ArtistDetailPage.tsx apps/web/src/pages/MelonHot100Page.tsx apps/web/src/pages/RecommendationAlgorithmPage.tsx apps/web/tests/e2e/recommendation-algorithm-page.spec.ts apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts
git commit -m "feat: localize PMS EMS GMS product pages"
```

---

### Task 5: Update Active Operator/Admin Pages

**Files:**
- Modify: `apps/web/src/pages/SchedulingAdminPage.tsx`
- Modify: `apps/web/src/pages/EmsAcquisitionAdminPage.tsx`
- Modify: `apps/web/src/pages/EmsPoolAdminPage.tsx`
- Modify: `apps/web/src/pages/PlaylistQualityAdminPage.tsx`
- Modify: `apps/web/src/pages/FeatureCoverageAdminPage.tsx`
- Modify: `apps/web/src/pages/SasrecModelAdminPage.tsx`
- Modify: `apps/web/src/pages/MetadataNormalizationAdminPage.tsx`
- Modify: `apps/web/tests/e2e/feature-coverage-taste-mode-summary.spec.ts`

- [ ] **Step 1: Add operator diagnostics notice to admin-only guards**

Import into admin pages:

```ts
import OperatorDiagnosticsNotice from '@/components/common/OperatorDiagnosticsNotice'
```

In each admin-only guard card, replace English headings like `Scheduling Admin` and `Metadata Normalization Admin` with Korean:

```tsx
<h2 className="text-xl font-semibold">운영자 전용 화면</h2>
<p className="mt-2 text-sm leading-6 text-hud-text-secondary">
    이 화면은 관리자 계정에만 노출됩니다.
</p>
<div className="mt-4">
    <OperatorDiagnosticsNotice compact />
</div>
```

- [ ] **Step 2: Convert active admin page headings**

Use these page titles:

```text
Scheduling Admin -> 스케줄 관리
EMS Acquisition Admin -> EMS 수집 관리
EMS Pool Admin -> EMS 큐 관리
Recommendation Quality Admin -> 추천 품질 관리
Feature Coverage -> 특성 커버리지
SASRec Model Admin -> SASRec 모델 관리
Metadata Normalization Admin -> 메타데이터 정규화
Taste Mode Rollout -> 취향 모드 반영 상태
Boost Applied -> 부스트 적용
Rank Changed -> 순위 변경
```

Keep backend status tokens such as `eligible`, `low_mode_similarity`, and model versions inside diagnostic tables, but add a Korean heading or description above the table.

- [ ] **Step 3: Update feature coverage e2e expectations**

In `apps/web/tests/e2e/feature-coverage-taste-mode-summary.spec.ts`, replace:

```ts
const rolloutPanel = page.locator('section', { hasText: 'Taste Mode Rollout' }).first()
await expect(rolloutPanel.getByRole('heading', { name: 'Taste Mode Rollout' })).toBeVisible()
await expect(rolloutPanel.getByLabel('Boost Applied 40')).toBeVisible()
await expect(rolloutPanel.getByLabel('Rank Changed 12')).toBeVisible()
```

with:

```ts
const rolloutPanel = page.locator('section', { hasText: '취향 모드 반영 상태' }).first()
await expect(rolloutPanel.getByRole('heading', { name: '취향 모드 반영 상태' })).toBeVisible()
await expect(rolloutPanel.getByLabel('부스트 적용 40')).toBeVisible()
await expect(rolloutPanel.getByLabel('순위 변경 12')).toBeVisible()
```

Replace failure expectation:

```ts
await expect(page.getByText('취향 모드 반영 상태를 불러오지 못했습니다.')).toBeVisible()
```

- [ ] **Step 4: Run admin targeted tests**

Run:

```bash
cd apps/web
npm run test:e2e -- feature-coverage-taste-mode-summary.spec.ts
npm run build
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/pages/SchedulingAdminPage.tsx apps/web/src/pages/EmsAcquisitionAdminPage.tsx apps/web/src/pages/EmsPoolAdminPage.tsx apps/web/src/pages/PlaylistQualityAdminPage.tsx apps/web/src/pages/FeatureCoverageAdminPage.tsx apps/web/src/pages/SasrecModelAdminPage.tsx apps/web/src/pages/MetadataNormalizationAdminPage.tsx apps/web/tests/e2e/feature-coverage-taste-mode-summary.spec.ts
git commit -m "feat: mark operator diagnostics across admin pages"
```

---

### Task 6: Add Sitewide E2E Smoke Test And Final Verification

**Files:**
- Create: `apps/web/tests/e2e/sitewide-korean-product-language.spec.ts`
- Modify: `apps/web/package.json` if `test:regression` should include product language harness

- [ ] **Step 1: Create e2e smoke test**

Create `apps/web/tests/e2e/sitewide-korean-product-language.spec.ts`:

```ts
import { expect, test, type Route } from '@playwright/test'

const userSession = {
    userId: 'user-language-e2e',
    email: 'language@example.com',
    displayName: '한국어 사용자',
    preferredPlatformId: 'tidal',
    onboardingStage: 'ready',
    registeredAt: '2026-05-26T00:00:00Z',
    platformConnectionRequired: false,
    nextStepPath: '/gms-preview',
    nextStepMessage: '추천 검토를 이어갈 수 있습니다.',
}

const fulfillJson = (route: Route, body: unknown) =>
    route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(body),
    })

test.beforeEach(async ({ page }) => {
    await page.addInitScript((session) => {
        window.localStorage.setItem('my-forever-music.auth-session', JSON.stringify(session))
    }, userSession)

    await page.route('**/api/v1/system/info', (route) =>
        fulfillJson(route, {
            service: 'api',
            status: 'ok',
            message: 'ready',
            timestamp: '2026-05-26T00:00:00Z',
        }),
    )
})

test('primary shell uses Korean product language', async ({ page }) => {
    await page.goto('/')

    await expect(page.getByRole('link', { name: /내 음악\(PMS\)/ })).toBeVisible()
    await expect(page.getByRole('link', { name: /음악 탐색\(EMS\)/ })).toBeVisible()
    await expect(page.getByRole('link', { name: /추천 검토\(GMS\)/ })).toBeVisible()
    await expect(page.getByText('플랫폼에서 가져온 음악을 내 보관함에 남기고')).toBeVisible()
    await expect(page.getByText('Delivery Snapshot')).toHaveCount(0)
})

test('login page explains the user flow in Korean', async ({ page }) => {
    await page.goto('/login')

    await expect(page.getByRole('heading', { name: '이어서 듣고 추천받기 위해 다시 로그인하세요.' })).toBeVisible()
    await expect(page.getByText('계정 복원')).toBeVisible()
    await expect(page.getByRole('button', { name: '로그인' })).toBeVisible()
    await expect(page.getByText('Session Restore')).toHaveCount(0)
})
```

- [ ] **Step 2: Include product-language harness in regression script**

Update `apps/web/package.json`:

```json
"test:regression": "npm run test:policy && npm run test:product-language && npm run test:product-flow && npm run test:playback"
```

- [ ] **Step 3: Run full frontend verification**

Run:

```bash
cd apps/web
npm run test:product-language
npm run test:e2e -- sitewide-korean-product-language.spec.ts recommendation-algorithm-page.spec.ts gms-preview-taste-mode-affinity.spec.ts feature-coverage-taste-mode-summary.spec.ts
npm run build
```

Expected: all PASS.

- [ ] **Step 4: Commit**

```bash
git add apps/web/tests/e2e/sitewide-korean-product-language.spec.ts apps/web/package.json
git commit -m "test: cover Korean product language surfaces"
```

- [ ] **Step 5: Final route scan**

Run:

```bash
cd apps/web
rg -n "Delivery Snapshot|Session Restore|Recommendation Candidates|Response Feed|Request GMS Preview|Gate dry run|Preparing playback|Open Platform Intake|Open GMS Preview" src
```

Expected: no matches in in-scope product files.

Do not remove matches from out-of-scope template/demo pages in this task. Any match in a product file must be fixed before repeating `npm run test:product-language` and `npm run build`.

---

## Self-Review

- Spec coverage:
  - Korean product terms are implemented through `productLanguage.ts`.
  - Page-level explanations are implemented through `PageExplanation`.
  - Operator diagnostics are implemented through `OperatorDiagnosticsNotice`.
  - GMS raw technical evidence is separated from user-facing recommendation reasons.
  - Template/demo pages are not promoted into primary navigation.
- Placeholder scan:
  - No `TBD`, `TODO`, or “similar to” steps are used.
- Type consistency:
  - `PRODUCT_TERMS`, `ACTION_LABELS`, `OPERATOR_DIAGNOSTICS`, `PAGE_EXPLANATIONS`, `recommendationSignalLabel`, and `recommendationSignalDescription` are defined before being used.
- Verification:
  - Static harness, targeted Playwright tests, and build are included before final completion.
