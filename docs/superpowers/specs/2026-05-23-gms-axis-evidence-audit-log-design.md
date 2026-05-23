# GMS Axis Evidence Audit Log Design

## Goal

Persist GMS recommendation `axis_evidence` on the server so operator-only diagnostics do not depend on browser local storage.

## Selected Approach

Use the existing `recommendation_audit_log` table and add a nullable `axis_evidence_summary` text column containing compact JSON. This matches the existing `taste_mode_gate_summary` pattern and keeps the implementation small.

## Data Flow

1. GMS preview generation returns recommendation items with `axis_evidence`.
2. The preview service serializes a compact axis evidence snapshot into JSON.
3. `RecommendationAuditLogStore.AuditDraft` carries that JSON as `axisEvidenceSummary`.
4. `recommendation_audit_log.axis_evidence_summary` stores the JSON with the preview audit row.
5. Admin audit-log APIs expose the raw summary only through existing admin-checked endpoints.
6. The web `PlaylistQualityAdminPage` reads the latest audit-log entry with `axis_evidence_summary` and renders it under an `운영자 전용` section.

## JSON Shape

For track recommendations:

```json
{
  "source": "gms-preview",
  "items": [
    {
      "rank": 1,
      "track_id": "track-123",
      "title": "Song",
      "artist_name": "Artist",
      "source_platform": "tidal",
      "score": 0.84,
      "axis_evidence": [
        {
          "axis": "affinity",
          "score": 0.55,
          "level": "moderate",
          "summary": "사용자 취향 신호와 부분적으로 겹치는 후보입니다."
        }
      ]
    }
  ]
}
```

For playlist recommendations:

```json
{
  "source": "gms-playlists",
  "playlists": [
    {
      "rank": 1,
      "playlist_id": 101,
      "title": "Playlist",
      "source_platform": "spotify",
      "track_count": 50,
      "composite_score": 0.91,
      "affinity_score": 0.88,
      "confidence_score": 0.86,
      "axis_evidence": [
        {
          "axis": "affinity",
          "score": 0.88,
          "level": "strong",
          "summary": "Matches user library genre anchors."
        }
      ]
    }
  ]
}
```

## Scope

- Add a Flyway migration for `recommendation_audit_log.axis_evidence_summary`.
- Extend `RecommendationAuditLogStore`, JPA entity mapping, in-memory store, and admin DTOs.
- Store track preview axis evidence from `GmsRecommendationPreviewService`.
- Store playlist preview axis evidence when a playlist-preview audit row is available. If playlist preview currently has no audit row, add the minimal audit write needed for this diagnostic.
- Update web admin types and `PlaylistQualityAdminPage` to prefer server audit evidence over local storage.
- Keep user-facing GMS cards free of raw axis evidence.

## Non-Goals

- Do not create a normalized axis evidence table in this step.
- Do not expose raw axis evidence on user-facing pages.
- Do not change recommendation scoring or ranking.
- Do not store full request/response payloads.

## Testing

- Backend WebMvc test: admin audit-log recent response includes `axis_evidence_summary`.
- Backend service test: GMS preview audit row stores compact axis evidence JSON.
- Frontend E2E/unit coverage: GMS cards hide raw summaries, while admin quality page renders `운영자 전용` raw evidence from server audit data.
- Build verification: `./gradlew test` for API scope and `npm run build` for web scope.
