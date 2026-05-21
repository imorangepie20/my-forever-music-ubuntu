# Positive EMS Playback Audio Feature Queue Design

Date: `2026-05-22`

## Goal

When a user listens to GMS/EMS tracks, those `ems-track:<id>` playback events should not wait behind the generic EMS pool scan. Positive EMS playback events should create high-priority audio feature completion jobs for the referenced `ems_collected_track` rows, so the tracks the user actually heard can become usable audio taste input quickly.

## Current Context

- `AudioTasteProfileService` now includes PMS library rows plus EMS rows referenced by recent positive playback events.
- Operational verification showed `ems_collected_track` rows are present in the audio taste dataset, but recently played tracks such as `ems-track:318283` and `ems-track:318279` still have `audio_feature_source=unavailable`.
- `POST /api/v1/recommendations/admin/audio-feature-completion/enqueue-positive-events` currently scans only PMS `track_id` values.
- The existing worker already treats `positive_audio_taste_retry` as an inference-only reason, so it uses Last.fm and LLM/search fallback without repeating ReccoBeats lookup.

## Design

Extend the existing `enqueue-positive-events` endpoint instead of adding a new endpoint.

The endpoint will accept an optional `track_scope` query parameter:

- `all` default: scan both PMS and EMS positive event references.
- `pms`: preserve the current PMS-only behavior.
- `ems`: scan only EMS playback references.

The response shape stays the same. The response `scope` will be one of:

- `positive_events`
- `pms_positive_events`
- `ems_positive_events`

## Event Selection

The service will scan recent `user_music_event` rows for the target user, using the existing `event_limit`.

An event is eligible only when:

- resolved event weight is greater than `0.0`
- the event has a usable track reference
- for EMS, either `track_id` or `item_id` matches `ems-track:<numeric-id>`

EMS parsing follows the same rule used by the audio taste profile:

1. Prefer `track_id` when present.
2. Fall back to `item_id`.
3. Accept only `ems-track:<positive long>`.
4. Ignore malformed ids and negative/zero-weight events.

## Queueing Rules

For PMS references:

- Keep the current behavior.
- Look up the track in the target user's PMS library.
- Skip if audio features are already complete.
- Enqueue `track_scope=pms_user_track`, `track_id=<pms track id>`, `user_id=<target user id>`.

For EMS references:

- Look up referenced ids in `EmsCollectedTrackRepository`.
- Skip rows that already have complete audio features.
- Enqueue `track_scope=ems_collected_track`, `track_id=<numeric ems collected track id>`, `user_id=null`.
- Use `requested_reason=positive_audio_taste_retry`.
- Use priority at least as high as PMS positive retry so user-heard EMS tracks are processed before generic `ems_collect` jobs.

Existing uniqueness behavior in `AudioFeatureCompletionJobStore.enqueueIfAbsent` remains the duplicate guard. Duplicate or already queued jobs should increment `skipped_existing_job_count`, not create extra rows.

## Script And Docs

Update `infra/scripts/run-audio-feature-completion-backfill.sh`:

- Add `--positive-scope all|pms|ems`.
- Default to `all`.
- Pass `track_scope` to `/enqueue-positive-events`.
- Rename the log copy from `positive-event PMS tracks` to `positive-event tracks`.

Update `docs/api/AUDIO_FEATURE_COMPLETION_ADMIN_API.md`:

- Document `track_scope`.
- Explain that EMS positive playback uses `ems-track:<id>` references and enqueues `ems_collected_track` jobs.
- Show an EMS-focused operational example.

## Error Handling

- If the event store is unavailable, keep the existing `412 PRECONDITION_FAILED`.
- If `track_scope` is not `all`, `pms`, or `ems`, return `400 BAD_REQUEST`.
- If `emsTrackRepository` is unavailable and `track_scope` includes EMS, scan PMS if requested and otherwise return zero EMS enqueue results rather than failing the whole endpoint.
- Malformed EMS ids are ignored.
- Missing EMS rows are ignored.

## Testing

Add focused service tests around `AudioFeatureCompletionService`:

- positive EMS `track_id=ems-track:<id>` enqueues an `ems_collected_track` job.
- EMS `item_id` fallback works when `track_id` is missing.
- malformed EMS ids and negative events do not query or enqueue.
- complete EMS audio features are skipped.
- `track_scope=pms` preserves PMS-only behavior.
- `track_scope=ems` excludes PMS tracks.

Run focused tests first, then the relevant API test set.

## Operational Verification

After deployment, run:

```bash
./infra/scripts/run-audio-feature-completion-backfill.sh \
  --admin-user user-1c7b2adc-f828-40f0-9da3-7b35d0d24457 \
  --target-user user-1c7b2adc-f828-40f0-9da3-7b35d0d24457 \
  --positive-events \
  --positive-scope ems \
  --positive-limit 10 \
  --process-limit 5 \
  --rounds 2
```

Then re-run audio taste dataset inspection and confirm previously `unavailable` listened EMS tracks either become complete or receive a new unresolved reason from the inference path.

## Non-Goals

- Do not enqueue jobs synchronously from playback event recording.
- Do not change the audio taste profile scoring model.
- Do not broaden the profile dataset to the full EMS pool.
- Do not add a new completion worker reason.
