# EMS Playback Audio Taste Input Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `/gms-playlists` EMS playback completion events contribute to the audio taste profile when the referenced EMS track has usable audio features.

**Architecture:** Extend `AudioTasteProfileService` so it builds the user feature set from PMS library rows plus EMS rows referenced by recent positive playback events. Keep event storage unchanged by using the existing `ems-track:<id>` event id as the join key and `EmsCollectedTrackRepository` only as an optional read dependency.

**Tech Stack:** Spring Boot 3.5, Java 21, Gradle, JUnit 5, Mockito, AssertJ, PostgreSQL/JPA repository interfaces.

---

## File Structure

- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileService.java`
  - Add optional EMS repository dependency.
  - Build a combined user feature map from PMS library rows and positive EMS playback rows.
  - Parse only `ems-track:<numeric-id>` values.
  - Keep missing/malformed EMS rows as no-op.
- Modify `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileServiceTest.java`
  - Add focused tests for EMS playback events, malformed EMS ids, negative EMS events, and coverage semantics.
- Review `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`
  - Add one short note that EMS playback events can now become audio taste input when EMS audio features are ready.

---

### Task 1: Add Failing Tests For EMS Playback Audio Taste Input

**Files:**
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileServiceTest.java`

- [ ] **Step 1: Add test imports**

Add these imports near the top of `AudioTasteProfileServiceTest.java`:

```java
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsTrackAudioFeatures;
import java.util.Optional;
import org.springframework.test.util.ReflectionTestUtils;
```

- [ ] **Step 2: Add failing test for positive EMS playback contribution**

Append this test before the helper methods in `AudioTasteProfileServiceTest.java`:

```java
@Test
void shouldBuildPositiveCentroidFromEmsPlaybackCompletion() {
    InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
    InMemoryTrackAudioFeatureEvidenceStore evidenceStore = new InMemoryTrackAudioFeatureEvidenceStore();
    PmsUserLibraryStore libraryStore = libraryWithTracks(0, index ->
        track("unused-" + index, "Unused " + index, "Unused Artist", "spotify", features(0.50d, 0.50d, 0.50d))
    );
    EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
    when(emsTrackRepository.findAllById(any())).thenReturn(List.of(emsTrack(
        318283L,
        "Magic",
        "TOMORROW X TOGETHER",
        "tidal",
        emsFeatures(0.76d, 0.66d, 0.82d)
    )));
    eventStore.save(emsEvent("user-1", "play_completed", "ems-track:318283", 1.0d));

    AudioTasteProfileService.Profile profile = new AudioTasteProfileService(
        libraryStore,
        eventStore,
        evidenceStore,
        new EventSignalWeights(),
        Optional.of(emsTrackRepository),
        1,
        0.0d
    ).recompute("user-1", 100);

    assertThat(profile.positiveTrackCount()).isEqualTo(1);
    assertThat(profile.coverage().trackCount()).isEqualTo(1);
    assertThat(profile.coverage().usableTrackCount()).isEqualTo(1);
    assertThat(profile.positiveCentroid().energy())
        .isCloseTo(0.76d, org.assertj.core.data.Offset.offset(1e-4));
    assertThat(profile.positiveCentroid().valence())
        .isCloseTo(0.66d, org.assertj.core.data.Offset.offset(1e-4));
}
```

- [ ] **Step 3: Add failing test for `item_id` fallback**

Append this test after the previous test:

```java
@Test
void shouldUseEmsItemIdWhenTrackIdIsMissing() {
    InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
    InMemoryTrackAudioFeatureEvidenceStore evidenceStore = new InMemoryTrackAudioFeatureEvidenceStore();
    PmsUserLibraryStore libraryStore = libraryWithTracks(0, index ->
        track("unused-" + index, "Unused " + index, "Unused Artist", "spotify", features(0.50d, 0.50d, 0.50d))
    );
    EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
    when(emsTrackRepository.findAllById(any())).thenReturn(List.of(emsTrack(
        318284L,
        "However",
        "10CM",
        "tidal",
        emsFeatures(0.62d, 0.58d, 0.60d)
    )));
    eventStore.save(emsItemOnlyEvent("user-1", "play_completed", "ems-track:318284", 1.0d));

    AudioTasteProfileService.Profile profile = new AudioTasteProfileService(
        libraryStore,
        eventStore,
        evidenceStore,
        new EventSignalWeights(),
        Optional.of(emsTrackRepository),
        1,
        0.0d
    ).recompute("user-1", 100);

    assertThat(profile.positiveTrackCount()).isEqualTo(1);
    assertThat(profile.coverage().trackCount()).isEqualTo(1);
    assertThat(profile.positiveCentroid().energy())
        .isCloseTo(0.62d, org.assertj.core.data.Offset.offset(1e-4));
}
```

- [ ] **Step 4: Add failing test for ignored EMS ids and negative events**

Append this test after the previous test:

```java
@Test
void shouldIgnoreMalformedAndNegativeEmsEventsForAudioTasteInput() {
    InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
    InMemoryTrackAudioFeatureEvidenceStore evidenceStore = new InMemoryTrackAudioFeatureEvidenceStore();
    PmsUserLibraryStore libraryStore = libraryWithTracks(0, index ->
        track("unused-" + index, "Unused " + index, "Unused Artist", "spotify", features(0.50d, 0.50d, 0.50d))
    );
    EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
    eventStore.save(emsEvent("user-1", "play_completed", "ems-track:not-a-number", 1.0d));
    eventStore.save(emsEvent("user-1", "skip_next", "ems-track:318283", -0.25d));

    AudioTasteProfileService.Profile profile = new AudioTasteProfileService(
        libraryStore,
        eventStore,
        evidenceStore,
        new EventSignalWeights(),
        Optional.of(emsTrackRepository),
        1,
        0.0d
    ).recompute("user-1", 100);

    assertThat(profile.positiveTrackCount()).isZero();
    assertThat(profile.coverage().trackCount()).isZero();
    verifyNoInteractions(emsTrackRepository);
}
```

- [ ] **Step 5: Add failing test for coverage denominator**

Append this test after the previous test:

```java
@Test
void shouldCountOnlyReferencedEmsRowsInAudioTasteCoverage() {
    InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
    InMemoryTrackAudioFeatureEvidenceStore evidenceStore = new InMemoryTrackAudioFeatureEvidenceStore();
    PmsUserLibraryStore libraryStore = libraryWithTracks(1, index ->
        track("pms-ready-" + index, "PMS Ready " + index, "PMS Artist", "spotify", features(0.70d, 0.60d, 0.72d))
    );
    EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
    when(emsTrackRepository.findAllById(any())).thenReturn(List.of(emsTrack(
        318283L,
        "Magic",
        "TOMORROW X TOGETHER",
        "tidal",
        emsFeatures(0.76d, 0.66d, 0.82d)
    )));
    eventStore.save(event("user-1", "track_saved", "pms-ready-1", 2.0d));
    eventStore.save(emsEvent("user-1", "play_completed", "ems-track:318283", 1.0d));

    AudioTasteProfileService.Profile profile = new AudioTasteProfileService(
        libraryStore,
        eventStore,
        evidenceStore,
        new EventSignalWeights(),
        Optional.of(emsTrackRepository),
        1,
        0.0d
    ).recompute("user-1", 100);

    assertThat(profile.coverage().trackCount()).isEqualTo(2);
    assertThat(profile.coverage().usableTrackCount()).isEqualTo(2);
    assertThat(profile.coverage().featureReadyRatio()).isEqualTo(1.0d);
    assertThat(profile.positiveTrackCount()).isEqualTo(2);
}
```

- [ ] **Step 6: Add EMS test helpers**

