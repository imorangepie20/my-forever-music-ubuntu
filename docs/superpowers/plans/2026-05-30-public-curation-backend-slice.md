# Public Curation Backend Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Public Curation playlist drafts, selected tracks, generation runs, public playback sessions, and public play events can be represented and persisted by the Spring Boot API.

**Architecture:** Add a new `publiccuration` module under `services/api`. The first slice creates the database schema, application store contract, JPA entities, repositories, and a persistence adapter; it does not start the admin UI, public page, model scoring, or OAuth flow yet.

**Tech Stack:** Spring Boot 3.5, Java 21 records, Spring Data JPA, Flyway, JUnit 5, Mockito, AssertJ.

---

### Task 1: Public Curation Persistence Contract

**Files:**
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationPlaylistStore.java`
- Create: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/JpaPublicCurationPlaylistStoreTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void shouldSavePlaylistDraftWithTracksAndRun() {
    PublicCurationPlaylistRepository playlistRepository = mock(PublicCurationPlaylistRepository.class);
    PublicCurationPlaylistTrackRepository trackRepository = mock(PublicCurationPlaylistTrackRepository.class);
    PublicCurationRunRepository runRepository = mock(PublicCurationRunRepository.class);
    JpaPublicCurationPlaylistStore store = new JpaPublicCurationPlaylistStore(
        playlistRepository,
        trackRepository,
        runRepository
    );

    PublicCurationPlaylistStore.CreateDraft draft = draft();
    when(playlistRepository.save(any(PublicCurationPlaylistEntity.class))).thenAnswer(invocation -> {
        PublicCurationPlaylistEntity entity = invocation.getArgument(0);
        ReflectionTestUtils.setField(entity, "playlistId", 10L);
        return entity;
    });
    when(trackRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    when(runRepository.save(any(PublicCurationRunEntity.class))).thenAnswer(invocation -> {
        PublicCurationRunEntity entity = invocation.getArgument(0);
        ReflectionTestUtils.setField(entity, "runId", 20L);
        return entity;
    });

    PublicCurationPlaylistStore.StoredPlaylist stored = store.createDraft(draft);

    assertThat(stored.playlistId()).isEqualTo(10L);
    assertThat(stored.status()).isEqualTo("draft");
    assertThat(stored.slug()).isEqualTo("rainy-jazz-night");
    assertThat(stored.tracks()).hasSize(2);
    assertThat(stored.run().runId()).isEqualTo(20L);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/api && ./gradlew test --tests io.myforevermusic.api.modules.publiccuration.infrastructure.persistence.JpaPublicCurationPlaylistStoreTest`

Expected: FAIL because `PublicCurationPlaylistStore`, `JpaPublicCurationPlaylistStore`, entities, and repositories do not exist yet.

- [ ] **Step 3: Add the minimal contract**

Create `PublicCurationPlaylistStore` with records for `CreateDraft`, `TrackDraft`, `RunDraft`, `StoredPlaylist`, `StoredTrack`, and `StoredRun`.

- [ ] **Step 4: Implement persistence adapter and entities**

Create JPA entities/repositories for `public_curation_playlist`, `public_curation_playlist_track`, and `public_curation_run`, plus `JpaPublicCurationPlaylistStore#createDraft`.

- [ ] **Step 5: Run test to verify it passes**

Run: `cd services/api && ./gradlew test --tests io.myforevermusic.api.modules.publiccuration.infrastructure.persistence.JpaPublicCurationPlaylistStoreTest`

Expected: PASS.

### Task 2: Public Playback Session And Event Schema

**Files:**
- Create: `services/api/src/main/resources/db/migration/V48__create_public_curation_storage.sql`
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/PublicPlaybackSessionEntity.java`
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/PublicPlaylistPlayEventEntity.java`

- [ ] **Step 1: Add Flyway migration**

Create the five tables from the spec: `public_curation_playlist`, `public_curation_playlist_track`, `public_curation_run`, `public_playback_session`, `public_playlist_play_event`.

- [ ] **Step 2: Verify migration naming and SQL formatting**

Run: `git diff --check services/api/src/main/resources/db/migration/V48__create_public_curation_storage.sql`

Expected: no output and exit code 0.

- [ ] **Step 3: Add entities for public playback session and public play event**

Map table names and key fields exactly to the migration. Do not wire OAuth or playback behavior in this slice.

### Task 3: Commit Backend Slice

**Files:**
- Stage only files under `docs/superpowers/plans/2026-05-30-public-curation-backend-slice.md`, `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration`, `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration`, and `services/api/src/main/resources/db/migration/V48__create_public_curation_storage.sql`.

- [ ] **Step 1: Run focused tests**

Run: `cd services/api && ./gradlew test --tests io.myforevermusic.api.modules.publiccuration.infrastructure.persistence.JpaPublicCurationPlaylistStoreTest`

Expected: PASS.

- [ ] **Step 2: Run diff check**

Run: `git diff --check`

Expected: no output and exit code 0.

- [ ] **Step 3: Commit**

Run:

```bash
git add docs/superpowers/plans/2026-05-30-public-curation-backend-slice.md \
  services/api/src/main/java/io/myforevermusic/api/modules/publiccuration \
  services/api/src/test/java/io/myforevermusic/api/modules/publiccuration \
  services/api/src/main/resources/db/migration/V48__create_public_curation_storage.sql
git commit -m "feat: add public curation persistence"
```
