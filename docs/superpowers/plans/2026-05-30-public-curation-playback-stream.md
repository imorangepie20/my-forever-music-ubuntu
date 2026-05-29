# Public Curation Playback Stream Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 공개 공유 페이지 방문자가 My Forever Music 로그인 없이 본인 TIDAL OAuth public playback session으로 사이트 안에서 공개 큐레이션 트랙을 재생할 수 있게 한다.

**Architecture:** 기존 `user_id` 기반 TIDAL stream API는 유지한다. TIDAL playbackinfo 해석 로직을 재사용 가능한 application service로 옮기고, public curation API는 `public_session_id + slug + public track id`로 세션/플레이리스트 소유권을 확인한 뒤 같은 stream resolver를 호출한다. Frontend는 공개 페이지 전용 sessionStorage 값을 읽어 public stream API를 호출하고, 재생 이벤트는 `public_playlist_play_event`에만 기록한다.

**Tech Stack:** Spring Boot 3.5, Java 21 records, MockMvc/JUnit, React + TypeScript + Vite, hls.js, existing `tidalStreamPlayback` primitive.

---

## 파일 구조

- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/platform/presentation/TidalPlaybackStreamController.java`
  - 기존 사용자용 endpoint 유지, 새 `TidalPlaybackStreamService` 호출로 변경.
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/platform/application/TidalPlaybackStreamService.java`
  - TIDAL playbackinfo 호출, manifest decode, FULL stream 검증 공통 로직.
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicPlaybackSessionStore.java`
  - public stream resolving에 필요한 token 필드를 `StoredSession`에 포함.
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/PublicPlaybackSessionEntity.java`
  - `toState()`가 access/refresh token을 전달.
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationPlaybackStreamService.java`
  - slug, public session, public track 검증 후 TIDAL stream response 생성.
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationPlaybackStreamController.java`
  - 공개 공유 페이지 전용 stream endpoint.
- Create: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationPlaybackStreamServiceTest.java`
  - public session/playlist/track 경계 검증.
- Create: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationPlaybackStreamControllerWebMvcTest.java`
  - endpoint contract 검증.
- Modify: `apps/web/src/types/api.ts`
  - public stream response type 추가.
- Modify: `apps/web/src/services/api.ts`
  - public stream API client 추가.
- Modify: `apps/web/src/lib/tidalStreamPlayback.ts`
  - public stream API를 받아 재생하는 helper 추가.
- Modify: `apps/web/src/pages/PublicCurationSharePage.tsx`
  - OAuth 완료 session을 읽고 첫 곡/선택 곡 재생.
- Create: `apps/web/scripts/public-curation-playback-stream-harness.mjs`
  - 공개 재생 wiring regression harness.

---

### Task 1: Backend Public Stream Endpoint