Append these helpers near the existing `event(...)` and `features(...)` helper methods:

```java
private UserMusicEventStore.EventDraft emsEvent(String userId, String type, String trackId, double weight) {
    return new UserMusicEventStore.EventDraft(
        userId,
        type,
        weight,
        "player",
        "tidal",
        "tidal",
        trackId,
        "track",
        trackId,
        null,
        trackId.substring("ems-track:".length()),
        "tidal:track:" + trackId.substring("ems-track:".length()),
        "EMS Title",
        "EMS Artist",
        "EMS Album",
        null,
        180000,
        180000,
        1.0d,
        null,
        1.0d,
        Instant.parse("2026-05-21T00:00:00Z")
    );
}

private UserMusicEventStore.EventDraft emsItemOnlyEvent(String userId, String type, String itemId, double weight) {
    return new UserMusicEventStore.EventDraft(
        userId,
        type,
        weight,
        "player",
        "tidal",
        "tidal",
        itemId,
        "track",
        null,
        null,
        itemId.substring("ems-track:".length()),
        "tidal:track:" + itemId.substring("ems-track:".length()),
        "EMS Title",
        "EMS Artist",
        "EMS Album",
        null,
        180000,
        180000,
        1.0d,
        null,
        1.0d,
        Instant.parse("2026-05-21T00:00:00Z")
    );
}

private EmsCollectedTrackEntity emsTrack(
    long id,
    String title,
    String artistName,
    String sourcePlatform,
    EmsTrackAudioFeatures audioFeatures
) {
    EmsCollectedTrackEntity track = new EmsCollectedTrackEntity(
        String.valueOf(id),
        title,
        artistName,
        sourcePlatform,
        null,
        "EMS Album",
        null,
        null,
        null,
        null,
        180000,
        "gms_playlist",
        Instant.parse("2026-05-21T00:00:00Z"),
        audioFeatures
    );
    ReflectionTestUtils.setField(track, "id", id);
    return track;
}

private EmsTrackAudioFeatures emsFeatures(double energy, double valence, double danceability) {
    return new EmsTrackAudioFeatures(
        "tidal-track",
        "reccobeats_lookup",
        true,
        null,
        null,
        null,
        "audio_features",
        180000,
        1,
        1,
        4,
        0.20d,
        danceability,
        energy,
        0.01d,
        0.12d,
        -8.0d,
        0.05d,
        120.0d,
        valence,
        Instant.parse("2026-05-21T00:00:00Z")
    );
}
```

- [ ] **Step 7: Run the focused test and confirm RED**

Run:

```bash
cd /srv/my-forever-music/services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileServiceTest
```

Expected: compile fails because `AudioTasteProfileService` does not yet have a constructor accepting `Optional<EmsCollectedTrackRepository>`, or tests fail because EMS rows are not collected.

---

### Task 2: Implement EMS Playback Feature Collection

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileService.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileServiceTest.java`

- [ ] **Step 1: Add imports and field**

Add these imports:

```java
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsTrackAudioFeatures;
import java.util.LinkedHashMap;
import java.util.Optional;
```

Add this field near the existing store fields:

```java
private final Optional<EmsCollectedTrackRepository> emsTrackRepository;
```

- [ ] **Step 2: Update constructors without breaking existing tests**

Replace the constructor block with this constructor set:

```java
public AudioTasteProfileService(
    PmsUserLibraryStore libraryStore,
    UserMusicEventStore eventStore,
    TrackAudioFeatureEvidenceStore evidenceStore,
    EventSignalWeights eventSignalWeights
) {
    this(
        libraryStore,
        eventStore,
        evidenceStore,
        eventSignalWeights,
        Optional.empty(),
        DEFAULT_MIN_POSITIVE_READY_TRACKS,
        DEFAULT_MIN_FEATURE_READY_RATIO,
        new AudioTasteModeService()
    );
}

