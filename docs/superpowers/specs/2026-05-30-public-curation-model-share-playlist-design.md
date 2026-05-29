# Public Curation Model Share Playlist Design

## Summary

Public Curation is a separate product domain for model-generated public share playlists.

An operator enters a natural-language theme plus structured filters. The system gathers candidate tracks from the available internal track pool, evaluates them with a Public Curation Model, selects about 30 TIDAL-playable tracks, generates title/copy/track reasons, and publishes a visually rich public share page.

Visitors can open the page without a My Forever Music account. To play inside the site, they authenticate with their own TIDAL account through a temporary public playback session. Public page playback does not use YouTube fallback and does not write into a user's PMS/GMS learning data.

## Product Intent

This feature is not a manual operator playlist builder.

The operator defines the editorial intention:

- theme prompt
- mood
- genre/tag filters
- audio feature ranges
- language or region
- release-year range
- target track count
- TIDAL playback requirement
- artist/genre duplication limits
- intended flow, such as calm start, stronger middle, soft ending

The model does the curation work:

- candidate retrieval
- track scoring
- redundancy control
- 30-track selection
- ordering
- title, subtitle, description, and per-track reason generation

The final playlist is meant to be shared externally, for example in Naver Cafe posts, blog posts, or social links.

## Scope

### Included In The First Product Slice

- Admin-only creation screen for a public curation run.
- Natural-language prompt plus structured filters.
- Candidate extraction from internal track pools.
- Public Curation Model scoring through FastAPI.
- Spring Boot fallback scoring if FastAPI is unavailable.
- Public curation playlist persistence.
- Published share page at `/share/playlists/{slug}`.
- Magazine/poster-style public page design that differs from the normal app shell.
- TIDAL OAuth redirect for public playback sessions.
- Existing `/platforms/oauth/callback` redirect URI reused with state-based flow routing.
- In-site playback for visitors who authenticated with TIDAL for this public page.
- Anonymous public playback events stored separately from user music events.

### Deferred

- TIDAL device-code fallback for public playback.
- YouTube fallback playback on public share pages.
- Importing a public curation playlist into a visitor's PMS.
- Public comments, likes, ranking, or social features.
- Multiple operator approval workflow.
- Full CMS-style page layout editor.

## Relationship To Existing Domains

Public Curation is separate from GMS.

GMS is user-personalized recommendation. Public Curation is public editorial generation for external sharing. They may use the same tracks and some shared feature data, but they must not share scoring intent or learning feedback.

Public Curation can read from EMS, PMS-derived global track candidates, GMS candidate outputs, acquisition tracks, search pool tracks, and imported platform tracks where legally and technically usable. The final published track list must strongly prefer or require TIDAL playback readiness because the public page plays through visitor-owned TIDAL credentials.

Public Curation events must not be written to `user_music_event` unless the visitor is also a logged-in My Forever Music user and explicitly chooses a user-bound action in a separate user-library feature. First-slice playback events go into a public analytics event table.

## Core Flow

### Admin Generation Flow

1. Operator opens `/admin/public-curations`.
2. Operator enters a prompt such as "비 오는 밤에 듣기 좋은 한국 인디와 재즈 감성. 너무 처지지 않고 카페에서 공유하기 좋은 30곡."
3. Operator sets structured filters:
   - target count, default 30
   - genre/tags
   - mood tags
   - energy, valence, acousticness, danceability, tempo ranges
   - language/region
   - release-year range
   - artist max count
   - TIDAL readiness required
4. Spring Boot creates a `public_curation_run`.
5. Spring Boot gathers candidate tracks and sends them to FastAPI.
6. FastAPI returns scored candidates, selected tracks, order, generated title/copy, and per-track reasons.
7. Spring Boot stores the playlist draft.
8. Operator previews the draft.
9. Operator publishes it, creating or activating a stable slug.
10. Public page becomes available at `/share/playlists/{slug}`.

### Public Playback Flow

