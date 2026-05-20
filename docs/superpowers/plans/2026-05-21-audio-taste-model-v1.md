# Audio Taste Model v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an explainable Audio Taste centroid baseline that uses completed/low-confidence audio features to adjust GMS preview ranking safely.

**Architecture:** Spring API owns v1 serving. A pure recommendation application service builds user audio centroids from PMS library tracks and `user_music_event`, computes feature quality weights from `audio_feature_source`, `audio_features_filled`, and evidence confidence, and exposes admin dataset/profile endpoints. GMS preview applies a small audio boost after existing PMS/SASRec scoring and records model context through warnings/context/audit fields.

**Tech Stack:** Spring Boot 3.5, Java 21 records/services, existing PMS/Recommendation stores, JUnit 5, AssertJ, Spring MVC slice tests.

---

## File Structure

- Create `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteTrackFeature.java`
  - Provider-neutral immutable audio feature row used by dataset/profile/scoring code.
- Create `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteFeatureQuality.java`
  - Computes feature quality weight and source tier from source/filled/evidence.
- Create `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileService.java`
  - Builds user centroid profile and dataset summary from PMS library + recent events.
- Create `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteScoringService.java`
  - Scores candidate feature rows against a profile and creates explanation tokens.
- Create `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminController.java`
  - Admin endpoints for dataset/profile/recompute.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewService.java`
  - Inject audio services, apply conservative score boost, append warning/context.
- Test with:
  - `AudioTasteFeatureQualityTest`
  - `AudioTasteProfileServiceTest`
  - `AudioTasteScoringServiceTest`
  - `AudioTasteAdminControllerWebMvcTest`
  - update `GmsRecommendationPreviewServiceTest`

---

### Task 1: Feature Quality and Track Feature Value Objects

**Files:**
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteTrackFeature.java`
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteFeatureQuality.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteFeatureQualityTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AudioTasteFeatureQualityTest {

    @Test
    void shouldTreatFilledProviderFeaturesAsFullWeight() {
        AudioTasteFeatureQuality.Quality quality = AudioTasteFeatureQuality.resolve(
            "reccobeats_lookup",
            true,
            0.0d,
            0
        );

        assertThat(quality.tier()).isEqualTo("provider");
        assertThat(quality.weight()).isEqualTo(1.0d);
        assertThat(quality.usable()).isTrue();
    }

    @Test
    void shouldKeepLowConfidenceLlmAsWeakSignal() {
        AudioTasteFeatureQuality.Quality quality = AudioTasteFeatureQuality.resolve(
            "llm_search_low_confidence",
            false,
            0.56d,
            2
        );

        assertThat(quality.tier()).isEqualTo("llm_weak");
        assertThat(quality.weight()).isEqualTo(0.35d);
        assertThat(quality.usable()).isTrue();
    }

    @Test
    void shouldRejectMissingOrUnresolvedFeatures() {
        AudioTasteFeatureQuality.Quality quality = AudioTasteFeatureQuality.resolve(
            "unresolved",
            false,
            0.0d,
            0
        );

        assertThat(quality.tier()).isEqualTo("missing");
        assertThat(quality.weight()).isEqualTo(0.0d);
        assertThat(quality.usable()).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteFeatureQualityTest
```

Expected: FAIL with `cannot find symbol` for `AudioTasteFeatureQuality`.

- [ ] **Step 3: Add minimal implementation**

Create `AudioTasteTrackFeature.java`:

```java
package io.myforevermusic.api.modules.recommendation.application;

public record AudioTasteTrackFeature(
    String trackScope,
    String trackId,
    String title,
    String artistName,
    String sourcePlatform,
    String audioFeatureSource,
    boolean audioFeaturesFilled,
    double featureWeight,
    String featureTier,
    Double acousticness,
    Double danceability,
    Double energy,
    Double instrumentalness,
    Double liveness,
    Double speechiness,
    Double tempo,
    Double valence
) {
    public boolean hasRequiredFeatures() {
        return acousticness != null
            && danceability != null
            && energy != null
            && instrumentalness != null
            && liveness != null
            && speechiness != null
            && tempo != null
            && valence != null;
    }

    public boolean usable() {
        return featureWeight > 0.0d && hasRequiredFeatures();
    }
}
```

Create `AudioTasteFeatureQuality.java`:

```java
package io.myforevermusic.api.modules.recommendation.application;

import java.util.Locale;

public final class AudioTasteFeatureQuality {

    private AudioTasteFeatureQuality() {
    }

    public static Quality resolve(
        String audioFeatureSource,
        boolean audioFeaturesFilled,
        double maxEvidenceConfidence,
        long evidenceCount
    ) {
        String source = normalize(audioFeatureSource);
        if (audioFeaturesFilled) {
            if ("llm_search_inferred".equals(source)) {
                return new Quality("llm_accepted", 0.70d, true);
            }
            return new Quality("provider", 1.00d, true);
        }
        if ("llm_search_low_confidence".equals(source) && maxEvidenceConfidence >= 0.50d && evidenceCount > 0L) {
            return new Quality("llm_weak", 0.35d, true);
        }
        if (source.startsWith("lastfm_") && evidenceCount > 0L) {
            return new Quality("lastfm_partial", 0.25d, true);
        }
        return new Quality("missing", 0.0d, false);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record Quality(String tier, double weight, boolean usable) {
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteFeatureQualityTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteTrackFeature.java \
  services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteFeatureQuality.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteFeatureQualityTest.java
git commit -m "feat: add audio taste feature quality"
```

---

### Task 2: Audio Taste Profile Service

**Files:**
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileService.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileServiceTest.java`

- [ ] **Step 1: Write the failing profile tests**

```java
package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsTrackAudioFeatures;
import io.myforevermusic.api.modules.recommendation.infrastructure.local.InMemoryTrackAudioFeatureEvidenceStore;
import io.myforevermusic.api.modules.recommendation.infrastructure.local.InMemoryUserMusicEventStore;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AudioTasteProfileServiceTest {

    @Test
    void shouldBuildPositiveCentroidFromWeightedEvents() {
        InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
        InMemoryTrackAudioFeatureEvidenceStore evidenceStore = new InMemoryTrackAudioFeatureEvidenceStore();
        PmsUserLibraryStore libraryStore = new PmsUserLibraryStore() {
            @Override
            public List<LibraryPlaylistState> findPlaylists(String userId) {
                return List.of(new PmsUserLibraryStore.LibraryPlaylistState(
                    userId,
                    "playlist-1",
                    "external-1",
                    "Library",
                    "spotify",
                    "me",
                    "",
                    null,
                    null,
                    null,
                    Instant.parse("2026-05-21T00:00:00Z"),
                    IntStream.rangeClosed(1, 10)
                        .mapToObj(index -> track("track-" + index, "Bright " + index, 0.80d, 0.70d, 0.90d))
                        .toList()
                ));
            }

            @Override
            public List<LibraryPlaylistState> savePlaylists(String userId, List<LibraryPlaylistState> playlists) {
                return playlists;
            }
        };
        IntStream.rangeClosed(1, 10)
            .forEach(index -> eventStore.save(event("user-1", "track_saved", "track-" + index, 2.0d)));

        AudioTasteProfileService.Profile profile = new AudioTasteProfileService(
            libraryStore,
            eventStore,
            evidenceStore,
            new EventSignalWeights()
        ).recompute("user-1", 100);

        assertThat(profile.status()).isEqualTo("ok");
        assertThat(profile.positiveTrackCount()).isEqualTo(10);
        assertThat(profile.coverage().featureReadyRatio()).isEqualTo(1.0d);
        assertThat(profile.positiveCentroid().energy()).isCloseTo(0.80d, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(profile.positiveCentroid().valence()).isCloseTo(0.70d, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void shouldReturnInsufficientDataWhenReadyTrackCountIsBelowGate() {
        InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
        InMemoryTrackAudioFeatureEvidenceStore evidenceStore = new InMemoryTrackAudioFeatureEvidenceStore();
        PmsUserLibraryStore libraryStore = new PmsUserLibraryStore() {
            @Override
            public List<LibraryPlaylistState> findPlaylists(String userId) {
                return List.of();
            }

            @Override
            public List<LibraryPlaylistState> savePlaylists(String userId, List<LibraryPlaylistState> playlists) {
                return playlists;
            }
        };

        AudioTasteProfileService.Profile profile = new AudioTasteProfileService(
            libraryStore,
            eventStore,
            evidenceStore,
            new EventSignalWeights()
        ).recompute("user-1", 100);

        assertThat(profile.status()).isEqualTo("insufficient_data");
        assertThat(profile.audioTasteApplicable()).isFalse();
        assertThat(profile.warnings()).contains("Audio taste profile requires at least 10 positive feature-ready tracks.");
    }

    private UserMusicEventStore.EventDraft event(String userId, String type, String trackId, double weight) {
        return new UserMusicEventStore.EventDraft(
            userId,
            type,
            weight,
            "pms",
            "spotify",
            "spotify",
            trackId,
            "track",
            trackId,
            "playlist-1",
            trackId,
            null,
            "Title",
            "Artist",
            null,
            null,
            180000,
            null,
            null,
            null,
            1.0d,
            Instant.parse("2026-05-21T00:00:00Z")
        );
    }

    private PmsUserLibraryStore.LibraryTrackState track(String trackId, String title, double energy, double valence, double danceability) {
        return new PmsUserLibraryStore.LibraryTrackState(
            trackId,
            trackId,
            title,
            "Artist",
            "spotify",
            "pop",
            null,
            null,
            null,
            null,
            null,
            1,
            false,
            features(energy, valence, danceability)
        );
    }

    private PmsTrackAudioFeatures features(double energy, double valence, double danceability) {
        return new PmsTrackAudioFeatures(
            "spotify-track",
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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileServiceTest
```

Expected: FAIL with `cannot find symbol` for `AudioTasteProfileService`.

- [ ] **Step 3: Implement `AudioTasteProfileService`**

Use this class shape:

```java
package io.myforevermusic.api.modules.recommendation.application;

import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsTrackAudioFeatures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import org.springframework.stereotype.Service;

@Service
public class AudioTasteProfileService {

    private static final int DEFAULT_EVENT_LIMIT = 500;
    private static final int MIN_POSITIVE_READY_TRACKS = 10;

    private final PmsUserLibraryStore libraryStore;
    private final UserMusicEventStore eventStore;
    private final TrackAudioFeatureEvidenceStore evidenceStore;
    private final EventSignalWeights eventSignalWeights;

    public AudioTasteProfileService(
        PmsUserLibraryStore libraryStore,
        UserMusicEventStore eventStore,
        TrackAudioFeatureEvidenceStore evidenceStore,
        EventSignalWeights eventSignalWeights
    ) {
        this.libraryStore = libraryStore;
        this.eventStore = eventStore;
        this.evidenceStore = evidenceStore;
        this.eventSignalWeights = eventSignalWeights;
    }

    public Profile recompute(String userId, Integer eventLimit) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id is required to recompute audio taste profile.");
        }
        int resolvedLimit = eventLimit == null ? DEFAULT_EVENT_LIMIT : Math.max(1, Math.min(2_000, eventLimit));
        Map<String, AudioTasteTrackFeature> featuresByTrackId = collectPmsFeatures(userId.trim());
        List<UserMusicEventStore.StoredEvent> events = eventStore.findRecentByUserId(userId.trim(), resolvedLimit)
            .stream()
            .sorted(Comparator.comparing(UserMusicEventStore.StoredEvent::occurredAt))
            .toList();

        List<WeightedFeature> positives = new ArrayList<>();
        List<WeightedFeature> negatives = new ArrayList<>();
        for (UserMusicEventStore.StoredEvent event : events) {
            AudioTasteTrackFeature feature = featuresByTrackId.get(event.trackId());
            if (feature == null || !feature.usable()) {
                continue;
            }
            double eventWeight = event.eventWeight() == null
                ? eventSignalWeights.findWeight(event.eventType()).orElse(0.0d)
                : event.eventWeight();
            double finalWeight = Math.abs(eventWeight) * feature.featureWeight();
            if (eventWeight > 0.0d) {
                positives.add(new WeightedFeature(feature, finalWeight));
            } else if (eventWeight < 0.0d) {
                negatives.add(new WeightedFeature(feature, finalWeight));
            }
        }

        Centroid positive = centroid(positives);
        Centroid negative = centroid(negatives);
        Coverage coverage = coverage(featuresByTrackId.values().stream().toList());
        boolean applicable = positives.size() >= MIN_POSITIVE_READY_TRACKS && coverage.featureReadyRatio() >= 0.30d;
        List<String> warnings = new ArrayList<>();
        if (positives.size() < MIN_POSITIVE_READY_TRACKS) {
            warnings.add("Audio taste profile requires at least 10 positive feature-ready tracks.");
        }
        if (coverage.featureReadyRatio() < 0.30d) {
            warnings.add("Audio taste feature coverage is below 0.30.");
        }
        return new Profile(
            userId.trim(),
            applicable ? "ok" : "insufficient_data",
            applicable,
            positives.size(),
            negatives.size(),
            resolvedLimit,
            positive,
            negative,
            coverage,
            warnings,
            Instant.now()
        );
    }

    public Dataset dataset(String userId, Integer eventLimit) {
        Profile profile = recompute(userId, eventLimit);
        List<AudioTasteTrackFeature> rows = collectPmsFeatures(userId.trim()).values().stream()
            .sorted(Comparator.comparing(AudioTasteTrackFeature::trackId))
            .toList();
        return new Dataset("audio-taste-dataset-v1", userId.trim(), profile, rows);
    }

    private Map<String, AudioTasteTrackFeature> collectPmsFeatures(String userId) {
        Map<String, AudioTasteTrackFeature> result = new HashMap<>();
        for (PmsUserLibraryStore.LibraryPlaylistState playlist : libraryStore.findPlaylists(userId)) {
            if (playlist.tracks() == null) {
                continue;
            }
            for (PmsUserLibraryStore.LibraryTrackState track : playlist.tracks()) {
                PmsTrackAudioFeatures audio = track.audioFeatures();
                if (audio == null) {
                    continue;
                }
                EvidenceSummary evidence = evidenceSummary("pms_user_track", track.trackId());
                AudioTasteFeatureQuality.Quality quality = AudioTasteFeatureQuality.resolve(
                    audio.getAudioFeatureSource(),
                    audio.isAudioFeaturesFilled(),
                    evidence.maxConfidence(),
                    evidence.count()
                );
                result.putIfAbsent(track.trackId(), new AudioTasteTrackFeature(
                    "pms_user_track",
                    track.trackId(),
                    track.title(),
                    track.artistName(),
                    track.sourcePlatform(),
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
                ));
            }
        }
        return result;
    }

    private EvidenceSummary evidenceSummary(String trackScope, String trackId) {
        List<TrackAudioFeatureEvidenceStore.StoredEvidence> evidence = evidenceStore.findByTrack(trackScope, trackId, 10);
        OptionalDouble max = evidence.stream().mapToDouble(TrackAudioFeatureEvidenceStore.StoredEvidence::confidence).max();
        return new EvidenceSummary(evidence.size(), max.orElse(0.0d));
    }

    private Centroid centroid(List<WeightedFeature> rows) {
        double totalWeight = rows.stream().mapToDouble(WeightedFeature::weight).sum();
        if (totalWeight <= 0.0d) {
            return Centroid.empty();
        }
        return new Centroid(
            weighted(rows, totalWeight, "acousticness"),
            weighted(rows, totalWeight, "danceability"),
            weighted(rows, totalWeight, "energy"),
            weighted(rows, totalWeight, "instrumentalness"),
            weighted(rows, totalWeight, "liveness"),
            weighted(rows, totalWeight, "speechiness"),
            weightedTempo(rows, totalWeight),
            weighted(rows, totalWeight, "valence")
        );
    }

    private double weighted(List<WeightedFeature> rows, double totalWeight, String key) {
        return rows.stream().mapToDouble(row -> value(row.feature(), key) * row.weight()).sum() / totalWeight;
    }

    private double weightedTempo(List<WeightedFeature> rows, double totalWeight) {
        return rows.stream().mapToDouble(row -> normalizeTempo(row.feature().tempo()) * row.weight()).sum() / totalWeight;
    }

    private double value(AudioTasteTrackFeature feature, String key) {
        return switch (key) {
            case "acousticness" -> nullToMid(feature.acousticness());
            case "danceability" -> nullToMid(feature.danceability());
            case "energy" -> nullToMid(feature.energy());
            case "instrumentalness" -> nullToMid(feature.instrumentalness());
            case "liveness" -> nullToMid(feature.liveness());
            case "speechiness" -> nullToMid(feature.speechiness());
            case "valence" -> nullToMid(feature.valence());
            default -> 0.5d;
        };
    }

    private double normalizeTempo(Double tempo) {
        if (tempo == null) {
            return 0.5d;
        }
        return Math.max(0.0d, Math.min(1.0d, (tempo - 60.0d) / 140.0d));
    }

    private double nullToMid(Double value) {
        return value == null ? 0.5d : Math.max(0.0d, Math.min(1.0d, value));
    }

    private Coverage coverage(List<AudioTasteTrackFeature> rows) {
        if (rows.isEmpty()) {
            return new Coverage(0, 0, 0.0d, 0, 0);
        }
        long usable = rows.stream().filter(AudioTasteTrackFeature::usable).count();
        long weak = rows.stream().filter(row -> Objects.equals(row.featureTier(), "llm_weak")).count();
        long inferred = rows.stream().filter(row -> row.featureTier().startsWith("llm_")).count();
        return new Coverage(rows.size(), usable, round((double) usable / rows.size()), inferred, weak);
    }

    private double round(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private record WeightedFeature(AudioTasteTrackFeature feature, double weight) {
    }

    private record EvidenceSummary(long count, double maxConfidence) {
    }

    public record Dataset(String datasetVersion, String userId, Profile profile, List<AudioTasteTrackFeature> rows) {
    }

    public record Profile(
        String userId,
        String status,
        boolean audioTasteApplicable,
        int positiveTrackCount,
        int negativeTrackCount,
        int eventLimit,
        Centroid positiveCentroid,
        Centroid negativeCentroid,
        Coverage coverage,
        List<String> warnings,
        Instant recomputedAt
    ) {
    }

    public record Centroid(
        double acousticness,
        double danceability,
        double energy,
        double instrumentalness,
        double liveness,
        double speechiness,
        double tempo,
        double valence
    ) {
        static Centroid empty() {
            return new Centroid(0.5d, 0.5d, 0.5d, 0.5d, 0.5d, 0.5d, 0.5d, 0.5d);
        }
    }

    public record Coverage(
        int trackCount,
        long usableTrackCount,
        double featureReadyRatio,
        long inferredTrackCount,
        long weakTrackCount
    ) {
    }
}
```

- [ ] **Step 4: Run profile tests**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileServiceTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileServiceTest.java
git commit -m "feat: compute audio taste profile"
```

---

### Task 3: Candidate Audio Taste Scoring

**Files:**
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteScoringService.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteScoringServiceTest.java`

- [ ] **Step 1: Write the failing scoring tests**

```java
package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AudioTasteScoringServiceTest {

    @Test
    void shouldScoreCandidateNearPositiveCentroidAboveFarCandidate() {
        AudioTasteScoringService scoring = new AudioTasteScoringService();
        AudioTasteProfileService.Profile profile = profile();

        AudioTasteScoringService.Score near = scoring.score(profile, feature("near", 0.70d, 0.70d, 0.70d, 120.0d));
        AudioTasteScoringService.Score far = scoring.score(profile, feature("far", 0.20d, 0.20d, 0.20d, 80.0d));

        assertThat(near.applied()).isTrue();
        assertThat(near.score()).isGreaterThan(far.score());
        assertThat(near.explanationTokens()).contains("energy_match", "tempo_match", "valence_match");
    }

    @Test
    void shouldNoOpWhenProfileIsNotApplicable() {
        AudioTasteScoringService scoring = new AudioTasteScoringService();
        AudioTasteProfileService.Profile profile = new AudioTasteProfileService.Profile(
            "user-1",
            "insufficient_data",
            false,
            0,
            0,
            100,
            AudioTasteProfileService.Centroid.empty(),
            AudioTasteProfileService.Centroid.empty(),
            new AudioTasteProfileService.Coverage(0, 0, 0.0d, 0, 0),
            List.of("Audio taste profile requires at least 10 positive feature-ready tracks."),
            Instant.parse("2026-05-21T00:00:00Z")
        );

        AudioTasteScoringService.Score score = scoring.score(profile, feature("track-a", 0.70d, 0.70d, 0.70d, 120.0d));

        assertThat(score.applied()).isFalse();
        assertThat(score.score()).isEqualTo(0.0d);
        assertThat(score.explanationTokens()).contains("audio_taste_not_applicable");
    }

    private AudioTasteProfileService.Profile profile() {
        return new AudioTasteProfileService.Profile(
            "user-1",
            "ok",
            true,
            12,
            1,
            100,
            new AudioTasteProfileService.Centroid(0.2d, 0.7d, 0.7d, 0.01d, 0.1d, 0.05d, 0.43d, 0.7d),
            new AudioTasteProfileService.Centroid(0.8d, 0.2d, 0.2d, 0.01d, 0.1d, 0.05d, 0.14d, 0.2d),
            new AudioTasteProfileService.Coverage(20, 15, 0.75d, 2, 1),
            List.of(),
            Instant.parse("2026-05-21T00:00:00Z")
        );
    }

    private AudioTasteTrackFeature feature(String trackId, double energy, double valence, double danceability, double tempo) {
        return new AudioTasteTrackFeature(
            "pms_user_track",
            trackId,
            "Title",
            "Artist",
            "spotify",
            "reccobeats_lookup",
            true,
            1.0d,
            "provider",
            0.2d,
            danceability,
            energy,
            0.01d,
            0.1d,
            0.05d,
            tempo,
            valence
        );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteScoringServiceTest
```

Expected: FAIL with `cannot find symbol` for `AudioTasteScoringService`.

- [ ] **Step 3: Implement scoring service**

```java
package io.myforevermusic.api.modules.recommendation.application;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AudioTasteScoringService {

    public Score score(AudioTasteProfileService.Profile profile, AudioTasteTrackFeature candidate) {
        if (profile == null || !profile.audioTasteApplicable()) {
            return new Score(false, 0.0d, 0.0d, List.of("audio_taste_not_applicable"));
        }
        if (candidate == null || !candidate.usable()) {
            return new Score(false, 0.0d, 0.0d, List.of("candidate_audio_missing"));
        }

        double positiveSimilarity = 1.0d - normalizedDistance(candidate, profile.positiveCentroid());
        double negativeDistanceBonus = normalizedDistance(candidate, profile.negativeCentroid());
        double coverageWeight = Math.min(1.0d, profile.coverage().featureReadyRatio() * candidate.featureWeight());
        double score = clamp(coverageWeight * ((0.70d * positiveSimilarity) + (0.30d * negativeDistanceBonus)));
        List<String> tokens = explanationTokens(candidate, profile.positiveCentroid());
        if ("llm_weak".equals(candidate.featureTier())) {
            tokens.add("low_confidence_audio");
        }
        return new Score(true, round(score), round(coverageWeight), List.copyOf(tokens));
    }

    private double normalizedDistance(AudioTasteTrackFeature feature, AudioTasteProfileService.Centroid centroid) {
        double sum = 0.0d;
        sum += squared(feature.acousticness(), centroid.acousticness());
        sum += squared(feature.danceability(), centroid.danceability());
        sum += squared(feature.energy(), centroid.energy());
        sum += squared(feature.instrumentalness(), centroid.instrumentalness());
        sum += squared(feature.liveness(), centroid.liveness());
        sum += squared(feature.speechiness(), centroid.speechiness());
        sum += squared(normalizeTempo(feature.tempo()), centroid.tempo());
        sum += squared(feature.valence(), centroid.valence());
        return Math.min(1.0d, Math.sqrt(sum / 8.0d));
    }

    private List<String> explanationTokens(AudioTasteTrackFeature feature, AudioTasteProfileService.Centroid centroid) {
        List<String> tokens = new ArrayList<>();
        if (Math.abs(value(feature.energy()) - centroid.energy()) <= 0.15d) {
            tokens.add("energy_match");
        }
        if (Math.abs(value(feature.valence()) - centroid.valence()) <= 0.15d) {
            tokens.add("valence_match");
        }
        if (Math.abs(value(feature.danceability()) - centroid.danceability()) <= 0.15d) {
            tokens.add("danceability_match");
        }
        if (Math.abs(normalizeTempo(feature.tempo()) - centroid.tempo()) <= 0.15d) {
            tokens.add("tempo_match");
        }
        if (tokens.isEmpty()) {
            tokens.add("audio_profile_distance");
        }
        return tokens;
    }

    private double squared(Double left, double right) {
        double value = value(left);
        return (value - right) * (value - right);
    }

    private double value(Double value) {
        return value == null ? 0.5d : Math.max(0.0d, Math.min(1.0d, value));
    }

    private double normalizeTempo(Double tempo) {
        if (tempo == null) {
            return 0.5d;
        }
        return Math.max(0.0d, Math.min(1.0d, (tempo - 60.0d) / 140.0d));
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private double round(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    public record Score(boolean applied, double score, double coverageWeight, List<String> explanationTokens) {
    }
}
```

- [ ] **Step 4: Run scoring tests**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteScoringServiceTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteScoringService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteScoringServiceTest.java
git commit -m "feat: score audio taste candidates"
```

---

### Task 4: Admin Audio Taste API

**Files:**
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminController.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminControllerWebMvcTest.java`

- [ ] **Step 1: Write the failing WebMvc test**

```java
package io.myforevermusic.api.modules.recommendation.presentation;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AudioTasteAdminController.class)
class AudioTasteAdminControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AudioTasteProfileService profileService;

    @Test
    void shouldReturnAudioTasteProfile() throws Exception {
        when(profileService.recompute(eq("target-user"), eq(100))).thenReturn(profile());

        mockMvc.perform(get("/api/v1/recommendations/admin/audio-taste/profile")
                .param("user_id", "admin")
                .param("target_user_id", "target-user")
                .param("event_limit", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.profile.user_id").value("target-user"))
            .andExpect(jsonPath("$.profile.audio_taste_applicable").value(true))
            .andExpect(jsonPath("$.profile.coverage.feature_ready_ratio").value(0.75));
    }

    @Test
    void shouldRecomputeAudioTasteProfile() throws Exception {
        when(profileService.recompute(eq("target-user"), eq(100))).thenReturn(profile());

        mockMvc.perform(post("/api/v1/recommendations/admin/audio-taste/recompute")
                .param("user_id", "admin")
                .param("target_user_id", "target-user")
                .param("event_limit", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.profile.positive_track_count").value(12));
    }

    private AudioTasteProfileService.Profile profile() {
        return new AudioTasteProfileService.Profile(
            "target-user",
            "ok",
            true,
            12,
            1,
            100,
            new AudioTasteProfileService.Centroid(0.2d, 0.7d, 0.7d, 0.01d, 0.1d, 0.05d, 0.43d, 0.7d),
            AudioTasteProfileService.Centroid.empty(),
            new AudioTasteProfileService.Coverage(20, 15, 0.75d, 2, 1),
            List.of(),
            Instant.parse("2026-05-21T00:00:00Z")
        );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.presentation.AudioTasteAdminControllerWebMvcTest
```

Expected: FAIL with `cannot find symbol` for `AudioTasteAdminController`.

- [ ] **Step 3: Implement controller**

```java
package io.myforevermusic.api.modules.recommendation.presentation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileService;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recommendations/admin/audio-taste")
public class AudioTasteAdminController {

    private final AudioTasteProfileService profileService;

    public AudioTasteAdminController(AudioTasteProfileService profileService) {
        this.profileService = profileService;
    }

    @Operation(summary = "Fetch an on-demand audio taste profile for a user")
    @GetMapping("/profile")
    public AudioTasteProfileResponse profile(
        @RequestParam("user_id") String userId,
        @RequestParam(value = "target_user_id", required = false) String targetUserId,
        @RequestParam(value = "event_limit", required = false) Integer eventLimit
    ) {
        return AudioTasteProfileResponse.from(profileService.recompute(targetUser(userId, targetUserId), eventLimit));
    }

    @Operation(summary = "Recompute an audio taste profile for a user")
    @PostMapping("/recompute")
    public AudioTasteProfileResponse recompute(
        @RequestParam("user_id") String userId,
        @RequestParam(value = "target_user_id", required = false) String targetUserId,
        @RequestParam(value = "event_limit", required = false) Integer eventLimit
    ) {
        return AudioTasteProfileResponse.from(profileService.recompute(targetUser(userId, targetUserId), eventLimit));
    }

    @Operation(summary = "Export audio taste dataset summary and rows for a user")
    @GetMapping("/dataset")
    public AudioTasteDatasetResponse dataset(
        @RequestParam("user_id") String userId,
        @RequestParam(value = "target_user_id", required = false) String targetUserId,
        @RequestParam(value = "event_limit", required = false) Integer eventLimit
    ) {
        AudioTasteProfileService.Dataset dataset = profileService.dataset(targetUser(userId, targetUserId), eventLimit);
        return AudioTasteDatasetResponse.from(dataset);
    }

    private String targetUser(String userId, String targetUserId) {
        return targetUserId == null || targetUserId.isBlank() ? userId : targetUserId;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AudioTasteProfileResponse(String service, String status, Instant generatedAt, AudioTasteProfileService.Profile profile) {
        static AudioTasteProfileResponse from(AudioTasteProfileService.Profile profile) {
            return new AudioTasteProfileResponse("api", profile.status(), Instant.now(), profile);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AudioTasteDatasetResponse(
        String service,
        String status,
        Instant generatedAt,
        String datasetVersion,
        String userId,
        AudioTasteProfileService.Profile profile,
        List<?> rows
    ) {
        static AudioTasteDatasetResponse from(AudioTasteProfileService.Dataset dataset) {
            return new AudioTasteDatasetResponse(
                "api",
                dataset.profile().status(),
                Instant.now(),
                dataset.datasetVersion(),
                dataset.userId(),
                dataset.profile(),
                dataset.rows()
            );
        }
    }
}
```

- [ ] **Step 4: Run controller tests**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.presentation.AudioTasteAdminControllerWebMvcTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminController.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminControllerWebMvcTest.java
git commit -m "feat: expose audio taste admin API"
```

---

### Task 5: GMS Preview Audio Taste Boost

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewService.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java`

- [ ] **Step 1: Write failing GMS preview test**

Add a test to `GmsRecommendationPreviewServiceTest` that builds two playable PMS candidates with audio features and events, then asserts the candidate closer to the user audio centroid is promoted. Use existing helper construction style from the file. The assertion should be:

```java
assertThat(response.items()).extracting(GmsRecommendationPreviewResponse.RecommendationItem::trackId)
    .containsExactly("track-audio-match", "track-audio-far");
assertThat(response.warnings()).anyMatch(warning -> warning.contains("Audio taste ranking adjusted"));
assertThat(response.context().engine()).contains("audio-taste:v1");
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest
```

Expected: FAIL because no audio taste boost is applied and constructor dependencies are not yet wired.

- [ ] **Step 3: Modify constructor and fields**

Add imports:

```java
import io.myforevermusic.api.modules.recommendation.application.AudioTasteFeatureQuality;
import io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileService;
import io.myforevermusic.api.modules.recommendation.application.AudioTasteScoringService;
import io.myforevermusic.api.modules.recommendation.application.AudioTasteTrackFeature;
```

Add fields:

```java
private final AudioTasteProfileService audioTasteProfileService;
private final AudioTasteScoringService audioTasteScoringService;
```

Add constructor parameters after `RecommendationReranker recommendationReranker`:

```java
AudioTasteProfileService audioTasteProfileService,
AudioTasteScoringService audioTasteScoringService,
```

Assign them:

```java
this.audioTasteProfileService = audioTasteProfileService;
this.audioTasteScoringService = audioTasteScoringService;
```

- [ ] **Step 4: Apply audio boost inside `projectPlayableItems`**

After `applySasrecRanking(...)` and before `rankedCandidates.stream().limit(...)`, add:

```java
rankedCandidates = applyAudioTasteRanking(
    request.userId(),
    rankedCandidates,
    enrichmentWarnings
);
```

Add helper methods:

```java
private List<RankedLibraryCandidate> applyAudioTasteRanking(
    String userId,
    List<RankedLibraryCandidate> rankedCandidates,
    List<String> enrichmentWarnings
) {
    if (userId == null || userId.isBlank() || rankedCandidates.size() < 2) {
        return rankedCandidates;
    }
    AudioTasteProfileService.Profile profile = audioTasteProfileService.recompute(userId, 500);
    if (!profile.audioTasteApplicable()) {
        return rankedCandidates;
    }
    record AudioScored(RankedLibraryCandidate ranked, double boostedScore, boolean applied) {}
    List<AudioScored> scored = rankedCandidates.stream()
        .map(ranked -> {
            AudioTasteScoringService.Score score = audioTasteScoringService.score(
                profile,
                toAudioTasteTrackFeature(ranked.candidate())
            );
            double centered = score.applied() ? (score.score() - 0.5d) : 0.0d;
            double boostWeight = resolveAudioBoostWeight(profile, score);
            double boosted = ranked.affinityScore() * (1.0d + boostWeight * centered);
            return new AudioScored(ranked, roundScore(boosted), score.applied());
        })
        .sorted((left, right) -> Double.compare(right.boostedScore(), left.boostedScore()))
        .toList();
    long appliedCount = scored.stream().filter(AudioScored::applied).count();
    if (appliedCount <= 0L) {
        return rankedCandidates;
    }
    enrichmentWarnings.add("Audio taste ranking adjusted %d candidate(s) via audio-taste:v1.".formatted(appliedCount));
    return scored.stream()
        .map(row -> new RankedLibraryCandidate(row.ranked().candidate(), row.boostedScore(), row.ranked().sasrecRanked()))
        .toList();
}

private double resolveAudioBoostWeight(
    AudioTasteProfileService.Profile profile,
    AudioTasteScoringService.Score score
) {
    if (!score.applied()) {
        return 0.0d;
    }
    double weight = 0.12d;
    if (profile.coverage().featureReadyRatio() < 0.50d || score.coverageWeight() < 0.50d) {
        weight *= 0.5d;
    }
    if (profile.coverage().weakTrackCount() > profile.coverage().usableTrackCount() / 2.0d) {
        weight *= 0.5d;
    }
    return weight;
}

private AudioTasteTrackFeature toAudioTasteTrackFeature(LibraryCandidateTrack candidate) {
    PmsTrackAudioFeatures audio = candidate.audioFeatures();
    if (audio == null) {
        return new AudioTasteTrackFeature(
            "pms_user_track",
            candidate.trackId(),
            candidate.title(),
            candidate.artistName(),
            candidate.sourcePlatform(),
            "unresolved",
            false,
            0.0d,
            "missing",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }
    AudioTasteFeatureQuality.Quality quality = AudioTasteFeatureQuality.resolve(
        audio.getAudioFeatureSource(),
        audio.isAudioFeaturesFilled(),
        0.0d,
        0L
    );
    return new AudioTasteTrackFeature(
        "pms_user_track",
        candidate.trackId(),
        candidate.title(),
        candidate.artistName(),
        candidate.sourcePlatform(),
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
```

- [ ] **Step 5: Include audio model in response context**

Update `applySasrecContext` or add a second context updater so warning-driven audio application appends `+audio-taste:v1` to `context.engine()`. The code should produce values such as:

```text
gms-baseline-v1+audio-taste:v1
gms-baseline-v1+sasrec:model-version+audio-taste:v1
```

- [ ] **Step 6: Run GMS preview tests**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java
git commit -m "feat: apply audio taste to gms preview"
```

---

### Task 6: Verification and API Documentation

**Files:**
- Create: `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`
- Modify: `docs/architecture/AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md`
- Modify: `docs/PROJECT_GUIDE.md`

- [ ] **Step 1: Add API doc**

Create `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`:

```markdown
# Audio Taste Model Admin API

작성일: `2026-05-21`

Audio Taste Model v1은 PMS user library와 user music event, audio feature completion snapshot을 사용해 사용자 오디오 취향 centroid를 계산한다.

## Endpoints

### Get profile

`GET /api/v1/recommendations/admin/audio-taste/profile?user_id={adminUserId}&target_user_id={targetUserId}&event_limit=500`

반환:

- `status`
- `profile.audio_taste_applicable`
- `profile.positive_centroid`
- `profile.negative_centroid`
- `profile.coverage`
- `profile.warnings`

### Recompute profile

`POST /api/v1/recommendations/admin/audio-taste/recompute?user_id={adminUserId}&target_user_id={targetUserId}&event_limit=500`

v1은 별도 profile table을 만들지 않고 on-demand로 재계산한다.

### Dataset

`GET /api/v1/recommendations/admin/audio-taste/dataset?user_id={adminUserId}&target_user_id={targetUserId}&event_limit=500`

반환:

- `dataset_version=audio-taste-dataset-v1`
- profile summary
- PMS audio feature rows

## Serving

GMS preview는 `audio_taste_applicable=true`일 때 `audio-taste:v1` boost를 적용한다. Coverage나 candidate feature confidence가 낮으면 boost weight를 자동 축소한다.
```

- [ ] **Step 2: Update architecture/project docs**

In `docs/architecture/AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md`, add an implementation note under Phase 6:

```markdown
- Audio Taste Model v1 1차는 Spring API의 `AudioCentroidBaseline`으로 시작한다. Deep model artifact는 다음 단계이며, GMS preview serving에는 `audio-taste:v1` boost만 보수적으로 적용한다.
```

In `docs/PROJECT_GUIDE.md`, add a current-state bullet:

```markdown
- `services/api`는 Audio Taste Model v1의 on-demand centroid profile/admin API/GMS preview boost를 제공한다. 첫 구현은 deep model이 아니라 설명 가능한 `AudioCentroidBaseline`이며, audio coverage/confidence가 낮으면 자동으로 no-op 또는 낮은 가중치로 동작한다.
```

- [ ] **Step 3: Run targeted tests**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteFeatureQualityTest \
  --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileServiceTest \
  --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteScoringServiceTest \
  --tests io.myforevermusic.api.modules.recommendation.presentation.AudioTasteAdminControllerWebMvcTest \
  --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest
```

Expected: PASS.

- [ ] **Step 4: Run full API tests**

Run:

```bash
cd services/api
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit docs and verification fixes**

```bash
git add docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md \
  docs/architecture/AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md \
  docs/PROJECT_GUIDE.md
git commit -m "docs: document audio taste model admin API"
```

---

## Self-Review

- Spec coverage: dataset/profile/scoring/admin API/GMS boost/audit-context/docs are covered by Tasks 1-6.
- Scope control: v1 avoids neural model training, vector DB, scheduler, and schema-wide redesign.
- Type consistency: plan uses `AudioTasteTrackFeature`, `AudioTasteFeatureQuality.Quality`, `AudioTasteProfileService.Profile`, and `AudioTasteScoringService.Score` consistently.
- Verification: each behavior has a failing-test step and a passing-test step, followed by full `./gradlew test`.
