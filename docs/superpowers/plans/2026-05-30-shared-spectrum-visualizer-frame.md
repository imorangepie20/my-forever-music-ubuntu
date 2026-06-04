# Shared Spectrum Visualizer Frame Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 공개 mix player의 bar spectrum frame을 홈 preview EQ와 `/visualizer` 전체 화면 EQ에도 적용한다.

**Architecture:** `BarsVisualizer`는 audio bin animation만 유지한다. 새 `SpectrumVisualizerFrame`이 막대 animation, 수평 guide line, `LOW · MID · HIGH` label을 조합하고 세 화면에서 재사용한다. analyser와 재생 상태 흐름은 변경하지 않는다.

**Tech Stack:** React, TypeScript, Tailwind CSS, Vite, Node regression harness

---

### Task 1: Shared Spectrum Frame Regression Harness

**Files:**
- Create: `apps/web/scripts/shared-spectrum-visualizer-frame-harness.mjs`

- [ ] **Step 1: Write the failing harness**

```js
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { cwd, exit } from 'node:process'

const root = cwd()
const read = (path) => {
    const absolutePath = join(root, path)
    return existsSync(absolutePath) ? readFileSync(absolutePath, 'utf8') : ''
}

const frame = read('src/components/visualizer/SpectrumVisualizerFrame.tsx')
const hero = read('src/components/home/HeroEqBanner.tsx')
const overlay = read('src/components/visualizer/EqOverlay.tsx')
const publicPlayer = read('src/components/public-curation/PublicMixSpectrumPlayer.tsx')

const passed =
    /data-spectrum-visualizer-frame/.test(frame) &&
    /BarsVisualizer/.test(frame) &&
    /LOW/.test(frame) &&
    /MID/.test(frame) &&
    /HIGH/.test(frame) &&
    /SpectrumVisualizerFrame/.test(hero) &&
    /SpectrumVisualizerFrame/.test(overlay) &&
    /SpectrumVisualizerFrame/.test(publicPlayer)

if (!passed) {
    console.error('FAIL Shared spectrum frame should be reused by home, visualizer, and public mix player.')
    exit(1)
}

console.log('PASS Shared spectrum frame is reused by home, visualizer, and public mix player.')
```

- [ ] **Step 2: Run the harness to verify it fails**

Run: `cd apps/web && node scripts/shared-spectrum-visualizer-frame-harness.mjs`

Expected: FAIL because `SpectrumVisualizerFrame.tsx` does not exist yet.

### Task 2: Shared Spectrum Frame Component

**Files:**
- Create: `apps/web/src/components/visualizer/SpectrumVisualizerFrame.tsx`

- [ ] **Step 1: Add the minimal shared frame**

```tsx
import BarsVisualizer from '@/components/visualizer/animations/BarsVisualizer'
import type { VisualizerAnimationProps } from '@/components/visualizer/animations/types'

interface SpectrumVisualizerFrameProps extends VisualizerAnimationProps {
    className?: string
    showLabels?: boolean
}

const SpectrumVisualizerFrame = ({
    analyser,
    accentHex,
    isPlaying,
    className = '',
    showLabels = true,
}: SpectrumVisualizerFrameProps) => (
    <div
        data-spectrum-visualizer-frame
        className={`relative overflow-hidden ${className}`}
    >
        {[25, 50, 75].map((level) => (
            <span
                key={level}
                className="absolute inset-x-0 border-t border-white/[0.07]"
                style={{ bottom: `${level}%` }}
            />
        ))}
        {showLabels && (
            <div className="absolute inset-x-0 bottom-2 z-10 flex justify-between px-5 text-[10px] font-black uppercase tracking-[0.24em] text-white/34">
                <span>LOW</span>
                <span>MID</span>
                <span>HIGH</span>
            </div>
        )}
        <div className="absolute inset-x-0 bottom-0 flex h-full items-end justify-center">
            <BarsVisualizer analyser={analyser} accentHex={accentHex} isPlaying={isPlaying} />
        </div>
    </div>
)

export default SpectrumVisualizerFrame
```

### Task 3: Replace Screen-Specific EQ Markup

**Files:**
- Modify: `apps/web/src/components/public-curation/PublicMixSpectrumPlayer.tsx`
- Modify: `apps/web/src/components/home/HeroEqBanner.tsx`
- Modify: `apps/web/src/components/visualizer/EqOverlay.tsx`

- [ ] **Step 1: Use the shared frame in the public mix player**

Replace the direct `BarsVisualizer` import with:

```tsx
import SpectrumVisualizerFrame from '@/components/visualizer/SpectrumVisualizerFrame'
```

Replace the current spectrum wrapper, guide lines, labels, and direct animation with:

```tsx
<SpectrumVisualizerFrame
    analyser={analyser}
    accentHex="#67e8f9"
    isPlaying={isPlaying}
    className="h-60 border-y border-white/8 bg-[linear-gradient(180deg,rgba(14,116,144,0.14)_0%,rgba(15,23,42,0.28)_52%,rgba(2,6,23,0.72)_100%)] sm:h-72"
/>
```

- [ ] **Step 2: Use the compact shared frame in the home preview**

Replace the direct `BarsVisualizer` import with:

```tsx
import SpectrumVisualizerFrame from '@/components/visualizer/SpectrumVisualizerFrame'
```

Replace the home EQ block with:

```tsx
<SpectrumVisualizerFrame
    analyser={analyser}
    accentHex="#ffffff"
    isPlaying={isPlaying && !isMuted}
    className="h-28 w-full"
/>
```

- [ ] **Step 3: Use the full-screen shared frame in the visualizer overlay**

Replace the direct `BarsVisualizer` import with:

```tsx
import SpectrumVisualizerFrame from './SpectrumVisualizerFrame'
```

Replace the `bars` branch with:

```tsx
return (
    <SpectrumVisualizerFrame
        {...animationProps}
        className="h-full w-full"
    />
)
```

### Task 4: Verification

**Files:**
- Verify: `apps/web/scripts/shared-spectrum-visualizer-frame-harness.mjs`
- Verify: `apps/web/scripts/public-curation-share-page-harness.mjs`

- [ ] **Step 1: Run focused regression harnesses**

Run:

```bash
cd apps/web
node scripts/shared-spectrum-visualizer-frame-harness.mjs
node scripts/public-curation-share-page-harness.mjs
```

Expected: both commands print PASS and exit `0`.

- [ ] **Step 2: Run the production build**

Run: `cd apps/web && pnpm run build`

Expected: TypeScript compilation and Vite build complete successfully.

- [ ] **Step 3: Check diff whitespace**

Run: `git diff --check -- apps/web`

Expected: no output.

- [ ] **Step 4: Browser verification**

Verify these three surfaces:

- Home preview EQ shows bars, guide lines, and `LOW · MID · HIGH`.
- `/visualizer` shows bars, guide lines, and `LOW · MID · HIGH` over the cover.
- Public mix player retains its existing spectrum appearance.
