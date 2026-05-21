# Positive EMS Playback Audio Feature Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend positive-event audio feature completion so user-played `ems-track:<id>` rows are queued for feature inference before generic EMS backfill.

**Architecture:** Keep the existing `/enqueue-positive-events` endpoint and `positive_audio_taste_retry` worker reason. Add a `track_scope=all|pms|ems` selector, collect positive PMS and EMS event references from `user_music_event`, and enqueue EMS references as `ems_collected_track` jobs using the numeric EMS id. The worker already routes `positive_audio_taste_retry` through Last.fm/LLM inference-only processing, so no worker change is needed.

**Tech Stack:** Spring Boot 3.5, Java 21, Gradle, JUnit 5, Mockito, AssertJ, Bash.

---

## File Structure

- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionService.java`
  - Add `trackScope` support to positive-event enqueue.
  - Preserve the existing four-argument service overload as PMS-only compatibility.
  - Parse `ems-track:<numeric-id>` from `track_id`, falling back to `item_id`.
  - Batch-load referenced EMS rows and skip complete/missing/malformed rows.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/AudioFeatureCompletionAdminController.java`
  - Add `track_scope` request parameter with default `all`.
- Modify `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionServiceTest.java`
  - Add RED tests for EMS positive enqueue, item fallback, malformed/negative filtering, complete EMS skip, and scope selection.
- Modify `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/AudioFeatureCompletionAdminControllerWebMvcTest.java`
  - Verify the controller passes `track_scope`.
- Modify `infra/scripts/run-audio-feature-completion-backfill.sh`
  - Add `--positive-scope all|pms|ems` and pass it to the endpoint.
- Modify `docs/api/AUDIO_FEATURE_COMPLETION_ADMIN_API.md`
  - Document EMS positive playback queueing and the new script option.

---

### Task 1: Add RED Tests For EMS Positive Playback Queueing