**Files:**
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/platform/application/TidalPlaybackStreamService.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/platform/presentation/TidalPlaybackStreamController.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicPlaybackSessionStore.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/PublicPlaybackSessionEntity.java`
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationPlaybackStreamService.java`
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationPlaybackStreamController.java`
- Create: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationPlaybackStreamServiceTest.java`
- Create: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationPlaybackStreamControllerWebMvcTest.java`

- [x] **Step 1: Write failing service test**

Create `PublicCurationPlaybackStreamServiceTest` with this behavior:

```java
@Test
void shouldResolveStreamOnlyWhenSessionAndTrackBelongToPublishedPlaylist() {
    Instant now = Instant.parse("2026-05-30T04:00:00Z");
    PublicCurationPlaylistStore.StoredTrack track = new PublicCurationPlaylistStore.StoredTrack(
        100L,
        42L,
        1,
        "pms_user_track",
        "track-100",
        "Rain Street",
        "Blue Trio",
        "Night Walk",
        null,
        181000,
        "KRA000000001",
        "10001",
        "tidal:track:10001",
        "https://tidal.com/browse/track/10001",
        0.94,
        "{}",
        "비 오는 밤의 첫 분위기를 만든다.",
        now
    );
    PublicCurationPlaylistStore.StoredPlaylist playlist = new PublicCurationPlaylistStore.StoredPlaylist(
        42L,
        "rainy-night",
        "비 오는 밤",
        null,
        null,
        "prompt",
        "{}",
        "published",
        "poster-dark",
        "public-curation-deterministic-v1",
        1,
        181000L,
        now,
        "admin-001",
        now,
        now,
        List.of(track),
        null
    );
    FakePlaylistStore playlistStore = new FakePlaylistStore(playlist);
    FakeSessionStore sessionStore = new FakeSessionStore(new PublicPlaybackSessionStore.StoredSession(
        "public-curation-session-1",
        42L,
        "TIDAL Listener",
        "public-access-token",
        "public-refresh-token",
        "r_usr w_usr w_sub r_stream",
        now.plusSeconds(3600),
        now,
        null
    ));
    FakeTidalStreamService tidalStreamService = new FakeTidalStreamService();
    PublicCurationPlaybackStreamService service = new PublicCurationPlaybackStreamService(
        playlistStore,
        sessionStore,
        tidalStreamService
    );

    PublicCurationPlaybackStreamService.PublicStreamResponse response =
        service.stream("rainy-night", "public-curation-session-1", 100L, "HIGH");

    assertThat(response.service()).isEqualTo("public-curation-playback-stream");
    assertThat(response.playlistId()).isEqualTo(42L);
    assertThat(response.publicSessionId()).isEqualTo("public-curation-session-1");
    assertThat(response.trackId()).isEqualTo(100L);
    assertThat(response.tidalTrackId()).isEqualTo("10001");
    assertThat(tidalStreamService.requestedAccessToken).isEqualTo("public-access-token");
    assertThat(tidalStreamService.requestedTidalTrackId).isEqualTo("10001");
}
```

- [x] **Step 2: Run service test to verify it fails**

Run:

```bash
cd services/api && ./gradlew test --tests io.myforevermusic.api.modules.publiccuration.application.PublicCurationPlaybackStreamServiceTest
```

Expected: FAIL because `PublicCurationPlaybackStreamService` and `TidalPlaybackStreamService` do not exist, and `StoredSession` does not expose access token.

- [x] **Step 3: Implement reusable TIDAL stream service**

Create `TidalPlaybackStreamService` by moving playbackinfo/decode helpers out of `TidalPlaybackStreamController`.

Public API:

```java
public TidalPlaybackStream resolve(
    PlatformAccountCredential credential,
    String trackId,
    String quality
)
```

Response record:

```java
public record TidalPlaybackStream(
    String trackId,
    String countryCode,
    String requestedQuality,
    String audioQuality,
    String codec,
    Integer bitRate,
    Integer sampleRate,
    Integer bitDepth,
    String assetPresentation,
    String manifestMimeType,
    String manifestCodecs,
    String encryptionType,
    Double durationSeconds,
    String streamUrl
) {}
```

Update `TidalPlaybackStreamController#streamUrl(...)` to resolve the existing credential and build the same `TidalPlaybackStreamResponse` from this service. Keep the existing endpoint path and JSON fields unchanged.

- [x] **Step 4: Add token-aware public session state**

Modify `PublicPlaybackSessionStore.StoredSession` to:

```java
record StoredSession(
    String sessionId,
    Long playlistId,
    String tidalAccountLabel,
    String accessToken,
    String refreshToken,
    String scopeSummary,
    Instant expiresAt,
    Instant createdAt,
    Instant lastUsedAt
) {}
```

Update `PublicPlaybackSessionEntity#toState()` and every test fake/constructor call to pass the token fields.

- [x] **Step 5: Implement public playback stream service and controller**

`PublicCurationPlaybackStreamService#stream(slug, publicSessionId, publicTrackId, quality)` must:

- find published playlist by slug
- find active public session by id
- require session playlist id equals playlist id
- find the requested public playlist track by `trackId`
- require the track has `tidalTrackId`
- create an internal `PlatformAccountCredential` from the public session token
- call `TidalPlaybackStreamService.resolve(...)`
- return `PublicStreamResponse` with stream metadata and URL

Controller endpoint:

```text
GET /api/v1/public-curations/share/{slug}/playback/tracks/{track_id}/stream?public_session_id=...&quality=HIGH
```

- [x] **Step 6: Write and run controller test**

Create `PublicCurationPlaybackStreamControllerWebMvcTest`:

```java
@WebMvcTest(PublicCurationPlaybackStreamController.class)
@AutoConfigureMockMvc(addFilters = false)
class PublicCurationPlaybackStreamControllerWebMvcTest {
    @Autowired MockMvc mockMvc;
    @MockBean PublicCurationPlaybackStreamService service;

    @Test
    void shouldReturnPublicTidalStream() throws Exception {
        when(service.stream("rainy-night", "public-session-1", 100L, "HIGH"))
            .thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/public-curations/share/rainy-night/playback/tracks/100/stream")
                .queryParam("public_session_id", "public-session-1")
                .queryParam("quality", "HIGH"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("public-curation-playback-stream"))
            .andExpect(jsonPath("$.track_id").value(100))
            .andExpect(jsonPath("$.tidal_track_id").value("10001"))
            .andExpect(jsonPath("$.stream_url").value("https://media.example/10001.m3u8"));
    }
}
```

Run:

```bash
cd services/api && ./gradlew test --tests io.myforevermusic.api.modules.publiccuration.application.PublicCurationPlaybackStreamServiceTest --tests io.myforevermusic.api.modules.publiccuration.presentation.PublicCurationPlaybackStreamControllerWebMvcTest
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/platform/application/TidalPlaybackStreamService.java \
  services/api/src/main/java/io/myforevermusic/api/modules/platform/presentation/TidalPlaybackStreamController.java \
  services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicPlaybackSessionStore.java \
  services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/PublicPlaybackSessionEntity.java \
  services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationPlaybackStreamService.java \
  services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationPlaybackStreamController.java \
  services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationPlaybackStreamServiceTest.java \
  services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationPlaybackStreamControllerWebMvcTest.java \
  docs/superpowers/plans/2026-05-30-public-curation-playback-stream.md
git commit -m "feat: add public curation tidal stream api"
```

