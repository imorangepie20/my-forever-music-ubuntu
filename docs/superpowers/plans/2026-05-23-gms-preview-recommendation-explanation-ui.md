# GMS Preview Recommendation Explanation UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a compact `Why this recommendation` block to each `/gms-preview` track candidate.

**Architecture:** Reuse the existing `GmsPreviewPage` response fields and card layout. Add a presentational `RecommendationExplanationPanel` beside the existing `TasteModeAffinityPanel`, deriving verdicts and signal rows from `score`, `taste_mode_affinity`, `taste_mode_gate`, and `axis_evidence` without backend changes.

**Tech Stack:** React 18, TypeScript, Vite, Playwright e2e, existing HUD Tailwind classes.

---

### Task 1: RED Playwright Coverage

**Files:**
- Modify: `apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts`

- [x] **Step 1: Extend the existing fixture and expectations**

In `gmsPreviewResponse.items[2]`, add one axis evidence item so the no-affinity fallback path is covered:

```ts
axis_evidence: [
    {
        axis: 'confidence',
        score: 0.68,
        level: 'moderate',
        summary: 'Moderate confidence from broader GMS signals.',
    },
],
```

In the test body, after `await expect(page.getByText('Velvet Voltage')).toBeVisible()`, add:

```ts
await expect(page.getByRole('heading', { name: 'Why this recommendation' })).toHaveCount(3)
const strongExplanation = page.getByLabel('Recommendation explanation track-affinity-001')
await expect(strongExplanation.getByText('Fits your active taste mode')).toBeVisible()
await expect(strongExplanation.getByText('Mode similarity')).toBeVisible()
await expect(strongExplanation.getByText('0.93')).toBeVisible()
await expect(strongExplanation.getByText('Gate')).toBeVisible()
await expect(strongExplanation.getByText('dry_run · eligible')).toBeVisible()
await expect(strongExplanation.getByText('Rank delta')).toBeVisible()
await expect(strongExplanation.getByText('+0.0084')).toBeVisible()
await expect(strongExplanation.getByText('Audio taste matched this candidate.')).toBeVisible()

const broaderExplanation = page.getByLabel('Recommendation explanation track-affinity-003')
await expect(broaderExplanation.getByText('Recommended from broader listening signals')).toBeVisible()
await expect(broaderExplanation.getByText('Moderate confidence from broader GMS signals.')).toBeVisible()
```

- [x] **Step 2: Run the target e2e test and verify RED**

Run:

```bash
cd apps/web
npm run test:e2e -- tests/e2e/gms-preview-taste-mode-affinity.spec.ts
```

Expected: FAIL because `Why this recommendation` does not exist yet.

---

### Task 2: Add Explanation Derivation And Panel

**Files:**
- Modify: `apps/web/src/pages/GmsPreviewPage.tsx`

- [x] **Step 1: Add type aliases and helpers**

Near `TasteModeAffinityPanelProps`, add:

```ts
type GmsPreviewItem = GmsRecommendationPreviewResponse['items'][number]

const evidenceRank = (level: string) => {
    switch (level) {
        case 'strong':
            return 0
        case 'moderate':
            return 1
        case 'low':
            return 2
        default:
            return 3
    }
}

const topEvidence = (items: GmsPreviewItem['axis_evidence']) =>
    [...(items ?? [])]
        .sort((left, right) => evidenceRank(left.level) - evidenceRank(right.level))
        .slice(0, 3)

const explanationVerdict = (item: GmsPreviewItem) => {
    const gateStatus = item.taste_mode_gate?.status
    if (gateStatus === 'dry_run' || gateStatus === 'eligible') {
        return 'Fits your active taste mode'
    }
    if (gateStatus === 'blocked') {
        return 'Close, but held back by the gate'
    }
    if (item.taste_mode_affinity) {
        return 'Similar to one of your listening modes'
    }
    if ((item.axis_evidence?.length ?? 0) > 0) {
        return 'Recommended from broader listening signals'
    }
    return 'Recommended from the current GMS ranking'
}
```

- [x] **Step 2: Add the panel component**

Add below `TasteModeAffinityPanel`:

