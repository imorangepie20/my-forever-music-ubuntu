# Taste Mode Rollout Summary API Design

Date: 2026-05-22

## Purpose

Operators need to check whether the GMS taste-mode affinity rollout is active without manually parsing `taste_mode_gate_summary` JSON from recent audit log rows.

## Design

Add `GET /api/v1/recommendations/admin/audit-log/taste-mode-summary` beside the existing audit log admin endpoint.

Parameters:

- `user_id`: admin user id.
- `target_user_id`: observed user id. Defaults to `user_id`.
- `limit`: recent audit rows to inspect. Defaults to `50`, max remains owned by the existing admin service.

The API reuses existing admin authorization, reads recent audit entries, parses `taste_mode_gate_summary`, and returns totals:

- analyzed entry counts and parse error count
- boost enabled count
- evaluated, eligible, dry-run, blocked, not-applicable totals
- boost applied and rank changed totals
- max positive and negative deltas
- merged reason counts
- latest parsed summary
- recommendation string: `boost_active`, `dry_run_only`, `blocked_by_confidence`, or `no_taste_mode_data`

## Non-Goals

- No new database schema.
- No frontend page.
- No SQL JSON aggregation.

## Testing

Unit tests cover aggregation and invalid JSON tolerance. Controller test covers the new route shape.