**Files:**
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionServiceTest.java`

- [ ] **Step 1: Add Mockito imports**

At the top of `AudioFeatureCompletionServiceTest.java`, update static imports to include:

```java
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
```

- [ ] **Step 2: Add failing test for positive EMS `track_id` enqueue**

Add this test after `shouldEnqueueMissingPositiveEventPmsTracksForAudioTasteReadiness()`:

```java
@Test
void shouldEnqueueMissingPositiveEventEmsTracksForAudioTasteReadiness() {
    AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
    PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
    EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
    InMemoryJobStore jobStore = new InMemoryJobStore();
    InMemoryEventStore eventStore = new InMemoryEventStore();
    Instant now = Instant.parse("2026-05-22T00:00:00Z");

    when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(adminAccount("admin-user")));
    when(pmsUserLibraryStore.findPlaylists("target-user")).thenReturn(List.of());
    when(emsTrackRepository.findAllById(any())).thenReturn(List.of(
        emsTrack(318283L, "tidal", "194088149", unavailableEmsFeatures())
    ));
    eventStore.add(emsEvent("target-user", "play_completed", 1.0d, "ems-track:318283", "ems-track:318283", now));

    AudioFeatureCompletionService service = new AudioFeatureCompletionService(
        authAccountStore,
        pmsUserLibraryStore,
        Optional.of(emsTrackRepository),
        jobStore,
        Optional.empty(),
        Optional.of(eventStore),
        new EventSignalWeights()
    );

    AudioFeatureCompletionService.EnqueueCompletionResult result = service.enqueuePositiveEventAudioFeatures(
        "admin-user",
        "target-user",
        "ems",
        500,
        10
    );

    assertThat(result.targetUserId()).isEqualTo("target-user");
    assertThat(result.scope()).isEqualTo("ems_positive_events");
    assertThat(result.scannedTrackCount()).isEqualTo(1);
    assertThat(result.enqueuedJobCount()).isEqualTo(1);
    assertThat(result.skippedExistingJobCount()).isZero();
    assertThat(result.jobs()).singleElement().satisfies(job -> {
        assertThat(job.trackScope()).isEqualTo("ems_collected_track");
        assertThat(job.trackId()).isEqualTo("318283");
        assertThat(job.userId()).isNull();
        assertThat(job.requestedReason()).isEqualTo("positive_audio_taste_retry");
        assertThat(job.priority()).isEqualTo(130);
    });
}
```

- [ ] **Step 3: Add failing test for EMS `item_id` fallback**

Add this test after the previous test:

```java
@Test
void shouldUsePositiveEventItemIdForEmsWhenTrackIdIsMissing() {
    AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
    PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
    EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
    InMemoryJobStore jobStore = new InMemoryJobStore();
    InMemoryEventStore eventStore = new InMemoryEventStore();
    Instant now = Instant.parse("2026-05-22T00:00:00Z");

    when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(adminAccount("admin-user")));
    when(pmsUserLibraryStore.findPlaylists("target-user")).thenReturn(List.of());
    when(emsTrackRepository.findAllById(any())).thenReturn(List.of(
        emsTrack(318279L, "tidal", "189033560", unavailableEmsFeatures())
    ));
    eventStore.add(emsEvent("target-user", "play_completed", 1.0d, null, "ems-track:318279", now));

    AudioFeatureCompletionService service = new AudioFeatureCompletionService(
        authAccountStore,
        pmsUserLibraryStore,
        Optional.of(emsTrackRepository),
        jobStore,
        Optional.empty(),
        Optional.of(eventStore),
        new EventSignalWeights()
    );

    AudioFeatureCompletionService.EnqueueCompletionResult result = service.enqueuePositiveEventAudioFeatures(
        "admin-user",
        "target-user",
        "ems",
        500,
        10
    );

    assertThat(result.enqueuedJobCount()).isEqualTo(1);
    assertThat(result.jobs()).singleElement().satisfies(job ->
        assertThat(job.trackId()).isEqualTo("318279")
    );
}
```

- [ ] **Step 4: Add failing test for malformed and negative EMS events**

Add this test after the previous test:

```java
@Test
void shouldIgnoreMalformedAndNegativeEmsPositiveEventReferences() {
    AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
    PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
    EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
    InMemoryJobStore jobStore = new InMemoryJobStore();
    InMemoryEventStore eventStore = new InMemoryEventStore();
    Instant now = Instant.parse("2026-05-22T00:00:00Z");

    when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(adminAccount("admin-user")));
    when(pmsUserLibraryStore.findPlaylists("target-user")).thenReturn(List.of());
    eventStore.add(emsEvent("target-user", "play_completed", 1.0d, "ems-track:not-a-number", "ems-track:not-a-number", now));
    eventStore.add(emsEvent("target-user", "skip_next", -0.25d, "ems-track:318283", "ems-track:318283", now.plusSeconds(1)));

    AudioFeatureCompletionService service = new AudioFeatureCompletionService(
        authAccountStore,
        pmsUserLibraryStore,
        Optional.of(emsTrackRepository),
        jobStore,
        Optional.empty(),
        Optional.of(eventStore),
        new EventSignalWeights()
    );

    AudioFeatureCompletionService.EnqueueCompletionResult result = service.enqueuePositiveEventAudioFeatures(
        "admin-user",
        "target-user",
        "ems",
        500,
        10
    );

    assertThat(result.scannedTrackCount()).isZero();
    assertThat(result.enqueuedJobCount()).isZero();
    assertThat(result.jobs()).isEmpty();
    verifyNoInteractions(emsTrackRepository);
}
```

- [ ] **Step 5: Add failing test for complete EMS feature skip**

Add this test after the previous test:

```java
@Test
void shouldSkipCompleteEmsPositiveEventTracks() {
    AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
    PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
    EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
    InMemoryJobStore jobStore = new InMemoryJobStore();
    InMemoryEventStore eventStore = new InMemoryEventStore();
    Instant now = Instant.parse("2026-05-22T00:00:00Z");

    when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(adminAccount("admin-user")));
    when(pmsUserLibraryStore.findPlaylists("target-user")).thenReturn(List.of());
    when(emsTrackRepository.findAllById(any())).thenReturn(List.of(
        emsTrack(318283L, "tidal", "194088149", completeEmsFeatures())
    ));
    eventStore.add(emsEvent("target-user", "play_completed", 1.0d, "ems-track:318283", "ems-track:318283", now));

    AudioFeatureCompletionService service = new AudioFeatureCompletionService(
        authAccountStore,
        pmsUserLibraryStore,
        Optional.of(emsTrackRepository),
        jobStore,
        Optional.empty(),
        Optional.of(eventStore),
        new EventSignalWeights()
    );

    AudioFeatureCompletionService.EnqueueCompletionResult result = service.enqueuePositiveEventAudioFeatures(
        "admin-user",
        "target-user",
        "ems",
        500,
        10
    );

    assertThat(result.scannedTrackCount()).isEqualTo(1);
    assertThat(result.enqueuedJobCount()).isZero();
    assertThat(result.jobs()).isEmpty();
}
```

- [ ] **Step 6: Add failing test for `track_scope` selection**

Add this test after the previous test:

```java
@Test
void shouldRespectPositiveEventTrackScopeSelection() {
    AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
    PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
    EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
    InMemoryJobStore jobStore = new InMemoryJobStore();
    InMemoryEventStore eventStore = new InMemoryEventStore();
    Instant now = Instant.parse("2026-05-22T00:00:00Z");

    when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(adminAccount("admin-user")));
    when(pmsUserLibraryStore.findPlaylists("target-user")).thenReturn(List.of(new PmsUserLibraryStore.LibraryPlaylistState(
        "target-user",
        "playlist-001",
        "external-playlist-001",
        "Target Playlist",
        "tidal",
        "curator",
        null,
        null,
        null,
        null,
        now,
        List.of(pmsTrack("pms-track-positive-missing", PmsTrackAudioFeatures.unresolved()))
    )));
    when(emsTrackRepository.findAllById(any())).thenReturn(List.of(
        emsTrack(318283L, "tidal", "194088149", unavailableEmsFeatures())
    ));
    eventStore.add(event("target-user", "play_completed", 1.0d, "pms-track-positive-missing", now.plusSeconds(1)));
    eventStore.add(emsEvent("target-user", "play_completed", 1.0d, "ems-track:318283", "ems-track:318283", now));

    AudioFeatureCompletionService service = new AudioFeatureCompletionService(
        authAccountStore,
        pmsUserLibraryStore,
        Optional.of(emsTrackRepository),
        jobStore,
        Optional.empty(),
        Optional.of(eventStore),
        new EventSignalWeights()
    );

    AudioFeatureCompletionService.EnqueueCompletionResult pmsOnly = service.enqueuePositiveEventAudioFeatures(
        "admin-user",
        "target-user",
        "pms",
        500,
        10
    );
    AudioFeatureCompletionService.EnqueueCompletionResult emsOnly = service.enqueuePositiveEventAudioFeatures(
        "admin-user",
        "target-user",
        "ems",
        500,
        10
    );

    assertThat(pmsOnly.scope()).isEqualTo("pms_positive_events");
    assertThat(pmsOnly.jobs()).singleElement().satisfies(job ->
        assertThat(job.trackScope()).isEqualTo("pms_user_track")
    );
    assertThat(emsOnly.scope()).isEqualTo("ems_positive_events");
    assertThat(emsOnly.jobs()).singleElement().satisfies(job ->
        assertThat(job.trackScope()).isEqualTo("ems_collected_track")
    );
}
```

- [ ] **Step 7: Add EMS test helpers**

Add these helper methods near the existing `event(...)` and feature helpers:

```java
private EmsTrackAudioFeatures completeEmsFeatures() {
    return new EmsTrackAudioFeatures(
        "tidal-complete",
        "reccobeats_isrc_match",
        true,
        null,
        null,
        null,
        "audio_features",
        180000,
        1,
        1,
        4,
        0.1d,
        0.7d,
        0.8d,
        0.0d,
        0.1d,
        -6.0d,
        0.04d,
        120.0d,
        0.6d,
        Instant.parse("2026-05-20T00:00:00Z")
    );
}

