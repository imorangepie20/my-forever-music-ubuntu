# Taste Mode Affinity Ranking Boost Design

Date: 2026-05-22

## 1. Purpose

The current GMS preview flow exposes `taste_mode_affinity` and `taste_mode_gate` as operational inspection signals for heavy audio taste profiles. Operators can see whether a candidate is close to a user's nearest taste mode and whether it passes a conservative gate, but the gate does not affect ranking yet.

This step promotes gated taste mode affinity into a small optional ranking signal. The goal is to let clearly eligible heavy-profile candidates move slightly in GMS preview order while keeping the default runtime behavior safe and reversible.

## 2. Decision

Use the conservative promotion path.

- `app.recommendation.taste-mode-affinity.apply-ranking-boost=false` remains the default.
- When the flag is false, GMS preview ranking, score, order, context, and audit model version remain unchanged from the current dry-run behavior.
- When the flag is true, only candidates whose gate result is `dry_run` or `eligible` receive the taste mode boost.
- Blocked and not-applicable candidates keep their existing score.
- The boost is applied after the existing `audio-taste:v1` ranking stage and before final item projection.
- The same scoring formula used by dry-run becomes the actual score adjustment formula.

## 3. Non-Goals

- No new machine learning model artifact.
- No offline promotion pipeline in this step.
- No frontend route or layout redesign.
- No change to the existing `audio-taste:v1` centroid boost formula.
- No boost for non-heavy profiles.
- No hidden fallback that bypasses the gate.

## 4. Ranking Formula

For an eligible candidate:

```text
centered_affinity = similarity - 0.5
next_score = current_score * (1 + suggested_boost_weight * centered_affinity)
```

The score is rounded through the existing GMS score clamp path and remains inside the normal preview score range.

`suggested_boost_weight` continues to come from `TasteModeAffinityGateService`:

```text
suggested_boost_weight = clamp(profile_confidence, 0.0, 1.0) * max_boost_weight
```

With the current default `max_boost_weight=0.03`, this keeps taste mode affinity weaker than the existing broader `audio-taste:v1` boost.

## 5. Data Flow

1. GMS preview builds baseline PMS candidate scores.
2. SASRec ranking runs if available.
3. `audio-taste:v1` ranking runs if the profile is applicable.
4. Heavy profile taste modes are evaluated for each candidate.
5. `TasteModeAffinityGateService` evaluates the nearest mode affinity against profile confidence, similarity, distance, token strength, and candidate feature usability.
6. If `apply-ranking-boost=false`, the gate output stays inspection-only.
7. If `apply-ranking-boost=true`, eligible candidates receive the calculated boost.
8. Candidates are sorted again using the existing stable GMS tie-breakers.
9. The response includes the existing `taste_mode_gate` item field when explanations are requested.
10. The preview warning, context engine, and audit summary record whether the ranking boost was actually applied.

## 6. API And Audit Contract

When the ranking boost applies to at least one candidate:

- `context.engine` appends `+taste-mode-affinity:v1`.
- `warnings[]` includes applied count, blocked count, max positive delta, max negative delta, and `ranking_impact=enabled`.
- `recommendation_audit_log.taste_mode_gate_summary` includes:
  - `apply_ranking_boost=true`
  - `boost_applied_count`
  - `rank_changed_count`
  - `max_positive_delta`
  - `max_negative_delta`
  - existing evaluated, eligible, dry-run, blocked, and reason count totals

When the flag is false:

- `context.engine` does not include `taste-mode-affinity:v1`.
- Existing dry-run warnings keep `ranking_impact=none`.
- Audit continues to store the dry-run summary, with `boost_applied_count=0` and `rank_changed_count=0` if those fields are present.

## 7. Feature Flags

| Property | Default | Meaning |
| --- | --- | --- |
| `app.recommendation.taste-mode-affinity.gate.enabled` | `true` | Evaluate the gate |
| `app.recommendation.taste-mode-affinity.dry-run-enabled` | `true` | Compute hypothetical score delta |
| `app.recommendation.taste-mode-affinity.apply-ranking-boost` | `false` | Apply eligible gate score delta to ranking |
| `app.recommendation.taste-mode-affinity.min-profile-confidence` | `0.55` | Heavy profile confidence threshold |
| `app.recommendation.taste-mode-affinity.min-similarity` | `0.82` | Nearest mode similarity threshold |
| `app.recommendation.taste-mode-affinity.max-distance` | `0.18` | Nearest mode distance threshold |
| `app.recommendation.taste-mode-affinity.max-boost-weight` | `0.03` | Maximum taste mode affinity boost weight |

The default keeps production behavior dry-run only. Operators must explicitly set `apply-ranking-boost=true` to enable ranking impact.

## 8. Error Handling

- If the profile is missing, not heavy, or has no taste modes, no boost is applied.
- If candidate audio features are missing or unusable, no boost is applied.
- If the current score is not finite, no boost is applied and the gate remains not-applicable or eligible without score adjustment.
- If all candidates are blocked or not-applicable, the response stays unchanged except for inspection output and audit counts.
- If the gate is disabled, no `taste_mode_gate` or boost behavior is emitted.

## 9. Testing

Backend tests must prove:

- Flag false preserves current rank, score, order, context, and warning behavior.
- Flag true changes only eligible candidate scores.
- Blocked and not-applicable candidates keep their score.
- Re-sorting uses the same stable tie-breakers as existing GMS ranking.
- `context.engine` includes `taste-mode-affinity:v1` only when at least one boost is applied.
- Warning text distinguishes `ranking_impact=enabled` from `ranking_impact=none`.
- Audit summary records boost-applied and rank-changed counts.

Frontend tests do not need a new page. The existing GMS preview affinity panel should continue rendering `taste_mode_gate`; if the backend warning includes enabled ranking impact, the existing warnings area is enough for this step.

## 10. Rollback

Rollback is operational first:

```text
app.recommendation.taste-mode-affinity.apply-ranking-boost=false
```

Turning the flag off returns the system to dry-run behavior without a database rollback. The schema already supports the audit payload and remains backward compatible.

## 11. Success Criteria

- The default deployment remains dry-run only.
- Enabling the flag produces small, explainable GMS preview score changes for gated heavy-profile candidates.
- Every applied score change is visible through warning/context/audit evidence.
- Operators can compare dry-run and enabled behavior without changing user-facing routes.
