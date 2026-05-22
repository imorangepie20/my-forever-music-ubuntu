# Taste Mode Rollout Summary UI Design

Date: `2026-05-22`

## Context

The API now exposes `GET /api/v1/recommendations/admin/audit-log/taste-mode-summary` for operator-level taste mode rollout inspection. The existing `/recommendations/feature-coverage` admin page already has the right boundary: admin-only access, target user lookup, refresh controls, and recommendation data-readiness panels.

## Goal

Add a compact `Taste Mode Rollout` panel to the Feature Coverage admin page so an operator can see whether taste mode ranking boost is active without manually calling curl or parsing audit JSON.

## Scope

In scope:

- Add frontend response types for the taste mode summary API.
- Add `fetchTasteModeRolloutSummaryForAdmin` in the shared API client.
- Load the summary from `FeatureCoverageAdminPage` alongside feature coverage for the same target user.
- Render a focused panel with rollout recommendation, boost/rank-change counts, gate totals, parse errors, latest summary time, and top reason counts.
- If the taste mode summary API fails, keep feature coverage visible and show only the rollout panel error.

Out of scope:

- New route or navigation item.
- Backend API changes.
- Changing taste mode boost thresholds or rollout behavior.
- Large dashboard redesign.

## UI Placement

The panel should appear after the top feature coverage stat cards and before the detailed source/completion tables. This keeps it close to model-readiness metrics but avoids crowding the existing header controls.

## Data Flow

1. Admin opens `/recommendations/feature-coverage`.
2. Page loads feature coverage and taste mode summary with the same `session.userId` and optional `targetInput`.
3. Feature coverage loading and error behavior remain unchanged.
4. Taste mode summary keeps its own state so a rollout API failure does not hide the rest of the page.
5. Refresh and target lookup trigger both requests.

## Display Contract

Primary fields:

- `recommendation`
- `boost_applied_total`
- `rank_changed_total`
- `eligible_total`
- `blocked_total`
- `dry_run_total`
- `parse_error_count`
- `latest_summary.created_at`

Secondary table:

- Top `reason_counts`, sorted by count descending, capped to a small visible list.

## Error Handling

- API unavailable or server not restarted: show a small amber/rose status inside the rollout panel.
- Empty/no summary: show `no_taste_mode_data` with zero counts.
- Feature coverage API failure: preserve the existing page-level error behavior.

## Testing

Use frontend tests around the page/component path:

- Summary panel renders boost/rank-change totals from API response.
- Summary panel renders a local error while feature coverage content can still render.
- Target lookup calls the summary API with the same target user id.

## Acceptance Criteria

- An admin can inspect taste mode rollout status in the browser from `/recommendations/feature-coverage`.
- The page still works if only feature coverage succeeds.
- No unrelated admin pages or navigation structure change.