private UserMusicEventStore.StoredEvent emsEvent(
    String userId,
    String eventType,
    Double eventWeight,
    String trackId,
    String itemId,
    Instant occurredAt
) {
    return new UserMusicEventStore.StoredEvent(
        null,
        userId,
        eventType,
        eventWeight,
        "player",
        "tidal",
        "tidal",
        itemId,
        "track",
        trackId,
        null,
        trackId == null ? null : trackId.substring("ems-track:".length()),
        itemId == null ? null : "tidal:track:" + itemId.substring("ems-track:".length()),
        "EMS Track",
        "EMS Artist",
        "EMS Album",
        null,
        180000,
        180000,
        1.0d,
        null,
        1.0d,
        occurredAt,
        occurredAt
    );
}
```

- [ ] **Step 8: Run service test and confirm RED**

Run:

```bash
cd /srv/my-forever-music/services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionServiceTest
```

Expected: compile fails because `enqueuePositiveEventAudioFeatures` does not yet accept `trackScope`, or tests fail because EMS positive events are ignored.

---

### Task 2: Implement Positive EMS Event Queueing

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionService.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionServiceTest.java`

- [ ] **Step 1: Add a compatibility overload and new signature**

Replace the current `enqueuePositiveEventAudioFeatures(String, String, int, int)` method signature with an overload pair:

```java
public EnqueueCompletionResult enqueuePositiveEventAudioFeatures(
    String adminUserId,
    String targetUserId,
    int eventLimit,
    int limit
) {
    return enqueuePositiveEventAudioFeatures(adminUserId, targetUserId, "pms", eventLimit, limit);
}

public EnqueueCompletionResult enqueuePositiveEventAudioFeatures(
    String adminUserId,
    String targetUserId,
    String trackScope,
    int eventLimit,
    int limit
) {
    assertAdmin(adminUserId);
    UserMusicEventStore resolvedEventStore = eventStore.orElseThrow(() -> new ResponseStatusException(
        HttpStatus.PRECONDITION_FAILED,
        "User music event store is not available for positive audio feature enqueue."
    ));
    String resolvedTargetUserId = targetUserId == null || targetUserId.isBlank()
        ? adminUserId
        : targetUserId.trim();
    String resolvedTrackScope = normalizePositiveEventTrackScope(trackScope);
    int resolvedEventLimit = Math.max(1, Math.min(2_000, eventLimit <= 0 ? 500 : eventLimit));
    int resolvedLimit = normalizeLimit(limit <= 0 ? 20 : limit);
    Instant now = Instant.now();
    boolean includePms = includesPmsPositiveEvents(resolvedTrackScope);
    boolean includeEms = includesEmsPositiveEvents(resolvedTrackScope) && emsTrackRepository.isPresent();
    Map<String, LibraryTrackState> pmsTracksById = includePms
        ? pmsTracksByTrackId(resolvedTargetUserId)
        : Map.of();
    Map<Long, EmsCollectedTrackEntity> emsTracksById = includeEms
        ? positiveEmsTracksById(resolvedEventStore, resolvedTargetUserId, resolvedEventLimit)
        : Map.of();
    Counter counter = new Counter();
    List<AudioFeatureCompletionJobStore.StoredJob> jobs = new ArrayList<>();
    List<PositiveEventReference> references = positiveEventReferences(
        resolvedEventStore,
        resolvedTargetUserId,
        resolvedEventLimit,
        includePms,
        includeEms
    );

    for (PositiveEventReference reference : references) {
        if (counter.enqueuedJobCount >= resolvedLimit) {
            break;
        }
        if ("pms_user_track".equals(reference.trackScope())) {
            enqueuePositivePmsReference(resolvedTargetUserId, pmsTracksById, reference, now, counter, jobs);
        } else if ("ems_collected_track".equals(reference.trackScope())) {
            enqueuePositiveEmsReference(emsTracksById, reference, now, counter, jobs);
        }
    }

    return new EnqueueCompletionResult(
        resolvedTargetUserId,
        positiveEventResultScope(resolvedTrackScope),
        counter.scannedTrackCount,
        counter.enqueuedJobCount,
        counter.skippedExistingJobCount,
        jobs
    );
}
```

- [ ] **Step 2: Add positive-event queue helpers**

Add these helper methods before `positiveEventTrackIds(...)`. The old `positiveEventTrackIds(...)` method will be removed in Step 3.

