# Public Curation Play All Spectrum Player Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 공개 큐레이션 공유 페이지에 순차 `Play All` 재생과 대형 bar spectrum player를 추가한다.

**Architecture:** 기존 `tidalStreamPlayback.ts`와 `useTidalAudioAnalyser.ts`의 검증된 cross-origin PCM capture 경계를 유지한다. 공개 페이지는 재생 index orchestration을 담당하고, 새 `PublicMixSpectrumPlayer.tsx`는 player UI와 128-band EQ 표시만 담당한다.

**Tech Stack:** React, TypeScript, TIDAL stream playback boundary, captured PCM analyzer handle, Tailwind CSS, lucide-react

---

### Task 1: 공개 페이지 회귀 하네스

**Files:**
- Modify: `apps/web/scripts/public-curation-share-page-harness.mjs`

- [ ] **Step 1: Write the failing checks**

```js
/Play All/.test(files.page)
/PublicMixSpectrumPlayer/.test(files.page)
/playTrackAtIndex/.test(files.page)
/tidalPause/.test(files.page)
/tidalResume/.test(files.page)
```

- [ ] **Step 2: Run the harness and verify RED**

Run: `node scripts/public-curation-share-page-harness.mjs`

Expected: FAIL because the spectrum player and `Play All` orchestration do not exist.

### Task 2: 공개 mix spectrum player UI

**Files:**
- Create: `apps/web/src/components/public-curation/PublicMixSpectrumPlayer.tsx`

- [ ] **Step 1: Add a focused UI component**

The component receives the current track, snapshot, analyzer handle, playback state, and control callbacks. It renders:

```tsx
<BarsVisualizer analyser={analyser} accentHex="#67e8f9" isPlaying={isPlaying} />
```

around a `240px` spectrum area with frequency labels, amplitude guides, progress, and previous/play-next controls.

- [ ] **Step 2: Keep TIDAL concerns outside the component**

The component must not fetch streams, authorize TIDAL, or mutate sessions.

### Task 3: Play All orchestration

**Files:**
- Modify: `apps/web/src/pages/PublicCurationSharePage.tsx`

- [ ] **Step 1: Connect analyzer and snapshot**

Use:

```tsx
const analyser = useTidalAudioAnalyser(audioElement, playbackSnapshot.state === 'PLAYING')
```

and poll `getTidalCurrentSnapshot()` for progress.

- [ ] **Step 2: Add indexed playback**

Use `playTrackAtIndex(index)` as the only stream-start path. Its `onEnded` callback calls the next index when available.

- [ ] **Step 3: Add player controls**

Wire `tidalPause()`, `tidalResume()`, previous index, and next index.

- [ ] **Step 4: Replace hero CTA**

When a public session exists, render `Play All` and start from index `0`.

- [ ] **Step 5: Insert player**

Render `PublicMixSpectrumPlayer` between hero and track list.

### Task 4: Verification

**Files:**
- Verify only

- [ ] **Step 1: Run focused harnesses**

Run:

```bash
node scripts/public-curation-share-page-harness.mjs
node scripts/public-curation-tidal-oauth-harness.mjs
node scripts/public-curation-admin-page-harness.mjs
```

Expected: PASS.

- [ ] **Step 2: Build frontend**

Run: `npm run build`

Expected: PASS.

- [ ] **Step 3: Run backend regression tests touched by the public flow**

Run:

```bash
./gradlew test \
  --tests 'io.myforevermusic.api.modules.publiccuration.application.PublicCurationTidalOAuthServiceTest' \
  --tests 'io.myforevermusic.api.modules.publiccuration.presentation.PublicCurationTidalOAuthControllerWebMvcTest'
```

Expected: PASS.