1. Visitor opens `/share/playlists/{slug}` from an external post.
2. The page renders public metadata, editorial copy, cover treatment, and track list.
3. Visitor clicks "TIDAL로 여기서 듣기".
4. If a valid public playback session exists, playback starts.
5. If not, Spring Boot starts a public TIDAL OAuth flow.
6. Browser redirects to TIDAL login.
7. TIDAL redirects back to the existing `/platforms/oauth/callback`.
8. The callback reads stored OAuth state and detects `flow=public-curation`.
9. Spring Boot exchanges the code, creates a short-lived `public_playback_session`, and redirects to `/share/playlists/{slug}?playback=ready`.
10. Public page loads session playback credentials and plays the playlist in the site.

## Public Page Design

The public page should feel more like a music magazine feature than an app dashboard.

The recommended visual direction is a hybrid:

- first viewport: cinematic poster-like hero
- title and subtitle generated by the model
- strong cover image or generated visual treatment
- "TIDAL로 여기서 듣기" primary CTA
- track count and approximate duration
- short curation note
- model-selected highlights
- full 30-track list
- per-track one-line reason
- TIDAL playback readiness indicator

The public page should not use the normal authenticated app sidebar/header shell. It can reuse low-level design tokens, player primitives, and playback context logic where practical, but it should present as a shareable public artifact.

## Admin Page Design

The admin page should remain operational and dense rather than decorative.

Expected panels:

- Prompt editor.
- Structured filters.
- Candidate pool summary.
- Model scoring axis weights.
- Run status and errors.
- Draft result preview.
- Selected 30 tracks with score, reason, and source.
- Publish controls.
- Copy public share URL.

The operator controls intent and publication, not individual manual song selection as the main workflow.

## Data Model

### `public_curation_playlist`

Stores the generated public playlist.

Fields:

- `id`
- `slug`
- `title`
- `subtitle`
- `description`
- `prompt`
- `filter_snapshot_json`
- `status`: `draft`, `published`, `archived`
- `cover_style`
- `model_version`
- `track_count`
- `duration_ms`
- `published_at`
- `created_by_admin_user_id`
- `created_at`
- `updated_at`

### `public_curation_playlist_track`

Stores the final ordered track list and display metadata.

Fields:

- `id`
- `playlist_id`
- `track_order`
- `source_track_scope`: `ems_collected_track`, `pms_user_track`, `gms_candidate`, `search_pool`, or another explicit source
- `source_track_id`
- `title`
- `artist_name`
- `album_title`
- `image_url`
- `duration_ms`
- `isrc`
- `tidal_track_id`
- `tidal_uri`
- `tidal_external_url`
- `score`
- `score_breakdown_json`
- `reason`
- `created_at`

### `public_curation_run`

Stores generation attempts and diagnostics.

Fields:

- `id`
- `playlist_id`
- `prompt`
- `filter_snapshot_json`
- `candidate_count`
- `selected_count`
- `model_version`
- `status`: `running`, `completed`, `failed`
- `score_summary_json`
- `error_message`
- `started_at`
- `completed_at`

### `public_playback_session`

Stores visitor-owned temporary TIDAL playback credentials.

Fields:

- `session_id`
- `playlist_id`
- `tidal_account_label`
- `access_token_encrypted`
- `refresh_token_encrypted`
- `scope_summary`
- `expires_at`
- `created_at`
- `last_used_at`

Sessions are scoped to public playback. They must not create a normal platform connection and must not be attached to PMS user library state.

### `public_playlist_play_event`

Stores anonymous public page playback analytics.

Fields:

- `id`
- `playlist_id`
- `public_session_id`
- `track_id`
- `event_type`: `play_started`, `play_completed`, `skipped`, `play_failed`
- `position_ms`
- `duration_ms`
- `occurred_at`
- `received_at`

## Model Contract

Spring Boot sends candidate tracks to FastAPI.

Request fields:

- `prompt`
- `filters`
- `target_track_count`
- `candidate_tracks[]`
  - source scope/id
  - title
  - artist
  - album
  - duration
  - ISRC
  - source platform
  - TIDAL identifiers if available
  - audio features
  - genre/tags
  - popularity/freshness signals when available

FastAPI returns:

- generated `title`
- generated `subtitle`
- generated `description`
- selected `tracks[]`
  - source reference
  - order
  - score
  - score breakdown
  - reason
- run-level score summary
- model version

## Scoring Axes

The first Public Curation Model uses these axes:

- `theme_fit`: alignment with the operator prompt.
- `tidal_readiness`: TIDAL track id, URI, and playback readiness.
- `audio_fit`: match to requested audio feature ranges.
- `coherence`: flow across the final 30-track sequence.
- `diversity`: artist, genre, source, era, and mood variety.
- `freshness`: discovery value and not only obvious tracks.
- `redundancy`: penalty for repeated artists, duplicate tracks, or overly similar adjacent songs.
- `shareability`: how explainable and attractive the track is on a public page.

## OAuth Strategy

The first slice uses TIDAL OAuth redirect only.

It should reuse the existing registered callback path:

`/platforms/oauth/callback`

State must distinguish normal platform connection from public curation playback:

- `flow=public-curation`
- `slug`
- `playlist_id`
- `return_path`
- `csrf_nonce`

On callback, the frontend/backend path must avoid storing the credential as a normal user platform connection. It creates a `public_playback_session` instead.

If OAuth redirect fails due to TIDAL policy or redirect limitations, the second slice adds device-code fallback on the same public page.

## Playback Policy

Public share pages do not use YouTube fallback.

Reasons:

- public traffic can exhaust YouTube Data API quota quickly
- the public page target is TIDAL listeners
- the final curated list requires TIDAL readiness
- fallback playback can blur the intended audio quality and rights boundary

Playback uses the visitor's own TIDAL authorization through the public playback session.

## API Sketch

Admin APIs:

- `POST /api/v1/public-curations/runs`
- `GET /api/v1/public-curations/runs/{runId}`
- `GET /api/v1/public-curations/playlists/{playlistId}`
- `POST /api/v1/public-curations/playlists/{playlistId}/publish`
- `POST /api/v1/public-curations/playlists/{playlistId}/archive`

Public APIs:

- `GET /api/v1/public-curations/share/{slug}`
- `POST /api/v1/public-curations/share/{slug}/tidal/oauth/start`
- `GET /api/v1/public-curations/share/{slug}/playback/session`
- `POST /api/v1/public-curations/share/{slug}/playback/events`

The existing OAuth callback can call a backend completion endpoint that branches by stored state.

## Error Boundaries

- No candidate tracks: fail the run with visible admin error.
- Fewer than target TIDAL-ready tracks: create a draft only if it still meets a minimum publishable count configured by the admin; otherwise fail the run.
- FastAPI unavailable: use Spring Boot fallback scoring and mark the run as fallback-generated.
- OAuth state missing: show public page with a reconnect CTA.
- TIDAL token exchange fails: show public page with an explicit TIDAL authentication error.
- Public playback session expired: require TIDAL authentication again.
- Track playback fails: skip only if the playlist has another TIDAL-ready track; record a public play failure event.

## Testing Strategy

Backend:

- Public curation run stores prompt/filter snapshot.
- Candidate extraction filters for TIDAL readiness.
- FastAPI response is persisted in playlist and playlist track tables.
- Spring Boot fallback creates deterministic scores when FastAPI fails.
- Publish creates a stable slug and public page payload.
- Public OAuth state creates a public playback session, not a user platform connection.
- Public play events are written to `public_playlist_play_event`, not `user_music_event`.

Frontend:

- Admin page submits prompt and filters.
- Admin page shows run status and generated draft.
- Published public page renders without normal app shell.
- Public page starts TIDAL OAuth when no public playback session exists.
- Public page returns to the same slug after OAuth.
- Public page disables YouTube fallback.
- Public page plays with a public playback session.

## Rollout Plan

1. Schema and backend domain.
2. FastAPI scoring endpoint with a minimal deterministic model.
3. Spring Boot candidate extraction and fallback scoring.
4. Admin generation page.
5. Public share page.
6. Public TIDAL OAuth session flow.
7. Public playback event logging.
8. Real TIDAL account end-to-end verification.
9. Device-code fallback design and implementation if OAuth redirect is not reliable.