```java
private void enqueuePositivePmsReference(
    String targetUserId,
    Map<String, LibraryTrackState> pmsTracksById,
    PositiveEventReference reference,
    Instant now,
    Counter counter,
    List<AudioFeatureCompletionJobStore.StoredJob> jobs
) {
    counter.scannedTrackCount++;
    LibraryTrackState track = pmsTracksById.get(reference.trackId());
    if (track == null || (track.audioFeatures() != null && track.audioFeatures().isComplete())) {
        return;
    }
    enqueue(
        new AudioFeatureCompletionJobStore.Draft(
            "pms_user_track",
            track.trackId(),
            targetUserId,
            130,
            POSITIVE_AUDIO_TASTE_RETRY_REASON,
            now
        ),
        counter,
        jobs
    );
}

private void enqueuePositiveEmsReference(
    Map<Long, EmsCollectedTrackEntity> emsTracksById,
    PositiveEventReference reference,
    Instant now,
    Counter counter,
    List<AudioFeatureCompletionJobStore.StoredJob> jobs
) {
    counter.scannedTrackCount++;
    Long emsTrackId = parseLong(reference.trackId());
    EmsCollectedTrackEntity track = emsTrackId == null ? null : emsTracksById.get(emsTrackId);
    if (track == null || hasCompleteAudioFeatures(track.getAudioFeatures())) {
        return;
    }
    enqueue(
        new AudioFeatureCompletionJobStore.Draft(
            "ems_collected_track",
            String.valueOf(track.getId()),
            null,
            130,
            POSITIVE_AUDIO_TASTE_RETRY_REASON,
            now
        ),
        counter,
        jobs
    );
}

private Map<Long, EmsCollectedTrackEntity> positiveEmsTracksById(
    UserMusicEventStore resolvedEventStore,
    String targetUserId,
    int eventLimit
) {
    if (emsTrackRepository.isEmpty()) {
        return Map.of();
    }
    Map<Long, Boolean> ids = new LinkedHashMap<>();
    for (UserMusicEventStore.StoredEvent event : resolvedEventStore.findRecentByUserId(targetUserId, eventLimit)) {
        if (event == null || eventWeight(event) <= 0.0d) {
            continue;
        }
        parseEmsTrackId(featureLookupTrackId(event)).ifPresent(id -> ids.putIfAbsent(id, true));
    }
    if (ids.isEmpty()) {
        return Map.of();
    }
    Map<Long, EmsCollectedTrackEntity> result = new LinkedHashMap<>();
    for (EmsCollectedTrackEntity track : emsTrackRepository.get().findAllById(ids.keySet())) {
        if (track != null && track.getId() != null) {
            result.putIfAbsent(track.getId(), track);
        }
    }
    return result;
}
```

- [ ] **Step 3: Replace old positive event id helper**

Delete the existing `positiveEventTrackIds(...)` method and add:

```java
private List<PositiveEventReference> positiveEventReferences(
    UserMusicEventStore resolvedEventStore,
    String targetUserId,
    int eventLimit,
    boolean includePms,
    boolean includeEms
) {
    Map<String, PositiveEventReference> seen = new LinkedHashMap<>();
    for (UserMusicEventStore.StoredEvent event : resolvedEventStore.findRecentByUserId(targetUserId, eventLimit)) {
        if (event == null || eventWeight(event) <= 0.0d) {
            continue;
        }
        if (includeEms) {
            Optional<Long> emsId = parseEmsTrackId(featureLookupTrackId(event));
            if (emsId.isPresent()) {
                PositiveEventReference reference = new PositiveEventReference(
                    "ems_collected_track",
                    String.valueOf(emsId.get())
                );
                seen.putIfAbsent(reference.trackScope() + ":" + reference.trackId(), reference);
                continue;
            }
        }
        if (includePms
            && event.trackId() != null
            && !event.trackId().isBlank()
            && parseEmsTrackId(event.trackId()).isEmpty()) {
            PositiveEventReference reference = new PositiveEventReference(
                "pms_user_track",
                event.trackId().trim()
            );
            seen.putIfAbsent(reference.trackScope() + ":" + reference.trackId(), reference);
        }
    }
    return new ArrayList<>(seen.values());
}

private double eventWeight(UserMusicEventStore.StoredEvent event) {
    return event.eventWeight() == null
        ? eventSignalWeights.findWeight(event.eventType()).orElse(0.0d)
        : event.eventWeight();
}

private String featureLookupTrackId(UserMusicEventStore.StoredEvent event) {
    if (event.trackId() != null && !event.trackId().isBlank()) {
        return event.trackId().trim();
    }
    if (event.itemId() != null && !event.itemId().isBlank()) {
        return event.itemId().trim();
    }
    return null;
}

private Optional<Long> parseEmsTrackId(String value) {
    if (value == null) {
        return Optional.empty();
    }
    String normalized = value.trim();
    if (!normalized.startsWith("ems-track:")) {
        return Optional.empty();
    }
    String rawId = normalized.substring("ems-track:".length()).trim();
    if (rawId.isEmpty()) {
        return Optional.empty();
    }
    try {
        long parsed = Long.parseLong(rawId);
        return parsed > 0 ? Optional.of(parsed) : Optional.empty();
    } catch (NumberFormatException ignored) {
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Add scope helpers and record**

Add these helpers near `normalizeScope(...)`:

```java
private String normalizePositiveEventTrackScope(String trackScope) {
    String value = trackScope == null || trackScope.isBlank() ? "all" : trackScope.trim().toLowerCase(Locale.ROOT);
    if ("all".equals(value) || "pms".equals(value) || "ems".equals(value)) {
        return value;
    }
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "track_scope must be one of: all, pms, ems.");
}