@Autowired
public AudioTasteProfileService(
    PmsUserLibraryStore libraryStore,
    UserMusicEventStore eventStore,
    TrackAudioFeatureEvidenceStore evidenceStore,
    EventSignalWeights eventSignalWeights,
    Optional<EmsCollectedTrackRepository> emsTrackRepository,
    @Value("${app.recommendation.audio-taste.min-positive-ready-tracks:10}") int minPositiveReadyTracks,
    @Value("${app.recommendation.audio-taste.min-feature-ready-ratio:0.30}") double minFeatureReadyRatio,
    AudioTasteModeService audioTasteModeService
) {
    this.libraryStore = libraryStore;
    this.eventStore = eventStore;
    this.evidenceStore = evidenceStore;
    this.eventSignalWeights = eventSignalWeights;
    this.emsTrackRepository = emsTrackRepository == null ? Optional.empty() : emsTrackRepository;
    this.minPositiveReadyTracks = Math.max(1, minPositiveReadyTracks);
    this.minFeatureReadyRatio = Math.max(0.0d, Math.min(1.0d, minFeatureReadyRatio));
    this.audioTasteModeService = audioTasteModeService == null ? new AudioTasteModeService() : audioTasteModeService;
}

public AudioTasteProfileService(
    PmsUserLibraryStore libraryStore,
    UserMusicEventStore eventStore,
    TrackAudioFeatureEvidenceStore evidenceStore,
    EventSignalWeights eventSignalWeights,
    int minPositiveReadyTracks,
    double minFeatureReadyRatio
) {
    this(
        libraryStore,
        eventStore,
        evidenceStore,
        eventSignalWeights,
        Optional.empty(),
        minPositiveReadyTracks,
        minFeatureReadyRatio,
        new AudioTasteModeService()
    );
}

public AudioTasteProfileService(
    PmsUserLibraryStore libraryStore,
    UserMusicEventStore eventStore,
    TrackAudioFeatureEvidenceStore evidenceStore,
    EventSignalWeights eventSignalWeights,
    Optional<EmsCollectedTrackRepository> emsTrackRepository,
    int minPositiveReadyTracks,
    double minFeatureReadyRatio
) {
    this(
        libraryStore,
        eventStore,
        evidenceStore,
        eventSignalWeights,
        emsTrackRepository,
        minPositiveReadyTracks,
        minFeatureReadyRatio,
        new AudioTasteModeService()
    );
}
```

- [ ] **Step 3: Add shared event limit and event loading helpers**

Add these helpers before `collectPmsFeatures`:

```java
private int resolveEventLimit(Integer eventLimit) {
    return eventLimit == null ? DEFAULT_EVENT_LIMIT : Math.max(1, Math.min(2_000, eventLimit));
}

private List<UserMusicEventStore.StoredEvent> recentEvents(String userId, int eventLimit) {
    return eventStore.findRecentByUserId(userId, eventLimit)
        .stream()
        .sorted(Comparator.comparing(UserMusicEventStore.StoredEvent::occurredAt))
        .toList();
}

private Map<String, AudioTasteTrackFeature> collectUserFeatures(
    String userId,
    List<UserMusicEventStore.StoredEvent> events
) {
    Map<String, AudioTasteTrackFeature> result = collectPmsFeatures(userId);
    collectEmsEventFeatures(events).forEach(result::putIfAbsent);
    return result;
}
```

- [ ] **Step 4: Use combined feature collection in `recompute` and `dataset`**

Replace the top of `recompute` after `normalizedUserId` with:

```java
int resolvedLimit = resolveEventLimit(eventLimit);
List<UserMusicEventStore.StoredEvent> events = recentEvents(normalizedUserId, resolvedLimit);
Map<String, AudioTasteTrackFeature> featuresByTrackId = collectUserFeatures(normalizedUserId, events);
```

Replace the row collection in `dataset` with:

```java
String normalizedUserId = userId.trim();
int resolvedLimit = resolveEventLimit(eventLimit);
Profile profile = recompute(normalizedUserId, resolvedLimit);
List<AudioTasteTrackFeature> rows = collectUserFeatures(normalizedUserId, recentEvents(normalizedUserId, resolvedLimit))
    .values()
    .stream()
    .sorted(Comparator.comparing(AudioTasteTrackFeature::trackId))
    .toList();
