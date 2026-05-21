# GMS Preview Taste Mode Affinity UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show backend-provided `items[].taste_mode_affinity` inside existing `/gms-preview` recommendation candidate cards.

**Architecture:** Keep the change frontend-only. Add the response type in `apps/web/src/types/api.ts`, render a compact diagnostic panel in `apps/web/src/pages/GmsPreviewPage.tsx`, and verify with a focused Playwright smoke test that uses mocked API responses. Do not change backend scoring, ranking, context, or route structure.

**Tech Stack:** React 18, TypeScript, Vite, Tailwind utility classes, Playwright, existing `apps/web` API/types layout.

---

## File Structure

- Create `apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts`
  - Focused browser test for `/gms-preview`.
  - Mocks auth/session, PMS bootstrap, and GMS preview API responses.
  - Verifies affinity label, mode id, metrics, and tokens render when present.
  - Verifies candidates without affinity do not create an extra affinity panel.
- Modify `apps/web/src/types/api.ts`
  - Add `GmsTasteModeAffinity` interface.
  - Add optional `taste_mode_affinity?: GmsTasteModeAffinity | null` to GMS preview item type.
- Modify `apps/web/src/pages/GmsPreviewPage.tsx`
  - Add small formatting helpers for numeric affinity metrics and token normalization.
  - Add a local `TasteModeAffinityPanel` component.
  - Render the panel immediately below `TrackFeatureCard` and above axis evidence.
- No backend files should change.
- No route or sidebar item should be added.

Verification commands:

```bash
cd apps/web
npm run test:e2e -- tests/e2e/gms-preview-taste-mode-affinity.spec.ts
npm run build
```

---

### Task 1: Add Failing GMS Preview Affinity E2E Test

**Files:**
- Create: `apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts`

- [ ] **Step 1: Write the failing Playwright test**

Create `apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts`:

```ts
import { expect, test, type Route } from '@playwright/test'

const userSession = {
    userId: 'user-gms-affinity-e2e',
    email: 'gms-affinity@example.com',
    displayName: 'GMS Affinity User',
    preferredPlatformId: 'spotify',
    onboardingStage: 'ready',
    registeredAt: '2026-05-21T00:00:00Z',
    platformConnectionRequired: false,
    nextStepPath: '/gms-preview',
    nextStepMessage: 'Ready for GMS affinity review.',
}

const workspaceState = {
    userId: userSession.userId,
    playlistId: 'playlist-affinity',
    mood: 'upbeat',
    energyLevel: 4,
    familiarityBias: 2,
    limit: 2,
    includeExplanations: true,
}

const workspaceBootstrapResponse = {
    service: 'api',
    status: 'ok',
    generated_at: '2026-05-21T00:00:00Z',
    workspace_defaults: {
        user_id: userSession.userId,
        playlist_id: 'playlist-affinity',
        seed_track_ids: ['track-affinity-001'],
        seed_artist_names: ['Neon Bloom'],
        seed_genres: ['synth-pop'],
    },
    playlists: [
        {
            playlist_id: 'playlist-affinity',
            title: 'Affinity Source Library',
            source_platform: 'spotify',
            track_count: 2,
            curator: 'Forever Listener',
            highlight: 'Imported from the connected platform.',
            cover_image_url: null,
            platform_external_url: 'https://open.spotify.com/playlist/playlist-affinity',
            platform_uri: 'spotify:playlist:playlist-affinity',
            source_collection: 'pms-user-library',
        },
    ],
    suggested_tracks: [],
    suggested_artists: [],
    suggested_genres: [],
}

const gmsPreviewResponse = {
    request_id: 'preview-affinity-001',
    generated_at: '2026-05-21T00:01:00Z',
    service: 'api',
    status: 'ok',
    context: {
        strategy: 'gms-hybrid-blend',
        engine: 'gms-baseline-v1+audio-taste:v1',
        mode: 'gms',
        mood: 'upbeat',
        energy_level: 4,
        seed_basis: ['track-affinity-001'],
    },
    input_summary: {
        user_id: userSession.userId,
        playlist_id: 'playlist-affinity',
        track_seed_count: 1,
        artist_seed_count: 1,
        genre_seed_count: 1,
        familiarity_bias: 2,
        limit: 2,
    },
    items: [
        {
            rank: 1,
            track_id: 'track-affinity-001',
            title: 'Velvet Voltage',
            artist_name: 'Neon Bloom',
            source_platform: 'spotify',
            source_playlist_id: 'playlist-affinity',
            source_playlist_title: 'Affinity Source Library',
            album_title: 'Signal Bloom',
            album_image_url: null,
            platform_external_url: 'https://open.spotify.com/track/track-affinity-001',
            platform_uri: 'spotify:track:track-affinity-001',
            preview_url: null,
            spotify_track_id: 'spotify-track-affinity-001',
            audio_feature_track_id: 'spotify-track-affinity-001',
            duration_ms: 180000,
            score: 0.91,
            source_space: 'gms',
            energy_level: 4,
            reason: 'Audio taste matched this candidate.',
            taste_mode_affinity: {
                applied: true,
                mode_id: 'mode-1',
                label: 'high_energy_bright_danceable',
                similarity: 0.9321,
                distance: 0.0679,
                tokens: ['mode_energy_match', 'mode_valence_match'],
            },
            axis_evidence: [
                {
                    axis: 'confidence',
                    score: 0.91,
                    level: 'strong',
                    summary: 'Strong playable candidate.',
                },
            ],
        },
        {
            rank: 2,
            track_id: 'track-affinity-002',
            title: 'Quiet Static',
            artist_name: 'Distance Field',
            source_platform: 'spotify',
            source_playlist_id: 'playlist-affinity',
            source_playlist_title: 'Affinity Source Library',
            album_title: 'Signal Bloom',
            album_image_url: null,
            platform_external_url: 'https://open.spotify.com/track/track-affinity-002',
            platform_uri: 'spotify:track:track-affinity-002',
            preview_url: null,
            spotify_track_id: 'spotify-track-affinity-002',
            audio_feature_track_id: 'spotify-track-affinity-002',
            duration_ms: 181000,
            score: 0.73,
            source_space: 'gms',
            energy_level: 3,
            reason: 'Playable library candidate.',
            taste_mode_affinity: null,
            axis_evidence: [],
        },
    ],
    warnings: [],
}

const fulfillJson = (route: Route, body: unknown) =>
    route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(body),
    })

test('GMS preview renders taste mode affinity when backend provides it', async ({ page }) => {
    await page.addInitScript(({ session, workspace }) => {
        window.localStorage.setItem('my-forever-music.auth-session', JSON.stringify(session))
        window.localStorage.setItem('my-forever-music.recommendation-workspace', JSON.stringify(workspace))
    }, { session: userSession, workspace: workspaceState })

    await page.route('**/api/v1/pms/workspace/bootstrap**', (route) =>
        fulfillJson(route, workspaceBootstrapResponse),
    )
    await page.route('**/api/v1/gms/recommendations/preview', (route) =>
        fulfillJson(route, gmsPreviewResponse),
    )

    await page.goto('/gms-preview')
    await expect(page.getByRole('heading', { name: 'GMS Approval Request' })).toBeVisible()

    await page.getByRole('button', { name: /Request GMS Preview/ }).click()

    await expect(page.getByText('Velvet Voltage')).toBeVisible()
    await expect(page.getByText('Taste mode')).toHaveCount(1)
    await expect(page.getByText('high_energy_bright_danceable')).toBeVisible()
    await expect(page.getByText('mode-1')).toBeVisible()
    await expect(page.getByText('Similarity')).toBeVisible()
    await expect(page.getByText('0.93')).toBeVisible()
    await expect(page.getByText('Distance')).toBeVisible()
    await expect(page.getByText('0.07')).toBeVisible()
    await expect(page.getByText('mode_energy_match')).toBeVisible()
    await expect(page.getByText('mode_valence_match')).toBeVisible()
    await expect(page.getByText('Quiet Static')).toBeVisible()
})
```

- [ ] **Step 2: Run test to verify RED**

Run:

```bash
cd apps/web
npm run test:e2e -- tests/e2e/gms-preview-taste-mode-affinity.spec.ts
```

Expected: FAIL because the page does not render `Taste mode`, `high_energy_bright_danceable`, or the affinity metrics yet.

- [ ] **Step 3: Commit failing test**

Do not commit the failing test by itself. Keep it unstaged until Task 2 makes it pass.

---

### Task 2: Add API Type and Affinity Panel Rendering

**Files:**
- Modify: `apps/web/src/types/api.ts`
- Modify: `apps/web/src/pages/GmsPreviewPage.tsx`
- Test: `apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts`

- [ ] **Step 1: Add the frontend response type**

In `apps/web/src/types/api.ts`, add this interface near `GmsRecommendationPreviewResponse`:

```ts
export interface GmsTasteModeAffinity {
    applied: boolean
    mode_id: string
    label: string
    similarity: number
    distance: number
    tokens?: string[] | null
}
```

Then add the optional field to each GMS preview item:

```ts
taste_mode_affinity?: GmsTasteModeAffinity | null
```

The item type should include it near `reason` and `axis_evidence`:

```ts
reason?: string | null
taste_mode_affinity?: GmsTasteModeAffinity | null
axis_evidence?: GmsAxisEvidence[]
```

- [ ] **Step 2: Add local UI helpers in `GmsPreviewPage`**

In `apps/web/src/pages/GmsPreviewPage.tsx`, add these helpers below `axisLevelClass`:

```tsx
const formatAffinityMetric = (value: number | null | undefined) =>
    typeof value === 'number' && Number.isFinite(value) ? value.toFixed(2) : 'n/a'

const affinityTokens = (tokens: string[] | null | undefined) =>
    tokens?.filter((token) => token.trim().length > 0) ?? []
```