```tsx
const RecommendationExplanationPanel = ({ item }: { item: GmsPreviewItem }) => {
    const evidence = topEvidence(item.axis_evidence)
    const tokens = [
        ...affinityTokens(item.taste_mode_affinity?.tokens),
        ...gateReasonTokens(item.taste_mode_gate?.reason_tokens),
    ].slice(0, 4)

    return (
        <section
            aria-label={`Recommendation explanation ${item.track_id}`}
            className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/60 p-3"
        >
            <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                    <h4 className="text-xs font-semibold uppercase tracking-[0.18em] text-hud-text-muted">
                        Why this recommendation
                    </h4>
                    <p className="mt-2 text-sm font-semibold text-hud-text-primary">
                        {explanationVerdict(item)}
                    </p>
                </div>
                <span className="rounded-full border border-hud-border-secondary bg-hud-bg-secondary/70 px-2.5 py-1 text-[11px] font-semibold text-hud-accent-primary">
                    Score {item.score.toFixed(2)}
                </span>
            </div>

            <dl className="mt-3 grid gap-2 text-xs sm:grid-cols-2">
                {item.taste_mode_affinity && (
                    <ExplanationSignal label="Mode similarity" value={formatAffinityMetric(item.taste_mode_affinity.similarity)} />
                )}
                {item.taste_mode_gate && (
                    <ExplanationSignal label="Gate" value={`${item.taste_mode_gate.status} · ${item.taste_mode_gate.reason}`} />
                )}
                {typeof item.taste_mode_gate?.dry_run_delta === 'number' && (
                    <ExplanationSignal label="Rank delta" value={formatGateDelta(item.taste_mode_gate.dry_run_delta)} />
                )}
                <ExplanationSignal label="Source" value={item.source_playlist_title ?? item.source_space} />
            </dl>

            {item.reason && (
                <p className="mt-3 text-xs leading-5 text-hud-text-secondary">{item.reason}</p>
            )}

            {evidence.length > 0 && (
                <ul className="mt-3 space-y-1.5">
                    {evidence.map((entry) => (
                        <li key={`${item.track_id}-explain-${entry.axis}`} className="text-xs leading-5 text-hud-text-secondary">
                            <span className="font-semibold capitalize text-hud-text-primary">{entry.axis}</span>
                            {entry.score !== null ? ` ${entry.score.toFixed(2)}` : ''}: {entry.summary}
                        </li>
                    ))}
                </ul>
            )}

            {tokens.length > 0 && (
                <div className="mt-3 flex flex-wrap gap-1.5">
                    {tokens.map((token) => (
                        <span
                            key={`${item.track_id}-explain-token-${token}`}
                            className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/60 px-2 py-0.5 text-[10px] text-hud-text-muted"
                        >
                            {token}
                        </span>
                    ))}
                </div>
            )}
        </section>
    )
}

const ExplanationSignal = ({ label, value }: { label: string; value: string }) => (
    <div className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/50 px-2.5 py-2">
        <dt className="text-[10px] uppercase tracking-[0.16em] text-hud-text-muted">{label}</dt>
        <dd className="mt-1 font-medium text-hud-text-primary">{value}</dd>
    </div>
)
```

- [x] **Step 3: Render the panel**

Inside the `response.items.map` card body, immediately after `TrackFeatureCard`, add:

```tsx
<RecommendationExplanationPanel item={item} />
```

---

### Task 3: Verify, Commit, Push

**Files:**
- Modify: `apps/web/src/pages/GmsPreviewPage.tsx`
- Modify: `apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts`
- Modify: `docs/superpowers/plans/2026-05-23-gms-preview-recommendation-explanation-ui.md`

- [x] **Step 1: Run target e2e**

Run:

```bash
cd apps/web
npm run test:e2e -- tests/e2e/gms-preview-taste-mode-affinity.spec.ts
```

Expected: PASS.

- [x] **Step 2: Run web build**

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
  apps/web/src/pages/GmsPreviewPage.tsx \
  apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts \
  docs/superpowers/plans/2026-05-23-gms-preview-recommendation-explanation-ui.md
git commit -m "feat: explain gms preview recommendations"
```

- [ ] **Step 4: Push**

Run:

```bash
git push
```