private boolean includesPmsPositiveEvents(String trackScope) {
    return "all".equals(trackScope) || "pms".equals(trackScope);
}

private boolean includesEmsPositiveEvents(String trackScope) {
    return "all".equals(trackScope) || "ems".equals(trackScope);
}

private String positiveEventResultScope(String trackScope) {
    return switch (trackScope) {
        case "pms" -> "pms_positive_events";
        case "ems" -> "ems_positive_events";
        default -> "positive_events";
    };
}
```

Add this private record near `Counter`:

```java
private record PositiveEventReference(String trackScope, String trackId) {
}
```

- [ ] **Step 5: Run service test and confirm GREEN**

Run:

```bash
cd /srv/my-forever-music/services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionServiceTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit Task 2**

Run:

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionServiceTest.java
git commit -m "feat: queue positive ems playback audio features"
```

---

### Task 3: Expose `track_scope` Through Admin API

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/AudioFeatureCompletionAdminController.java`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/AudioFeatureCompletionAdminControllerWebMvcTest.java`

- [ ] **Step 1: Add RED controller test for `track_scope`**

In `AudioFeatureCompletionAdminControllerWebMvcTest.java`, replace `shouldEnqueuePositiveEventAudioFeatureJobs()` stubbing with the five-argument service call:

```java
when(completionService.enqueuePositiveEventAudioFeatures(
    eq("admin-user"),
    eq("target-user"),
    eq("ems"),
    eq(500),
    eq(10)
)).thenReturn(new AudioFeatureCompletionService.EnqueueCompletionResult(
    "target-user",
    "ems_positive_events",
    3,
    1,
    2,
    List.of(positiveAudioTasteRetryJob())
));
```

In the mock request, add:

```java
.param("track_scope", "ems")
```

Change the expected scope assertion to:

```java
.andExpect(jsonPath("$.scope").value("ems_positive_events"))
```

- [ ] **Step 2: Run controller test and confirm RED**

Run:

```bash
cd /srv/my-forever-music/services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.presentation.AudioFeatureCompletionAdminControllerWebMvcTest
```

Expected: compile or verification failure because the controller does not pass `track_scope` yet.

- [ ] **Step 3: Add controller parameter**

In `AudioFeatureCompletionAdminController.enqueuePositiveEventAudioFeatures`, change the method to:

```java
@Operation(summary = "Enqueue positive-event PMS/EMS tracks missing audio features")
@PostMapping("/enqueue-positive-events")
public EnqueueCompletionResponse enqueuePositiveEventAudioFeatures(
    @RequestParam("user_id") String userId,
    @RequestParam(value = "target_user_id", required = false) String targetUserId,
    @RequestParam(value = "track_scope", defaultValue = "all") String trackScope,
    @RequestParam(value = "event_limit", defaultValue = "500") int eventLimit,
    @RequestParam(value = "limit", defaultValue = "20") int limit
) {
    return EnqueueCompletionResponse.from(completionService.enqueuePositiveEventAudioFeatures(
        userId,
        targetUserId,
        trackScope,
        eventLimit,
        limit
    ));
}
```

- [ ] **Step 4: Run controller test and confirm GREEN**

Run:

```bash
cd /srv/my-forever-music/services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.presentation.AudioFeatureCompletionAdminControllerWebMvcTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit Task 3**

Run:

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/AudioFeatureCompletionAdminController.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/AudioFeatureCompletionAdminControllerWebMvcTest.java
git commit -m "feat: expose positive event completion scope"
```

---

### Task 4: Update Backfill Script And API Documentation

**Files:**
- Modify: `infra/scripts/run-audio-feature-completion-backfill.sh`
- Modify: `docs/api/AUDIO_FEATURE_COMPLETION_ADMIN_API.md`

- [ ] **Step 1: Update script variables and usage**

In `infra/scripts/run-audio-feature-completion-backfill.sh`, add:

```bash
POSITIVE_SCOPE="${POSITIVE_SCOPE:-all}"
```

Add usage text under `--positive-events`:

```text
  --positive-scope SCOPE
                        positive event track scope: all, pms, or ems. Default: ${POSITIVE_SCOPE}
