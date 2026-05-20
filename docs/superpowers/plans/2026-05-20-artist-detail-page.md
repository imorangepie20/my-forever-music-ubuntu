# Artist Detail Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a first artist detail page using canonical artist-name slugs and real PMS/EMS stored track data.

**Architecture:** Add a small `artist` backend module that aggregates matching PMS and EMS tracks, then expose it through a typed React page at `/artists/:artistSlug`. Keep provider artist search as an inferred candidate layer for this pass, so no mock provider profile is shown.

**Tech Stack:** Spring Boot 3.5, Java 21, JUnit/MockMvc, React, TypeScript, Vite, Playwright-ready routing.

---

### Task 1: Backend Artist Detail Contract

**Files:**
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/artist/application/ArtistDetailService.java`
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/artist/presentation/ArtistDetailController.java`
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/artist/presentation/ArtistDetailResponse.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/artist/presentation/ArtistDetailControllerWebMvcTest.java`

- [ ] Write a failing WebMvc test for `GET /api/v1/artists/newjeans?user_id=user-1&artist_name=NewJeans`.
- [ ] Implement the response records and controller.
- [ ] Implement service aggregation from repositories with a no-repository fallback to empty lists.
- [ ] Run the targeted backend test and make it pass.

### Task 2: Backend Repository Queries

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/pms/infrastructure/persistence/PmsUserTrackRepository.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/ems/infrastructure/persistence/EmsCollectedTrackRepository.java`

- [ ] Add case-insensitive artist-name lookup methods with `Pageable`.
- [ ] Use those methods from `ArtistDetailService`.
- [ ] Run the targeted backend test.

### Task 3: Frontend Artist Detail Page

**Files:**
- Modify: `apps/web/src/types/api.ts`
- Modify: `apps/web/src/services/api.ts`
- Modify: `apps/web/src/App.tsx`
- Create: `apps/web/src/lib/artistLinks.ts`
- Create: `apps/web/src/pages/ArtistDetailPage.tsx`

- [ ] Add TypeScript response types.
- [ ] Add `fetchArtistDetail`.
- [ ] Add `buildArtistDetailPath`.
- [ ] Add the route `/artists/:artistSlug`.
- [ ] Implement the page with loading, empty, error, PMS tracks, and EMS tracks states.

### Task 4: Artist Click Entrypoints

**Files:**
- Modify: `apps/web/src/components/music/TrackFeatureCard.tsx`
- Modify call sites only if TypeScript requires it.

- [ ] Add optional `artistDetailPath` prop.
- [ ] Render artist name as a link when provided.
- [ ] Pass artist detail paths from EMS/PMS/GMS card call sites that already have artist names.

### Task 5: Verification

**Files:**
- No new production files unless verification exposes a bug.

- [ ] Run targeted backend test.
- [ ] Run `npm run build` in `apps/web`.
- [ ] Run a broader backend compile/test target if time permits.
