# GMS Preview Recommendation Explanation UI Design

Date: `2026-05-23`

## Context

GMS preview already returns useful explanation data per track:

- `reason`
- `score`
- `taste_mode_affinity`
- `taste_mode_gate`
- `axis_evidence`

The current UI renders these signals, but the user still has to mentally connect raw score, taste mode similarity, gate status, and 6-axis evidence. The next step is to make each recommendation explain itself in a compact, readable way.

## Goal

Add a `Why this recommendation` explanation block to each `/gms-preview` candidate card so the user can understand why a track was recommended without reading raw operational tokens first.

## Scope

In scope:

- Add a focused explanation block under each GMS preview track card.
- Summarize the candidate in one short verdict line.
- Show key signals:
  - recommendation score
  - taste mode similarity when available
  - taste mode gate status/reason when available
  - dry-run or boost delta when available
  - strongest 6-axis evidence items
- Preserve the existing raw taste mode panel for deeper inspection in the same card.
- Keep the UI responsive and compact for the existing 2/3-column candidate grid.

Out of scope:

- Backend API changes.
- New recommendation model behavior.
- GMS playlist page changes.
- Full dashboard redesign.

## Placement

Inside `GmsPreviewPage`, each candidate currently renders:

1. `TrackFeatureCard`
2. optional `TasteModeAffinityPanel`
3. optional `axis_evidence` list

The new block should sit directly after `TrackFeatureCard` and before the lower-level taste mode/axis evidence details. This makes the explanation the first thing a user sees after the track identity.

## Explanation Rules

The block should derive human-readable text from existing response fields only.

Verdict examples:

- Gate `dry_run` or `eligible`: `Fits your active taste mode`
- Gate `blocked`: `Close, but held back by the gate`
- Affinity exists without gate: `Similar to one of your listening modes`
- No affinity but axis evidence exists: `Recommended from broader listening signals`
- No explanation signals: `Recommended from the current GMS ranking`

Signal rows:

- `Score`: `item.score.toFixed(2)`
- `Mode similarity`: `taste_mode_affinity.similarity.toFixed(2)` when available
- `Gate`: `taste_mode_gate.status` + `taste_mode_gate.reason` when available
- `Rank delta`: `taste_mode_gate.dry_run_delta` when available
- `Top evidence`: first 2-3 `axis_evidence` summaries, prioritizing `strong`, then `moderate`, then other levels

Evidence chips:

- Show up to 4 combined taste mode/gate tokens.
- Keep raw token chips visually secondary.
- Do not show a long wall of tokens.

## Error Handling

No new network request is introduced, so no new request error path is needed.

If fields are missing:

- Hide the missing row.
- Keep the verdict fallback.
- Do not render `undefined`, `null`, or `NaN`.

## Testing

Add/extend Playwright coverage for `/gms-preview`:

- A candidate with taste mode affinity and dry-run gate renders `Why this recommendation`, mode similarity, gate reason, and rank delta.
- A candidate without taste mode affinity but with axis evidence still renders a broader-signal verdict.
- Existing taste mode detail panel remains visible.

## Acceptance Criteria

- `/gms-preview` users can understand the reason behind a candidate before inspecting raw mode/gate details.
- The explanation uses only existing API fields.
- Existing feedback, save, playback, taste mode panel, and axis evidence behavior remain intact.
