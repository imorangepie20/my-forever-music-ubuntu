# EMS Playback Audio Taste Input Design

Date: 2026-05-22

## Context

`/gms-playlists` playback now records user music events correctly. A real TIDAL play completion was stored as:

- `event_type=play_completed`
- `event_weight=1.0`
- `source_space=player`
- `source_platform=tidal`
- `track_id=ems-track:318283`

The event flows into the general personalization profile, but `AudioTasteProfileService` still reports `positive_track_count=0`. The root cause is that audio taste profile construction only collects PMS user library audio features through `collectPmsFeatures(userId)`. EMS playback events use `ems-track:*` ids and therefore cannot join to any `AudioTasteTrackFeature`.

## Goal

Make EMS playback from `/gms-playlists` usable as audio taste learning input when the EMS track has usable audio features.

The immediate success criteria are:

- A `play_completed` event for `ems-track:<id>` can contribute to `AudioTasteProfileService.positiveTrackCount`.
- EMS feature rows are represented with `trackScope=ems_collected_track`.
- Existing PMS behavior remains unchanged.
- GMS preview can later evaluate taste mode affinity from real listening events instead of only imported PMS library tracks.

## Non-Goals

- Do not redesign the whole recommendation event model.
- Do not add a database migration.
- Do not include negative EMS events in this first change.
- Do not dilute user coverage with the full EMS pool of 180k+ tracks.
- Do not enable ranking boost automatically in this change.

## Selected Approach

Extend `AudioTasteProfileService` with an optional EMS feature source.

The service will still collect all PMS user library features. It will additionally scan the recent user events already loaded for profile recomputation, find positive EMS playback events, and fetch only those referenced EMS tracks from `EmsCollectedTrackRepository`.

Positive EMS event types are the same high-intent events already weighted positively:

- `play_completed`
- `track_saved`
- `added_to_playlist`
- `recommendation_liked`
- `replay`

The implementation should rely on `eventWeight > 0.0` as the final gate after canonical weighting, while still parsing only `ems-track:<numeric-id>` track ids for EMS lookup.

## Data Flow

1. `AudioTasteProfileService.recompute(userId, eventLimit)` loads recent events.
2. PMS features are collected as today.
3. Positive EMS event ids are extracted from the recent events:
   - accept `track_id=ems-track:<id>`
   - optionally fall back to `item_id=ems-track:<id>` if `track_id` is absent
   - ignore malformed or non-numeric ids
4. Each parsed EMS id is looked up in `EmsCollectedTrackRepository`.
5. Tracks with usable audio features become `AudioTasteTrackFeature` rows:
   - `trackScope=ems_collected_track`
   - `trackId=ems-track:<id>`
   - title, artist, source platform, and audio feature fields copied from `EmsCollectedTrackEntity`
6. The existing event-to-feature join then works without changing event storage.

## Coverage Semantics

Coverage must remain user-scope coverage, not whole EMS-pool coverage.

That means the profile feature set is:

- all PMS user library feature rows
- plus only EMS rows referenced by recent positive user events

The denominator must not include unrelated EMS pool tracks. This keeps `feature_ready_ratio` meaningful and prevents the large EMS acquisition pool from making one user's profile look permanently incomplete.

## Error Handling

EMS lookup failures should be no-op:

- malformed `ems-track:*` ids are ignored
- missing EMS rows are ignored
- EMS rows without usable audio features are ignored
- absence of `EmsCollectedTrackRepository` in local/test profiles keeps current PMS-only behavior

No user-facing request should fail because one EMS event cannot be resolved.

## Testing

Add focused service tests around `AudioTasteProfileService`:

- PMS-only profile behavior remains unchanged.
- `play_completed` for `ems-track:<id>` contributes to positive centroid when the EMS track has usable features.
- malformed EMS ids and missing EMS tracks are ignored.
- EMS negative events are not added in this first step.
- coverage denominator includes only PMS rows plus referenced EMS rows, not the full EMS pool.

## Operational Verification

After deployment:

1. Play a `/gms-playlists` track through completion.
2. Recompute audio taste profile:
   - `POST /api/v1/recommendations/admin/audio-taste/recompute`
3. Confirm:
   - `positive_track_count` increases for usable EMS tracks
   - profile warnings still reflect normal gates
4. Run GMS preview and inspect audit log:
   - `evaluated_count` should become greater than zero once candidate/profile prerequisites are met
   - `apply_ranking_boost` remains controlled by configuration