return new Dataset("audio-taste-dataset-v1", normalizedUserId, profile, rows);
```

- [ ] **Step 5: Add EMS event feature collection helpers**

Add these methods after `collectPmsFeatures`:

```java
private Map<String, AudioTasteTrackFeature> collectEmsEventFeatures(
    List<UserMusicEventStore.StoredEvent> events
) {
    if (emsTrackRepository.isEmpty() || events == null || events.isEmpty()) {
        return Map.of();
    }
    Map<Long, String> eventTrackIdsByEmsId = new LinkedHashMap<>();
    for (UserMusicEventStore.StoredEvent event : events) {
        if (event == null || eventWeight(event) <= 0.0d) {
            continue;
        }
        String featureKey = featureLookupTrackId(event);
        Optional<Long> emsId = parseEmsTrackId(featureKey);
        emsId.ifPresent(id -> eventTrackIdsByEmsId.putIfAbsent(id, featureKey));
    }
    if (eventTrackIdsByEmsId.isEmpty()) {
        return Map.of();
    }
    Map<String, AudioTasteTrackFeature> result = new HashMap<>();
    for (EmsCollectedTrackEntity track : emsTrackRepository.get().findAllById(eventTrackIdsByEmsId.keySet())) {
        if (track == null || track.getId() == null) {
            continue;
        }
        String eventTrackId = eventTrackIdsByEmsId.get(track.getId());
        AudioTasteTrackFeature feature = toEmsAudioTasteFeature(track, eventTrackId);
        if (feature != null) {
            result.putIfAbsent(eventTrackId, feature);
        }
    }
    return result;
}

