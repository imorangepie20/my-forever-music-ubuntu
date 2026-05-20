# Artist Detail Page Design

## Goal

Create a first artist detail page that treats an artist as a PMS-owned taste anchor, not as a single provider-specific object.

## Decision

Artist detail URLs use a canonical artist slug based on the display artist name:

- Route: `/artists/:artistSlug`
- Optional query: `?name={display artist name}`
- Canonical slug examples: `NewJeans -> newjeans`, `The Weeknd -> the-weeknd`

The `name` query is used when the user clicks a known artist name in the app. If it is missing, the page falls back to the slug converted back to readable text.

## First Scope

The first version does not add a mandatory live Spotify/TIDAL/YouTube artist lookup. It uses the current real PMS/EMS stored data and the existing search/playback model:

- PMS user tracks matching the artist name
- EMS collected tracks matching the artist name
- Provider/source breakdown from those tracks
- Platform candidate chips inferred from matching track platforms
- Playable track lists using the existing playback item shape

This avoids exposing mock artist data and keeps failures honest. Live provider artist search can be added later behind the same response shape.

## API

Add:

`GET /api/v1/artists/{artistSlug}?user_id={userId}&artist_name={artistName}`

Response:

- `artist.artist_slug`
- `artist.display_name`
- `artist.image_url`
- `summary.pms_track_count`
- `summary.ems_track_count`
- `summary.total_track_count`
- `platform_candidates[]`
- `pms_tracks[]`
- `ems_tracks[]`

## Web

Add:

- `ArtistDetailPage`
- `/artists/:artistSlug` route
- API client/types for artist detail
- Artist links from reusable track cards

The page shows a compact profile header, platform/source chips, PMS tracks, EMS/GMS discovery tracks, and uses the existing global playback context for play buttons.

## Verification

- Backend WebMvc test proves the endpoint shape.
- Frontend build proves the route, types, and page compile.
- Existing playlist/search pages continue to render because artist linking is additive.