```

- [ ] **Step 2: Parse and validate `--positive-scope`**

Add this case arm in the argument parser:

```bash
      --positive-scope)
        POSITIVE_SCOPE="${2:?--positive-scope requires a value}"
        shift 2
        ;;
```

Add validation near the existing `scope` validation:

```bash
case "$POSITIVE_SCOPE" in
  all|pms|ems) ;;
  *) fail "--positive-scope must be all, pms, or ems" ;;
esac
```

Add startup log output when positive events are enabled:

```bash
log "positive_events=true, positive_scope=${POSITIVE_SCOPE}, positive_event_limit=${POSITIVE_EVENT_LIMIT}, positive_limit=${POSITIVE_LIMIT}"
```

- [ ] **Step 3: Pass `track_scope` to the endpoint**

In both dry-run and normal positive enqueue calls, add:

```bash
"track_scope=${POSITIVE_SCOPE}" \
```

Change the normal log line from:

```bash
log "enqueue positive-event PMS tracks"
```

to:

```bash
log "enqueue positive-event tracks"
```

- [ ] **Step 4: Update API documentation**

In `docs/api/AUDIO_FEATURE_COMPLETION_ADMIN_API.md`, change section `5.5` title to:

```markdown
## 5.5. Enqueue positive-event PMS/EMS tracks
```

Replace the current description paragraph with:

```markdown
Audio Taste 적용 gate와 EMS playback learning loop를 빠르게 넘기기 위한 운영 endpoint입니다. 최근 `user_music_event` 중 positive weight를 가진 PMS track과 `ems-track:<id>` playback reference를 우선 스캔하고, 아직 complete audio feature가 없는 트랙만 `positive_audio_taste_retry` job으로 큐잉합니다. Worker는 이 reason을 ReccoBeats 반복 조회 없이 Last.fm/LLM inference 경로로 처리합니다.
```

Add this query parameter row after `target_user_id`:

```markdown
| `track_scope` | no | `all` | `all`, `pms`, `ems` 중 하나. `ems`는 `track_id` 또는 `item_id`의 `ems-track:<id>` reference를 `ems_collected_track` job으로 큐잉 |
```

Change the response sentence to:

```markdown
Response는 `enqueue`와 동일한 shape이며 `scope`는 `positive_events`, `pms_positive_events`, `ems_positive_events` 중 하나입니다.
```

Add this EMS-focused example after the existing example:

```bash
./infra/scripts/run-audio-feature-completion-backfill.sh \
  --admin-user user-... \
  --target-user user-... \
  --positive-events \
  --positive-scope ems \
  --positive-limit 10 \
  --process-limit 5 \
  --rounds 2
```

- [ ] **Step 5: Run shell syntax and focused tests**

Run:

```bash
cd /srv/my-forever-music
bash -n infra/scripts/run-audio-feature-completion-backfill.sh
cd /srv/my-forever-music/services/api
./gradlew test \
  --tests io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionServiceTest \
  --tests io.myforevermusic.api.modules.recommendation.presentation.AudioFeatureCompletionAdminControllerWebMvcTest
```

Expected: `bash -n` exits `0` and Gradle prints `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit Task 4**

Run:

```bash
git add infra/scripts/run-audio-feature-completion-backfill.sh docs/api/AUDIO_FEATURE_COMPLETION_ADMIN_API.md
git commit -m "docs: document positive ems feature queue operation"
```

---

### Task 5: Final Verification And Operational Smoke

**Files:**
- No repository file changes expected.

- [ ] **Step 1: Run full API tests**

Run:

```bash
cd /srv/my-forever-music/services/api
./gradlew test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run whitespace check**

Run:

```bash
cd /srv/my-forever-music
git diff --check
```

Expected: no output and exit code `0`.

- [ ] **Step 3: Commit any remaining tracked changes**

If `git status --short` shows only intended tracked changes from this plan, commit them. Do not stage unrelated user changes in:

- `apps/web/scripts/playback-regression-harness.mjs`
- `services/api/src/main/java/io/myforevermusic/api/modules/platform/presentation/TidalPlaybackDiagnosticsController.java`
- `.idea/`
- `.superpowers/`
- `hud-theme/`

- [ ] **Step 4: Deploy verification command after restart**

After server restart, run:

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

Expected: the script logs `positive_scope=ems`, the positive enqueue call scans EMS positive references, and processing either completes jobs or records a new inference-path unresolved reason for those EMS tracks.