private String featureLookupTrackId(UserMusicEventStore.StoredEvent event) {
    if (event == null) {
        return null;
    }
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

private AudioTasteTrackFeature toEmsAudioTasteFeature(EmsCollectedTrackEntity track, String eventTrackId) {
    EmsTrackAudioFeatures audio = track.getAudioFeatures();
    if (audio == null || eventTrackId == null || eventTrackId.isBlank()) {
        return null;
    }
    String evidenceTrackId = String.valueOf(track.getId());
    EvidenceSummary evidence = evidenceSummary("ems_collected_track", evidenceTrackId);
    AudioTasteFeatureQuality.Quality quality = AudioTasteFeatureQuality.resolve(
        audio.getAudioFeatureSource(),
        audio.isAudioFeaturesFilled(),
        evidence.maxConfidence(),
        evidence.count()
    );
    return new AudioTasteTrackFeature(
        "ems_collected_track",
        eventTrackId,
        track.getTitle(),
        track.getArtistName(),
        track.getSourcePlatform(),
        audio.getAudioFeatureSource(),
        audio.isAudioFeaturesFilled(),
        quality.weight(),
        quality.tier(),
        audio.getAcousticness(),
        audio.getDanceability(),
        audio.getEnergy(),
        audio.getInstrumentalness(),
        audio.getLiveness(),
        audio.getSpeechiness(),
        audio.getTempo(),
        audio.getValence()
    );
}

private double eventWeight(UserMusicEventStore.StoredEvent event) {
    if (event == null) {
        return 0.0d;
    }
    return event.eventWeight() == null
        ? eventSignalWeights.findWeight(event.eventType()).orElse(0.0d)
        : event.eventWeight();
}
```

- [ ] **Step 6: Reuse event helpers in the centroid loop**

In `recompute`, replace this block:

```java
AudioTasteTrackFeature feature = featuresByTrackId.get(event.trackId());
```

with:

```java
AudioTasteTrackFeature feature = featuresByTrackId.get(featureLookupTrackId(event));
```

Then replace this block:

```java
double eventWeight = event.eventWeight() == null
    ? eventSignalWeights.findWeight(event.eventType()).orElse(0.0d)
    : event.eventWeight();
```

with:

```java
double eventWeight = eventWeight(event);
```

- [ ] **Step 7: Run focused tests and confirm GREEN**

Run:

```bash
cd /srv/my-forever-music/services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileServiceTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit Task 2**

Run:

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileServiceTest.java
git commit -m "feat: use ems playback in audio taste profile"
```

---

### Task 3: Document EMS Playback Audio Taste Input And Verify GMS Impact Boundary

**Files:**
- Modify: `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java`

- [ ] **Step 1: Update admin API documentation**

In `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`, add this short note near the audio taste profile section:

```markdown
### EMS playback events

`/gms-playlists` playback events with `track_id=ems-track:<id>` can contribute to the audio taste profile when the referenced `ems_collected_track` row has usable audio features. The audio taste dataset remains user-scoped: it includes PMS library rows plus EMS rows referenced by recent positive user events, not the full EMS acquisition pool.
```

- [ ] **Step 2: Run the focused GMS and audio taste tests**

Run:

```bash
cd /srv/my-forever-music/services/api
./gradlew test \
  --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileServiceTest \
  --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run whitespace check**

Run:

```bash
cd /srv/my-forever-music
git diff --check
```

Expected: no output and exit code `0`.

- [ ] **Step 4: Commit Task 3**

Run:

```bash
git add docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md
git commit -m "docs: document ems playback audio taste input"
```

---

### Task 4: Operational Verification After Deploy

**Files:**
- No repository file changes.

- [ ] **Step 1: Recompute audio taste profile for the current user**

Run on the Ubuntu host after restarting the API with the new build:

```bash
curl -sS -X POST \
  'http://127.0.0.1:8081/api/v1/recommendations/admin/audio-taste/recompute?user_id=user-1c7b2adc-f828-40f0-9da3-7b35d0d24457&target_user_id=user-1c7b2adc-f828-40f0-9da3-7b35d0d24457&event_limit=500' \
  | jq '{status, profile:{positive_track_count:.profile.positive_track_count, coverage:.profile.coverage, warnings:.profile.warnings}}'
```

Expected: `positive_track_count` becomes greater than `0` if at least one completed EMS track has usable audio features.

- [ ] **Step 2: Run GMS preview and inspect the gate**

Run:

```bash
curl -sS http://127.0.0.1:8081/api/v1/gms/recommendations/preview \
  -H 'Content-Type: application/json' \
  -d '{
    "user_id": "user-1c7b2adc-f828-40f0-9da3-7b35d0d24457",
    "mode": "gms",
    "mood": "focus",
    "energy_level": 3,
    "familiarity_bias": 3,
    "limit": 10,
    "include_explanations": true
  }' \
  | jq '{engine:.context.engine,warnings:.warnings, first_items:(.items[0:5] | map({rank,track_id,title,taste_mode_affinity,taste_mode_gate}))}'
```

Expected: `taste_mode_affinity` remains absent until the profile reaches heavy/taste-mode prerequisites, but `positive_track_count` is no longer blocked by EMS event id mismatch.

- [ ] **Step 3: Inspect latest audit log**

Run:

```bash
curl -sS \
  'http://127.0.0.1:8081/api/v1/recommendations/admin/audit-log/recent?user_id=user-1c7b2adc-f828-40f0-9da3-7b35d0d24457&target_user_id=user-1c7b2adc-f828-40f0-9da3-7b35d0d24457&limit=5' \
  | jq '.entries[0] | {model_version,item_count,taste_mode_gate_summary}'
```

Expected: audit output remains present and `apply_ranking_boost` stays controlled by configuration.