- [ ] **Step 3: Add a local `TasteModeAffinityPanel` component**

In `apps/web/src/pages/GmsPreviewPage.tsx`, add this component below the helper functions and above `openExternal`:

```tsx
type TasteModeAffinityPanelProps = {
    affinity: NonNullable<GmsRecommendationPreviewResponse['items'][number]['taste_mode_affinity']>
}

const TasteModeAffinityPanel = ({ affinity }: TasteModeAffinityPanelProps) => {
    const tokens = affinityTokens(affinity.tokens)

    return (
        <div
            aria-label={`Taste mode affinity ${affinity.mode_id}`}
            className="rounded-lg border border-hud-border-primary/40 bg-hud-accent-primary/10 p-3"
        >
            <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0">
                    <span className="inline-flex rounded-lg border border-hud-border-primary bg-hud-bg-primary/70 px-2.5 py-1 text-[10px] font-semibold uppercase text-hud-accent-primary">
                        Taste mode
                    </span>
                    <p className="mt-2 truncate text-sm font-semibold text-hud-text-primary">
                        {affinity.label}
                    </p>
                    <p className="mt-1 text-xs text-hud-text-muted">{affinity.mode_id}</p>
                </div>
                <div className="grid grid-cols-2 gap-2 text-right">
                    <div>
                        <p className="text-[10px] uppercase text-hud-text-muted">Similarity</p>
                        <p className="mt-1 text-sm font-semibold text-hud-text-primary">
                            {formatAffinityMetric(affinity.similarity)}
                        </p>
                    </div>
                    <div>
                        <p className="text-[10px] uppercase text-hud-text-muted">Distance</p>
                        <p className="mt-1 text-sm font-semibold text-hud-text-primary">
                            {formatAffinityMetric(affinity.distance)}
                        </p>
                    </div>
                </div>
            </div>

            {tokens.length > 0 && (
                <div className="mt-3 flex flex-wrap gap-1.5">
                    {tokens.map((token) => (
                        <span
                            key={`${affinity.mode_id}-${token}`}
                            className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary/60 px-2.5 py-1 text-[11px] text-hud-text-secondary"
                        >
                            {token}
                        </span>
                    ))}
                </div>
            )}
        </div>
    )
}
```

- [ ] **Step 4: Render panel below `TrackFeatureCard`**

In `apps/web/src/pages/GmsPreviewPage.tsx`, inside `response.items.map`, render the panel immediately after `TrackFeatureCard` and before the existing `axis_evidence` block:

```tsx
{item.taste_mode_affinity && (
    <TasteModeAffinityPanel affinity={item.taste_mode_affinity} />
)}
```

The candidate rendering order should be:

```tsx
<TrackFeatureCard ... />
{item.taste_mode_affinity && (
    <TasteModeAffinityPanel affinity={item.taste_mode_affinity} />
)}
{item.axis_evidence && item.axis_evidence.length > 0 && (
    <ul>...</ul>
)}
```

- [ ] **Step 5: Run focused E2E test to verify GREEN**

Run:

```bash
cd apps/web
npm run test:e2e -- tests/e2e/gms-preview-taste-mode-affinity.spec.ts
```

Expected: PASS. The test should see exactly one `Taste mode` panel, label `high_energy_bright_danceable`, `mode-1`, `0.93`, `0.07`, and both tokens.

- [ ] **Step 6: Run frontend build**

Run:

```bash
cd apps/web
npm run build
```

Expected: PASS. TypeScript must accept `taste_mode_affinity` and the Vite build must complete.

- [ ] **Step 7: Commit implementation**

Run:

```bash
git add apps/web/src/types/api.ts apps/web/src/pages/GmsPreviewPage.tsx apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts
git commit -m "feat: show taste mode affinity in gms preview"
```

---

### Task 3: Final Regression and Push Preparation

**Files:**
- Review: `apps/web/src/pages/GmsPreviewPage.tsx`
- Review: `apps/web/src/types/api.ts`
- Review: `apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts`

- [ ] **Step 1: Run focused test again**

Run:

```bash
cd apps/web
npm run test:e2e -- tests/e2e/gms-preview-taste-mode-affinity.spec.ts
```

Expected: PASS.

- [ ] **Step 2: Run frontend build again**

Run:

```bash
cd apps/web
npm run build
```

Expected: PASS.

- [ ] **Step 3: Run diff whitespace check**

Run:

```bash
git diff --check
```

Expected: no output and exit code 0.

- [ ] **Step 4: Confirm unrelated untracked files remain untouched**

Run:

```bash
git status --short --branch
```

Expected: implementation commits are on `main`; existing untracked `.idea/`, `.superpowers/`, and `hud-theme/` may remain untracked and must not be staged.

- [ ] **Step 5: Handoff for merge/push**

If implementation was done in a feature worktree, use `superpowers:finishing-a-development-branch` to merge back to `main`, verify, clean up the worktree, and push only after verification succeeds.