---

### Task 2: Frontend Public Share Playback

**Files:**
- Modify: `apps/web/src/types/api.ts`
- Modify: `apps/web/src/services/api.ts`
- Modify: `apps/web/src/lib/tidalStreamPlayback.ts`
- Modify: `apps/web/src/pages/PublicCurationSharePage.tsx`
- Create: `apps/web/scripts/public-curation-playback-stream-harness.mjs`

- [x] **Step 1: Write failing frontend harness**

Create `apps/web/scripts/public-curation-playback-stream-harness.mjs` with checks:

```js
assertIncludes('src/services/api.ts', 'fetchPublicCurationTidalPlaybackStream')
assertIncludes('src/types/api.ts', 'PublicCurationPlaybackStreamResponse')
assertIncludes('src/lib/tidalStreamPlayback.ts', 'playPublicCurationTidalTrack')
assertIncludes('src/pages/PublicCurationSharePage.tsx', 'fetchPublicCurationPlaybackSession')
assertIncludes('src/pages/PublicCurationSharePage.tsx', 'playPublicCurationTidalTrack')
assertIncludes('src/pages/PublicCurationSharePage.tsx', 'recordPublicCurationPlaybackEvent')
assertIncludes('src/pages/PublicCurationSharePage.tsx', '지금 재생 중')
```

- [x] **Step 2: Run harness to verify it fails**

Run:

```bash
cd apps/web && node scripts/public-curation-playback-stream-harness.mjs
```

Expected: FAIL because the public stream API client and page playback wiring do not exist.

- [x] **Step 3: Add frontend API types and client**

Add `PublicCurationPlaybackStreamResponse` with the same snake_case fields returned by backend:

```ts
export interface PublicCurationPlaybackStreamResponse {
    service: string
    status: string
    generated_at: string
    playlist_id: number
    public_session_id: string
    track_id: number
    tidal_track_id: string
    country_code: string
    requested_quality: string
    audio_quality: string | null
    codec: string | null
    bit_rate: number | null
    sample_rate: number | null
    bit_depth: number | null
    asset_presentation: string | null
    manifest_mime_type: string | null
    manifest_codecs: string | null
    encryption_type: string | null
    duration_seconds: number | null
    stream_url: string
}
```

Add `fetchPublicCurationTidalPlaybackStream(slug, publicSessionId, trackId, quality, signal)`.

- [x] **Step 4: Add public playback helper**

Add `playPublicCurationTidalTrack(...)` to `tidalStreamPlayback.ts`. It should reuse the existing detached audio element, HLS/direct dispatch, snapshot callbacks, and `PublicCurationPlaybackStreamResponse` metadata. It must not require `userId`.

- [x] **Step 5: Wire public share page playback**

In `PublicCurationSharePage.tsx`:

- read session from `my-forever-music.public-curation-oauth.session.{slug}`
- call `fetchPublicCurationPlaybackSession` to confirm it is still ready
- if `?playback=ready`, auto start the first track after user clicks the active CTA again; do not autoplay without a user gesture
- CTA behavior:
  - no session: start OAuth
  - ready session: play first track
- track row play button behavior:
  - ready session: play clicked track
  - no session: start OAuth
- render Korean status: `지금 재생 중`, `TIDAL 세션 준비됨`, `TIDAL 재생 인증이 필요합니다`
- record `play_started` and `play_failed` using existing public event endpoint.

- [x] **Step 6: Run frontend harness and build**

Run:

```bash
cd apps/web && node scripts/public-curation-playback-stream-harness.mjs
cd apps/web && npm run build
```

Expected: harness PASS and build exit 0.

- [x] **Step 7: Commit**

```bash
git add apps/web/scripts/public-curation-playback-stream-harness.mjs \
  apps/web/src/pages/PublicCurationSharePage.tsx
git add -p apps/web/src/services/api.ts
git add -p apps/web/src/types/api.ts
git add apps/web/src/lib/tidalStreamPlayback.ts
git commit -m "feat: play public curation tidal streams"
```

---

## Self Review

- Spec coverage: public session 기반 사이트 내 TIDAL 재생, 기존 사용자 credential 분리, public play event 기록을 포함한다.
- Deliberate gap: token refresh와 public session 만료 연장은 포함하지 않는다. 만료 시 다시 TIDAL OAuth를 시작한다.
- Placeholder scan: 미완성 표기 없음.
- Type consistency: backend `public_session_id`, `track_id`, `stream_url`와 frontend snake_case 타입/클라이언트 이름이 일치한다.
