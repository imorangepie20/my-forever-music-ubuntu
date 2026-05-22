# Taste Mode Rollout Summary UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show taste mode rollout status in the existing Feature Coverage admin page.

**Architecture:** Reuse the existing `/recommendations/feature-coverage` admin page and API client. Add frontend types and a focused fetch helper for the backend summary endpoint, then render a self-contained panel with its own loading/error state so feature coverage remains usable if the rollout summary API fails.

**Tech Stack:** React 18, TypeScript, Vite, Playwright e2e, existing HUD Tailwind classes, lucide-react icons.

---

### Task 1: Add RED Playwright Coverage

**Files:**
- Create: `apps/web/tests/e2e/feature-coverage-taste-mode-summary.spec.ts`

- [ ] **Step 1: Write the failing e2e test**

Create `apps/web/tests/e2e/feature-coverage-taste-mode-summary.spec.ts`:

```ts
import { expect, test, type Route } from '@playwright/test'

const adminSession = {
    userId: 'admin-user',
    email: 'jowoosungtidal@gmail.com',
    displayName: 'Admin',
    preferredPlatformId: 'tidal',
    onboardingStage: 'ready',
    registeredAt: '2026-05-22T00:00:00Z',
    platformConnectionRequired: false,
    nextStepPath: '/recommendations/feature-coverage',
    nextStepMessage: 'Ready.',
}

const featureCoverageResponse = {
    service: 'api',
    status: 'ok',
    generated_at: '2026-05-22T00:00:00Z',
    target_user_id: 'target-user',
    pms_library: {
        playlist_count: 4,
        track_count: 100,
        audio_feature_filled_count: 80,
        audio_feature_coverage_ratio: 0.8,
        stale_audio_feature_count: 2,
        stale_audio_feature_ratio: 0.025,
        latest_audio_resolved_at: '2026-05-22T00:00:00Z',
        isrc_count: 70,
        isrc_coverage_ratio: 0.7,
        playback_target_available_count: 90,
        playback_target_coverage_ratio: 0.9,
        audio_feature_source_classes: [],
    },
    ems_pool: {
        track_count: 120,
        audio_feature_filled_count: 60,
        audio_feature_coverage_ratio: 0.5,
        stale_audio_feature_count: 4,
        stale_audio_feature_ratio: 0.0667,
        latest_audio_resolved_at: '2026-05-22T00:00:00Z',
        isrc_count: 55,
        isrc_coverage_ratio: 0.4583,
        canonical_track_count: 44,
        canonical_track_coverage_ratio: 0.3667,
        audio_feature_source_classes: [],
        sources: [],
        warnings: [],
    },
    ems_acquisition: {
        recent_run_count: 3,
        article_count: 20,
        skipped_article_count: 2,
        seed_count: 12,
        skipped_seed_count: 1,
        checked_item_count: 40,
        skipped_item_count: 4,
        skipped_item_ratio: 0.1,
        warnings: [],
    },
    audio_feature_completion: {
        recent_job_count: 10,
        status_counts: [],
        top_reasons: [],
        warnings: [],
    },
    learning_data: {
        event_count: 30,
        recent_recommendation_snapshot_count: 6,
        recent_recommendation_snapshot_limit: 100,
    },
    warnings: [],
    drift_signals: [],
}

const tasteModeSummaryResponse = {
    service: 'api',
    status: 'ok',
    generated_at: '2026-05-22T00:01:00Z',
    summary: {
        entries_analyzed: 50,
        entries_with_summary: 12,
        parse_error_count: 1,
        boost_enabled_count: 4,
        evaluated_total: 120,
        eligible_total: 70,
        dry_run_total: 20,
        blocked_total: 30,
        not_applicable_total: 0,
        boost_applied_total: 40,
        rank_changed_total: 12,
        max_positive_delta: 0.0129,
        max_negative_delta: 0,
        reason_counts: {
            eligible: 70,
            low_mode_similarity: 30,
        },
        latest_summary: {
            audit_log_id: 1001,
            model_version: 'rule-based-preview-v1+audio-taste:v1+taste-mode-affinity:v1',
            created_at: '2026-05-22T00:01:00Z',
            taste_mode_gate_summary: '{"boost_applied_count":7}',
        },
        recommendation: 'boost_active',
    },
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
    }, adminSession)
})

test('feature coverage admin renders taste mode rollout summary', async ({ page }) => {
    await page.route('**/api/v1/recommendations/admin/feature-coverage**', (route) =>
        fulfillJson(route, featureCoverageResponse),
    )
    await page.route('**/api/v1/recommendations/admin/audit-log/taste-mode-summary**', (route) =>
        fulfillJson(route, tasteModeSummaryResponse),
    )

    await page.goto('/recommendations/feature-coverage')

    await expect(page.getByRole('heading', { name: 'Taste Mode Rollout' })).toBeVisible()
    await expect(page.getByText('boost_active')).toBeVisible()
    await expect(page.getByText('Boost Applied')).toBeVisible()
    await expect(page.getByText('40')).toBeVisible()
    await expect(page.getByText('Rank Changed')).toBeVisible()
    await expect(page.getByText('12')).toBeVisible()
    await expect(page.getByText('eligible')).toBeVisible()
    await expect(page.getByText('low_mode_similarity')).toBeVisible()
})

test('feature coverage remains visible when taste mode summary fails', async ({ page }) => {
    await page.route('**/api/v1/recommendations/admin/feature-coverage**', (route) =>
        fulfillJson(route, featureCoverageResponse),
    )
    await page.route('**/api/v1/recommendations/admin/audit-log/taste-mode-summary**', (route) =>
        route.fulfill({ status: 503, contentType: 'application/json', body: '{"message":"unavailable"}' }),
    )

    await page.goto('/recommendations/feature-coverage')

    await expect(page.getByText('추천 데이터 준비도')).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Taste Mode Rollout' })).toBeVisible()
    await expect(page.getByText('Taste mode rollout summary를 불러오지 못했습니다.')).toBeVisible()
})
```

- [ ] **Step 2: Run the new test to verify RED**

Run:

```bash
cd apps/web
npm run test:e2e -- tests/e2e/feature-coverage-taste-mode-summary.spec.ts
```

Expected: FAIL because the summary API client and UI panel do not exist yet.

---

### Task 2: Add Types And API Client

**Files:**
- Modify: `apps/web/src/types/api.ts`
- Modify: `apps/web/src/services/api.ts`

- [ ] **Step 1: Add response types**

Add after `RecommendationAuditLogRecentResponse` in `apps/web/src/types/api.ts`:

```ts
export interface RecommendationTasteModeLatestSummary {
    audit_log_id: number | null
    model_version: string | null
    created_at: string | null
    taste_mode_gate_summary: string
}

export interface RecommendationTasteModeSummary {
    entries_analyzed: number
    entries_with_summary: number
    parse_error_count: number
    boost_enabled_count: number
    evaluated_total: number
    eligible_total: number
    dry_run_total: number
    blocked_total: number
    not_applicable_total: number
    boost_applied_total: number
    rank_changed_total: number
    max_positive_delta: number | null
    max_negative_delta: number | null
    reason_counts: Record<string, number>
    latest_summary: RecommendationTasteModeLatestSummary | null
    recommendation: string
}

export interface RecommendationTasteModeSummaryResponse {
    service: string
    status: string
    generated_at: string
    summary: RecommendationTasteModeSummary
}
```

- [ ] **Step 2: Import and add fetch helper**

In `apps/web/src/services/api.ts`, include `RecommendationTasteModeSummaryResponse` in the type import list and add this helper near `fetchRecentRecommendationAuditLogForAdmin`:

```ts
export const fetchTasteModeRolloutSummaryForAdmin = (
    userId: string,
    targetUserId?: string,
    limit = 50,
    signal?: AbortSignal,
) => {
    const params = new URLSearchParams({ user_id: userId, limit: String(limit) })
    if (targetUserId?.trim()) {
        params.set('target_user_id', targetUserId.trim())
    }
    return requestJson<RecommendationTasteModeSummaryResponse>(
        `/api/v1/recommendations/admin/audit-log/taste-mode-summary?${params.toString()}`,
        { signal, cache: 'no-store' },
    )
}
```

---

### Task 3: Render The Rollout Panel

**Files:**
- Modify: `apps/web/src/pages/FeatureCoverageAdminPage.tsx`

- [ ] **Step 1: Import API helper and types**

Update imports:

```ts
import { fetchFeatureCoverageForAdmin, fetchTasteModeRolloutSummaryForAdmin } from '@/services/api'
import type {
    FeatureCoverageAdminResponse,
    FeatureCoverageAudioFeatureCompletion,
    FeatureCoverageAudioFeatureSourceClass,
    FeatureCoverageSummary,
    RecommendationTasteModeSummaryResponse,
} from '@/types/api'
```

- [ ] **Step 2: Add summary state and loader**

Inside `FeatureCoverageAdminPage`, add:

```ts
const [tasteModeSummary, setTasteModeSummary] = useState<RecommendationTasteModeSummaryResponse | null>(null)
const [tasteModeError, setTasteModeError] = useState<string | null>(null)
```

Replace the `load` try block with separate fetches:

```ts
try {
    const trimmedTarget = targetUserId?.trim() || undefined
    const response = await fetchFeatureCoverageForAdmin(session.userId, trimmedTarget, signal)
    setReport(response)

    try {
        const summary = await fetchTasteModeRolloutSummaryForAdmin(session.userId, trimmedTarget, 50, signal)
        setTasteModeSummary(summary)
        setTasteModeError(null)
    } catch (summaryErr) {
        if (signal?.aborted) {
            return
        }
        setTasteModeSummary(null)
        setTasteModeError(summaryErr instanceof Error ? summaryErr.message : 'Taste mode rollout summary를 불러오지 못했습니다.')
    }
} catch (err) {
```

- [ ] **Step 3: Render panel between stat cards and detail panels**

After the totals stat section, add:

```tsx
<TasteModeRolloutPanel
    summary={tasteModeSummary}
    error={tasteModeError}
/>
```

- [ ] **Step 4: Add panel component**

Add below `CoveragePanel`:

```tsx
const TasteModeRolloutPanel = ({
    summary,
    error,
}: {
    summary: RecommendationTasteModeSummaryResponse | null
    error: string | null
}) => {
    const data = summary?.summary
    const reasons = Object.entries(data?.reason_counts ?? {})
        .sort(([, left], [, right]) => right - left)
        .slice(0, 5)

    return (
        <section className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-5">
            <div className="flex flex-col gap-4 xl:flex-row xl:items-start xl:justify-between">
                <div>
                    <div className="flex items-center gap-3 text-hud-accent-primary">
                        <BrainCircuit size={20} />
                        <h3 className="text-sm font-semibold uppercase tracking-[0.2em]">Taste Mode Rollout</h3>
                    </div>
                    <p className="mt-2 text-xs text-hud-text-muted">
                        Latest summary {formatDateTime(data?.latest_summary?.created_at)}
                    </p>
                </div>
                <span className="w-fit rounded-full border border-hud-border-secondary bg-hud-bg-primary/70 px-3 py-1 text-xs font-semibold text-hud-text-primary">
                    {data?.recommendation ?? 'unavailable'}
                </span>
            </div>

            {error && (
                <div className="mt-4 flex items-start gap-3 rounded-xl border border-amber-300/30 bg-amber-300/10 p-3 text-sm text-amber-100">
                    <AlertTriangle size={16} className="mt-0.5 shrink-0" />
                    <span>Taste mode rollout summary를 불러오지 못했습니다.</span>
                </div>
            )}

            <div className="mt-5 grid gap-3 sm:grid-cols-2 xl:grid-cols-6">
                <RolloutMetric label="Boost Applied" value={data?.boost_applied_total} />
                <RolloutMetric label="Rank Changed" value={data?.rank_changed_total} />
                <RolloutMetric label="Eligible" value={data?.eligible_total} />
                <RolloutMetric label="Blocked" value={data?.blocked_total} />
                <RolloutMetric label="Dry Run" value={data?.dry_run_total} />
                <RolloutMetric label="Parse Errors" value={data?.parse_error_count} />
            </div>

            <div className="mt-5 grid gap-5 xl:grid-cols-[0.8fr_1.2fr]">
                <dl className="divide-y divide-hud-border-secondary rounded-xl border border-hud-border-secondary bg-hud-bg-primary/50 text-sm">
                    <RolloutRow label="Entries Analyzed" value={formatCount(data?.entries_analyzed)} />
                    <RolloutRow label="Entries With Summary" value={formatCount(data?.entries_with_summary)} />
                    <RolloutRow label="Boost Enabled" value={formatCount(data?.boost_enabled_count)} />
                    <RolloutRow label="Max Positive Delta" value={formatDecimal(data?.max_positive_delta)} />
                </dl>

                <div className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/50 p-4">
                    <p className="text-xs font-semibold uppercase tracking-[0.18em] text-hud-text-muted">Gate Reasons</p>
                    <div className="mt-3 space-y-2">
                        {reasons.map(([reason, count]) => (
                            <div key={reason} className="flex items-center justify-between gap-4 text-sm">
                                <span className="break-all font-medium text-hud-text-primary">{reason}</span>
                                <span className="text-hud-text-secondary">{formatCount(count)}</span>
                            </div>
                        ))}
                        {!reasons.length && (
                            <p className="text-sm text-hud-text-muted">Gate reason summary가 없습니다.</p>
                        )}
                    </div>
                </div>
            </div>
        </section>
    )
}

const RolloutMetric = ({ label, value }: { label: string; value: number | null | undefined }) => (
    <div className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/60 p-3">
        <p className="text-[11px] uppercase tracking-[0.16em] text-hud-text-muted">{label}</p>
        <p className="mt-2 text-xl font-semibold text-hud-text-primary">{formatCount(value)}</p>
    </div>
)

const RolloutRow = ({ label, value }: { label: string; value: string }) => (
    <div className="flex items-center justify-between gap-4 px-4 py-3">
        <dt className="text-hud-text-muted">{label}</dt>
        <dd className="text-right font-medium text-hud-text-primary">{value}</dd>
    </div>
)
```

- [ ] **Step 5: Add decimal formatter**

Near `formatPercent`, add:

```ts
const formatDecimal = (value: number | null | undefined) => {
    if (value === null || value === undefined || Number.isNaN(value)) {
        return '-'
    }
    return value.toFixed(4)
}
```

---

### Task 4: Verify, Commit, Push

**Files:**
- All files from Tasks 1-3.

- [ ] **Step 1: Run RED/GREEN e2e target**

Run:

```bash
cd apps/web
npm run test:e2e -- tests/e2e/feature-coverage-taste-mode-summary.spec.ts
```

Expected: PASS after implementation.

- [ ] **Step 2: Run TypeScript build**

Run:

```bash
cd apps/web
npm run build
```

Expected: PASS.

- [ ] **Step 3: Commit only related files**

Run:

```bash
git add \
  apps/web/src/pages/FeatureCoverageAdminPage.tsx \
  apps/web/src/services/api.ts \
  apps/web/src/types/api.ts \
  apps/web/tests/e2e/feature-coverage-taste-mode-summary.spec.ts \
  docs/superpowers/plans/2026-05-22-taste-mode-rollout-summary-ui.md
git commit -m "feat: show taste mode rollout summary in admin"
```

- [ ] **Step 4: Push**

Run:

```bash
git push
```
