# Heavy Taste Modes v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose deterministic `taste_modes` for heavy audio taste profiles in admin profile/dataset responses without changing GMS recommendation serving.

**Architecture:** Add a small Spring-side mode builder that groups usable PMS audio feature rows into deterministic audio buckets, merges small buckets into nearby larger modes, and returns inspection-only summaries. Wire the mode builder into `AudioTasteProfileService.Profile` and `AudioTasteAdminController`; leave `GmsRecommendationPreviewService` scoring unchanged.

**Tech Stack:** Spring Boot 3.5, Java 21 records/services, JUnit 5, AssertJ, Spring WebMvc tests, Gradle.

---

## File Structure

- Create `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteMode.java`
  - Public record for mode response data: id, label, count, confidence, centroid, top artists, representative tracks.
- Create `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteModeService.java`
  - Deterministic bucket grouping, target mode count, small-bucket merge, centroid, confidence, top artist, representative track calculation.
- Create `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteModeServiceTest.java`
  - Unit tests for heavy gating, bucket split, merge, representative tracks, and artist-dominated confidence.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileService.java`
  - Inject/use `AudioTasteModeService`.
  - Add `List<AudioTasteMode> tasteModes` to `Profile`.
  - Return non-empty modes only for `profile_type=heavy`.
- Modify `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileServiceTest.java`
  - Assert heavy profiles include modes and strong profiles return empty mode list.
  - Update existing constructor usages if record signature changes.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminController.java`
  - Add snake_case JSON DTOs for `taste_modes`.
- Modify `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminControllerWebMvcTest.java`
  - Assert `taste_modes` serializes for heavy profile and empty list serializes for non-heavy profile.
- Modify docs:
  - `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`
  - `docs/architecture/AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md`
- Verification commands:
  - `cd services/api && ./gradlew test --tests '*AudioTaste*' --tests '*GmsRecommendationPreviewServiceTest*' --rerun-tasks`
  - `git diff --check`

---

### Task 1: Add Mode Domain And Deterministic Builder

**Files:**
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteMode.java`
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteModeService.java`
- Create: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteModeServiceTest.java`

- [ ] **Step 1: Write failing tests for mode builder behavior**

Create `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteModeServiceTest.java`.

```java
package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AudioTasteModeServiceTest {

    private final AudioTasteModeService service = new AudioTasteModeService();

    @Test
    void shouldReturnEmptyModesForNonHeavyProfile() {
        List<AudioTasteMode> modes = service.buildModes(
            "strong",
            0.75d,
            tracks(199, "strong", "Artist", 0.70d, 0.70d, 0.70d, 0.20d, 120.0d)
        );

        assertThat(modes).isEmpty();
    }

    @Test
    void shouldBuildTasteModesForHeavyProfileRows() {
        List<AudioTasteTrackFeature> rows = new ArrayList<>();
        rows.addAll(tracks(80, "dance", "Dance Artist", 0.82d, 0.74d, 0.80d, 0.12d, 128.0d));
        rows.addAll(tracks(70, "calm", "Calm Artist", 0.22d, 0.45d, 0.34d, 0.82d, 82.0d));
        rows.addAll(tracks(50, "mid", "Mid Artist", 0.52d, 0.54d, 0.50d, 0.32d, 106.0d));

        List<AudioTasteMode> modes = service.buildModes("heavy", 0.81d, rows);

        assertThat(modes).hasSizeGreaterThanOrEqualTo(3);
        assertThat(modes).extracting(AudioTasteMode::label)
            .anyMatch(label -> label.contains("high_energy"))
            .anyMatch(label -> label.contains("low_energy"))
            .anyMatch(label -> label.contains("mid_energy"));
        assertThat(modes.getFirst().modeId()).isEqualTo("mode-1");
        assertThat(modes).allSatisfy(mode -> {
            assertThat(mode.confidence()).isBetween(0.0d, 1.0d);
            assertThat(mode.representativeTracks()).hasSizeLessThanOrEqualTo(5);
            assertThat(mode.topArtists()).hasSizeLessThanOrEqualTo(5);
        });
    }

    @Test
    void shouldMergeSmallBucketsIntoNearestKeptMode() {
        List<AudioTasteTrackFeature> rows = new ArrayList<>();
        rows.addAll(tracks(90, "dance", "Dance Artist", 0.82d, 0.74d, 0.80d, 0.12d, 128.0d));
        rows.addAll(tracks(80, "calm", "Calm Artist", 0.22d, 0.45d, 0.34d, 0.82d, 82.0d));
        rows.addAll(tracks(30, "tiny", "Tiny Artist", 0.80d, 0.72d, 0.78d, 0.14d, 126.0d));

        List<AudioTasteMode> modes = service.buildModes("heavy", 0.80d, rows);

        assertThat(modes).hasSize(3);
        assertThat(modes.stream().mapToInt(AudioTasteMode::trackCount).sum()).isEqualTo(200);
        assertThat(modes).extracting(AudioTasteMode::trackCount).contains(120);
    }

    @Test
    void shouldLowerConfidenceWhenModeIsDominatedByOneArtist() {
        List<AudioTasteTrackFeature> mixedRows = new ArrayList<>();
        mixedRows.addAll(tracks(100, "mixed-a", "Artist A", 0.82d, 0.74d, 0.80d, 0.12d, 128.0d));
        mixedRows.addAll(tracks(100, "mixed-b", "Artist B", 0.82d, 0.74d, 0.80d, 0.12d, 128.0d));

        List<AudioTasteTrackFeature> dominatedRows = tracks(
            200,
            "dominated",
            "One Artist",
            0.82d,
            0.74d,
            0.80d,
            0.12d,
            128.0d
        );

        double mixedConfidence = service.buildModes("heavy", 0.80d, mixedRows).getFirst().confidence();
        double dominatedConfidence = service.buildModes("heavy", 0.80d, dominatedRows).getFirst().confidence();

        assertThat(dominatedConfidence).isLessThan(mixedConfidence);
    }

    @Test
    void shouldSortRepresentativeTracksByDistanceToCentroid() {
        List<AudioTasteTrackFeature> rows = new ArrayList<>();
        rows.addAll(tracks(198, "base", "Artist", 0.80d, 0.70d, 0.80d, 0.10d, 126.0d));
        rows.add(feature("near", "Near", "Artist", 0.80d, 0.70d, 0.80d, 0.10d, 126.0d));
        rows.add(feature("far", "Far", "Artist", 0.20d, 0.20d, 0.20d, 0.90d, 72.0d));

        AudioTasteMode mode = service.buildModes("heavy", 0.80d, rows).getFirst();

        assertThat(mode.representativeTracks().getFirst().trackId()).isEqualTo("near");
        assertThat(mode.representativeTracks().getFirst().distanceToCentroid()).isLessThan(
            mode.representativeTracks().getLast().distanceToCentroid()
        );
    }

    private List<AudioTasteTrackFeature> tracks(
        int count,
        String prefix,
        String artistName,
        double energy,
        double valence,
        double danceability,
        double acousticness,
        double tempo
    ) {
        return IntStream.rangeClosed(1, count)
            .mapToObj(index -> feature(
                prefix + "-" + index,
                prefix + " " + index,
                artistName,
                energy,
                valence,
                danceability,
                acousticness,
                tempo
            ))
            .toList();
    }

    private AudioTasteTrackFeature feature(
        String trackId,
        String title,
        String artistName,
        double energy,
        double valence,
        double danceability,
        double acousticness,
        double tempo
    ) {
        return new AudioTasteTrackFeature(
            "pms_user_track",
            trackId,
            title,
            artistName,
            "tidal",
            "reccobeats_lookup",
            true,
            1.0d,
            "provider",
            acousticness,
            danceability,
            energy,
            0.02d,
            0.10d,
            0.05d,
            tempo,
            valence
        );
    }
}
```

- [ ] **Step 2: Run test to verify RED**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteModeServiceTest
```

Expected: FAIL at compile time because `AudioTasteModeService` and `AudioTasteMode` do not exist.

- [ ] **Step 3: Add `AudioTasteMode` record**

Create `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteMode.java`.

```java
package io.myforevermusic.api.modules.recommendation.application;

import java.util.List;

public record AudioTasteMode(
    String modeId,
    String label,
    int trackCount,
    double confidence,
    AudioTasteProfileService.Centroid centroid,
    List<TopArtist> topArtists,
    List<RepresentativeTrack> representativeTracks
) {

    public AudioTasteMode {
        topArtists = topArtists == null ? List.of() : List.copyOf(topArtists);
        representativeTracks = representativeTracks == null ? List.of() : List.copyOf(representativeTracks);
    }

    public record TopArtist(String artistName, int trackCount) {
    }

    public record RepresentativeTrack(
        String trackId,
        String title,
        String artistName,
        String sourcePlatform,
        double distanceToCentroid
    ) {
    }
}
```

- [ ] **Step 4: Add `AudioTasteModeService` implementation**

Create `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteModeService.java`.

```java
package io.myforevermusic.api.modules.recommendation.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class AudioTasteModeService {

    private static final String PROFILE_HEAVY = "heavy";
    private static final int MIN_HEAVY_ROWS = 200;
    private static final int MAX_REPRESENTATIVE_TRACKS = 5;
    private static final int MAX_TOP_ARTISTS = 5;

    public List<AudioTasteMode> buildModes(
        String profileType,
        double profileConfidence,
        List<AudioTasteTrackFeature> rows
    ) {
        List<AudioTasteTrackFeature> usableRows = rows == null
            ? List.of()
            : rows.stream().filter(AudioTasteTrackFeature::usable).toList();
        if (!PROFILE_HEAVY.equals(profileType) || usableRows.size() < MIN_HEAVY_ROWS) {
            return List.of();
        }

        int targetCount = targetModeCount(usableRows.size());
        List<ModeBucket> buckets = buckets(usableRows);
        if (buckets.isEmpty()) {
            return List.of();
        }

        List<ModeBucket> kept = buckets.stream()
            .limit(targetCount)
            .map(ModeBucket::copy)
            .collect(Collectors.toCollection(ArrayList::new));
        for (ModeBucket bucket : buckets.stream().skip(targetCount).toList()) {
            nearestBucket(bucket, kept).tracks().addAll(bucket.tracks());
        }

        return kept.stream()
            .sorted(Comparator.comparingInt((ModeBucket bucket) -> bucket.tracks().size()).reversed()
                .thenComparing(ModeBucket::label))
            .map(bucket -> toMode(bucket, profileConfidence))
            .filter(mode -> mode.trackCount() > 0)
            .toList();
    }

    private int targetModeCount(int usableTrackCount) {
        if (usableTrackCount >= 800) {
            return 8;
        }
        if (usableTrackCount >= 400) {
            return 6;
        }
        return 4;
    }

    private List<ModeBucket> buckets(List<AudioTasteTrackFeature> rows) {
        Map<String, List<AudioTasteTrackFeature>> grouped = new LinkedHashMap<>();
        for (AudioTasteTrackFeature row : rows) {
            grouped.computeIfAbsent(label(row), ignored -> new ArrayList<>()).add(row);
        }
        return grouped.entrySet().stream()
            .map(entry -> new ModeBucket(entry.getKey(), new ArrayList<>(entry.getValue())))
            .sorted(Comparator.comparingInt((ModeBucket bucket) -> bucket.tracks().size()).reversed()
                .thenComparing(ModeBucket::label))
            .toList();
    }

    private String label(AudioTasteTrackFeature row) {
        List<String> tokens = new ArrayList<>();
        tokens.add(energyToken(row.energy()));
        tokens.add(valenceToken(row.valence()));
        if (value(row.danceability()) >= 0.65d) {
            tokens.add("danceable");
        }
        if (value(row.acousticness()) >= 0.65d) {
            tokens.add("acoustic");
        }
        double tempo = normalizeTempo(row.tempo());
        if (tempo >= 0.65d) {
            tokens.add("fast");
        } else if (tempo <= 0.35d) {
            tokens.add("slow");
        }
        return String.join("_", tokens);
    }

    private String energyToken(Double energy) {
        double value = value(energy);
        if (value >= 0.65d) {
            return "high_energy";
        }
        if (value <= 0.35d) {
            return "low_energy";
        }
        return "mid_energy";
    }

    private String valenceToken(Double valence) {
        double value = value(valence);
        if (value >= 0.62d) {
            return "bright";
        }
        if (value <= 0.38d) {
            return "dark";
        }
        return "neutral";
    }

    private ModeBucket nearestBucket(ModeBucket source, List<ModeBucket> candidates) {
        AudioTasteProfileService.Centroid sourceCentroid = centroid(source.tracks());
        return candidates.stream()
            .min(Comparator.comparingDouble(candidate -> distance(sourceCentroid, centroid(candidate.tracks()))))
            .orElseGet(candidates::getFirst);
    }

    private AudioTasteMode toMode(ModeBucket bucket, double profileConfidence) {
        AudioTasteProfileService.Centroid centroid = centroid(bucket.tracks());
        List<AudioTasteMode.TopArtist> artists = topArtists(bucket.tracks());
        double confidence = modeConfidence(profileConfidence, bucket.tracks(), artists);
        List<AudioTasteMode.RepresentativeTrack> representatives = bucket.tracks().stream()
            .map(track -> new AudioTasteMode.RepresentativeTrack(
                track.trackId(),
                track.title(),
                track.artistName(),
                track.sourcePlatform(),
                round(distance(track, centroid))
            ))
            .sorted(Comparator.comparingDouble(AudioTasteMode.RepresentativeTrack::distanceToCentroid)
                .thenComparing(AudioTasteMode.RepresentativeTrack::trackId))
            .limit(MAX_REPRESENTATIVE_TRACKS)
            .toList();
        return new AudioTasteMode(
            "",
            bucket.label(),
            bucket.tracks().size(),
            confidence,
            centroid,
            artists,
            representatives
        );
    }

    private double modeConfidence(
        double profileConfidence,
        List<AudioTasteTrackFeature> rows,
        List<AudioTasteMode.TopArtist> artists
    ) {
        if (rows.isEmpty()) {
            return 0.0d;
        }
        double countFactor = Math.min(1.0d, Math.sqrt(rows.size() / 50.0d));
        double averageFeatureWeight = rows.stream().mapToDouble(AudioTasteTrackFeature::featureWeight).average().orElse(0.0d);
        double topArtistShare = artists.isEmpty() ? 0.0d : (double) artists.getFirst().trackCount() / rows.size();
        double artistDiversityMultiplier = topArtistShare >= 0.70d ? 0.70d : topArtistShare >= 0.50d ? 0.85d : 1.0d;
        return round(clamp(profileConfidence * countFactor * averageFeatureWeight * artistDiversityMultiplier));
    }

    private List<AudioTasteMode.TopArtist> topArtists(List<AudioTasteTrackFeature> rows) {
        Map<String, Long> counts = new HashMap<>();
        for (AudioTasteTrackFeature row : rows) {
            String artist = normalize(row.artistName());
            if (!artist.isBlank()) {
                counts.merge(artist, 1L, Long::sum);
            }
        }
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
            .limit(MAX_TOP_ARTISTS)
            .map(entry -> new AudioTasteMode.TopArtist(entry.getKey(), Math.toIntExact(entry.getValue())))
            .toList();
    }

    private AudioTasteProfileService.Centroid centroid(List<AudioTasteTrackFeature> rows) {
        double totalWeight = rows.stream().mapToDouble(AudioTasteTrackFeature::featureWeight).sum();
        if (totalWeight <= 0.0d) {
            return AudioTasteProfileService.Centroid.empty();
        }
        return new AudioTasteProfileService.Centroid(
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

    private double weighted(List<AudioTasteTrackFeature> rows, double totalWeight, String key) {
        return rows.stream().mapToDouble(row -> featureValue(row, key) * row.featureWeight()).sum() / totalWeight;
    }

    private double weightedTempo(List<AudioTasteTrackFeature> rows, double totalWeight) {
        return rows.stream().mapToDouble(row -> normalizeTempo(row.tempo()) * row.featureWeight()).sum() / totalWeight;
    }

    private double featureValue(AudioTasteTrackFeature row, String key) {
        return switch (key) {
            case "acousticness" -> value(row.acousticness());
            case "danceability" -> value(row.danceability());
            case "energy" -> value(row.energy());
            case "instrumentalness" -> value(row.instrumentalness());
            case "liveness" -> value(row.liveness());
            case "speechiness" -> value(row.speechiness());
            case "valence" -> value(row.valence());
            default -> 0.5d;
        };
    }

    private double distance(AudioTasteTrackFeature track, AudioTasteProfileService.Centroid centroid) {
        double sum = 0.0d;
        sum += squared(value(track.acousticness()), centroid.acousticness());
        sum += squared(value(track.danceability()), centroid.danceability());
        sum += squared(value(track.energy()), centroid.energy());
        sum += squared(value(track.instrumentalness()), centroid.instrumentalness());
        sum += squared(value(track.liveness()), centroid.liveness());
        sum += squared(value(track.speechiness()), centroid.speechiness());
        sum += squared(normalizeTempo(track.tempo()), centroid.tempo());
        sum += squared(value(track.valence()), centroid.valence());
        return Math.sqrt(sum / 8.0d);
    }

    private double distance(AudioTasteProfileService.Centroid left, AudioTasteProfileService.Centroid right) {
        double sum = 0.0d;
        sum += squared(left.acousticness(), right.acousticness());
        sum += squared(left.danceability(), right.danceability());
        sum += squared(left.energy(), right.energy());
        sum += squared(left.instrumentalness(), right.instrumentalness());
        sum += squared(left.liveness(), right.liveness());
        sum += squared(left.speechiness(), right.speechiness());
        sum += squared(left.tempo(), right.tempo());
        sum += squared(left.valence(), right.valence());
        return Math.sqrt(sum / 8.0d);
    }

    private double squared(double left, double right) {
        return (left - right) * (left - right);
    }

    private double normalizeTempo(Double tempo) {
        if (tempo == null) {
            return 0.5d;
        }
        return Math.max(0.0d, Math.min(1.0d, (tempo - 60.0d) / 140.0d));
    }

    private double value(Double value) {
        return value == null ? 0.5d : Math.max(0.0d, Math.min(1.0d, value));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private double round(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private record ModeBucket(String label, List<AudioTasteTrackFeature> tracks) {
        private ModeBucket copy() {
            return new ModeBucket(label, new ArrayList<>(tracks));
        }
    }
}
```

- [ ] **Step 5: Fix `mode_id` assignment**

The implementation in Step 4 creates modes before assigning stable ids. Replace the final return block in `buildModes` with this exact code so ids follow the post-sort order:

```java
List<AudioTasteMode> modes = kept.stream()
    .sorted(Comparator.comparingInt((ModeBucket bucket) -> bucket.tracks().size()).reversed()
        .thenComparing(ModeBucket::label))
    .map(bucket -> toMode(bucket, profileConfidence))
    .filter(mode -> mode.trackCount() > 0)
    .toList();
List<AudioTasteMode> numbered = new ArrayList<>();
for (int index = 0; index < modes.size(); index++) {
    AudioTasteMode mode = modes.get(index);
    numbered.add(new AudioTasteMode(
        "mode-" + (index + 1),
        mode.label(),
        mode.trackCount(),
        mode.confidence(),
        mode.centroid(),
        mode.topArtists(),
        mode.representativeTracks()
    ));
}
return List.copyOf(numbered);
```

- [ ] **Step 6: Run test to verify GREEN**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteModeServiceTest --rerun-tasks
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add \
  services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteMode.java \
  services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteModeService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteModeServiceTest.java
git commit -m "feat: add heavy audio taste mode builder"
```

---

### Task 2: Wire Taste Modes Into AudioTasteProfileService

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileService.java`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileServiceTest.java`
- Modify constructor call sites that create `AudioTasteProfileService.Profile` directly:
  - `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteScoringServiceTest.java`
  - `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminControllerWebMvcTest.java`

- [ ] **Step 1: Write failing profile integration tests**

Append these tests to `AudioTasteProfileServiceTest`.

```java
@Test
void shouldExposeTasteModesForHeavyProfile() {
    InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
    PmsUserLibraryStore libraryStore = libraryWithTracks(200, index -> {
        if (index <= 80) {
            return track("heavy-" + index, "Dance " + index, "Dance Artist", "tidal", features(0.82d, 0.74d, 0.80d));
        }
        if (index <= 150) {
            return track("heavy-" + index, "Calm " + index, "Calm Artist", "tidal",
                featuresWithShape(0.22d, 0.45d, 0.34d, 0.82d, 82.0d));
        }
        return track("heavy-" + index, "Mid " + index, "Mid Artist", "tidal",
            featuresWithShape(0.52d, 0.54d, 0.50d, 0.32d, 106.0d));
    });
    IntStream.rangeClosed(1, 20)
        .forEach(index -> eventStore.save(event("user-1", "track_saved", "heavy-" + index, 2.0d)));

    AudioTasteProfileService.Profile profile = new AudioTasteProfileService(
        libraryStore,
        eventStore,
        new InMemoryTrackAudioFeatureEvidenceStore(),
        new EventSignalWeights(),
        10,
        0.0d
    ).recompute("user-1", 500);

    assertThat(profile.profileType()).isEqualTo("heavy");
    assertThat(profile.tasteModes()).hasSizeGreaterThanOrEqualTo(3);
    assertThat(profile.warnings()).contains("Heavy audio taste modes are available for admin inspection.");
}

@Test
void shouldReturnEmptyTasteModesForStrongProfile() {
    AudioTasteProfileService.Profile profile = profileWithReadyTracks(199);

    assertThat(profile.profileType()).isEqualTo("strong");
    assertThat(profile.tasteModes()).isEmpty();
}
```

Add this helper next to existing `features(...)` helpers in the same test class:

```java
private PmsTrackAudioFeatures featuresWithShape(
    double energy,
    double valence,
    double danceability,
    double acousticness,
    double tempo
) {
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
        acousticness,
        danceability,
        energy,
        0.01d,
        0.12d,
        -8.0d,
        0.05d,
        tempo,
        valence,
        Instant.parse("2026-05-21T00:00:00Z")
    );
}
```

- [ ] **Step 2: Run tests to verify RED**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileServiceTest
```

Expected: FAIL because `Profile.tasteModes()` does not exist.

- [ ] **Step 3: Add `AudioTasteModeService` dependency and compute taste modes**

In `AudioTasteProfileService`, add a field:

```java
private final AudioTasteModeService audioTasteModeService;
```

Update the 4-argument convenience constructor to call a 7-argument constructor with a new service:

```java
this(
    libraryStore,
    eventStore,
    evidenceStore,
    eventSignalWeights,
    DEFAULT_MIN_POSITIVE_READY_TRACKS,
    DEFAULT_MIN_FEATURE_READY_RATIO,
    new AudioTasteModeService()
);
```

Update the Spring `@Autowired` constructor signature to include `AudioTasteModeService audioTasteModeService` after `double minFeatureReadyRatio`, and assign:

```java
this.audioTasteModeService = audioTasteModeService == null ? new AudioTasteModeService() : audioTasteModeService;
```

Add a constructor for tests that currently pass gates manually:

```java
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
        minPositiveReadyTracks,
        minFeatureReadyRatio,
        new AudioTasteModeService()
    );
}
```

Add the full constructor:

```java
public AudioTasteProfileService(
    PmsUserLibraryStore libraryStore,
    UserMusicEventStore eventStore,
    TrackAudioFeatureEvidenceStore evidenceStore,
    EventSignalWeights eventSignalWeights,
    int minPositiveReadyTracks,
    double minFeatureReadyRatio,
    AudioTasteModeService audioTasteModeService
) {
    this.libraryStore = libraryStore;
    this.eventStore = eventStore;
    this.evidenceStore = evidenceStore;
    this.eventSignalWeights = eventSignalWeights;
    this.minPositiveReadyTracks = Math.max(1, minPositiveReadyTracks);
    this.minFeatureReadyRatio = Math.max(0.0d, Math.min(1.0d, minFeatureReadyRatio));
    this.audioTasteModeService = audioTasteModeService == null ? new AudioTasteModeService() : audioTasteModeService;
}
```

In `recompute`, after `profileConfidence` is computed and before returning `new Profile(...)`, add:

```java
List<AudioTasteMode> tasteModes = audioTasteModeService.buildModes(
    profileType,
    profileConfidence,
    featureRows
);
if (!tasteModes.isEmpty()) {
    warnings.add("Heavy audio taste modes are available for admin inspection.");
}
```

Add `tasteModes` to the `new Profile(...)` call after `sourceQualityMix`.

Update the `Profile` record:

```java
public record Profile(
    String userId,
    String status,
    boolean audioTasteApplicable,
    String profileType,
    String profileFocus,
    double profileConfidence,
    Diversity diversity,
    SourceQualityMix sourceQualityMix,
    List<AudioTasteMode> tasteModes,
    int positiveTrackCount,
    int negativeTrackCount,
    int eventLimit,
    Centroid positiveCentroid,
    Centroid negativeCentroid,
    Coverage coverage,
    List<String> warnings,
    Instant recomputedAt
) {
    public Profile {
        tasteModes = tasteModes == null ? List.of() : List.copyOf(tasteModes);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
```

- [ ] **Step 4: Update direct `Profile` constructor calls in tests**

Every existing `new AudioTasteProfileService.Profile(...)` test fixture must pass `List.of()` after `sourceQualityMix`.

Example:

```java
new AudioTasteProfileService.Profile(
    "target-user",
    "ok",
    true,
    "ready",
    "balanced",
    0.62d,
    new AudioTasteProfileService.Diversity(8, "Artist A", 0.18d, 2),
    new AudioTasteProfileService.SourceQualityMix(0.72d, 0.18d, 0.04d, 0.06d, 0.0d),
    List.of(),
    12,
    1,
    100,
    ...
);
```

Use `rg -n "new AudioTasteProfileService\\.Profile" services/api/src/test/java` to find every call site.

- [ ] **Step 5: Run tests to verify GREEN**

Run:

```bash
cd services/api
./gradlew test \
  --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileServiceTest \
  --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteScoringServiceTest \
  --rerun-tasks
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add \
  services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileServiceTest.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteScoringServiceTest.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminControllerWebMvcTest.java
git commit -m "feat: expose heavy taste modes in audio profile"
```

---

### Task 3: Expose Taste Modes In Admin API JSON

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminController.java`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminControllerWebMvcTest.java`

- [ ] **Step 1: Write failing WebMvc assertions**

Update `shouldReturnAudioTasteProfile` in `AudioTasteAdminControllerWebMvcTest` to assert mode JSON:

```java
.andExpect(jsonPath("$.profile.taste_modes[0].mode_id").value("mode-1"))
.andExpect(jsonPath("$.profile.taste_modes[0].label").value("high_energy_bright_danceable"))
.andExpect(jsonPath("$.profile.taste_modes[0].track_count").value(78))
.andExpect(jsonPath("$.profile.taste_modes[0].confidence").value(0.74))
.andExpect(jsonPath("$.profile.taste_modes[0].centroid.energy").value(0.81))
.andExpect(jsonPath("$.profile.taste_modes[0].top_artists[0].artist_name").value("Artist A"))
.andExpect(jsonPath("$.profile.taste_modes[0].representative_tracks[0].distance_to_centroid").value(0.08))
```

Update the `profile()` fixture in the same test to use `profile_type="heavy"` and include one `AudioTasteMode`:

```java
List.of(new AudioTasteMode(
    "mode-1",
    "high_energy_bright_danceable",
    78,
    0.74d,
    new AudioTasteProfileService.Centroid(0.18d, 0.76d, 0.81d, 0.03d, 0.13d, 0.06d, 0.58d, 0.72d),
    List.of(new AudioTasteMode.TopArtist("Artist A", 12)),
    List.of(new AudioTasteMode.RepresentativeTrack(
        "pms-track-001",
        "Track Title",
        "Artist A",
        "tidal",
        0.08d
    ))
))
```

Add import:

```java
import io.myforevermusic.api.modules.recommendation.application.AudioTasteMode;
```

- [ ] **Step 2: Run test to verify RED**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.presentation.AudioTasteAdminControllerWebMvcTest
```

Expected: FAIL because `ProfileItem` does not expose `taste_modes`.

- [ ] **Step 3: Add DTO records and mapping**

In `AudioTasteAdminController.ProfileItem`, add:

```java
List<TasteModeItem> tasteModes,
```

Place it after `SourceQualityMixItem sourceQualityMix`.

In `ProfileItem.from(...)`, add:

```java
profile.tasteModes().stream().map(TasteModeItem::from).toList(),
```

Place it after `SourceQualityMixItem.from(profile.sourceQualityMix())`.

Add these records before `RowItem`:

```java
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TasteModeItem(
    String modeId,
    String label,
    int trackCount,
    double confidence,
    CentroidItem centroid,
    List<TopArtistItem> topArtists,
    List<RepresentativeTrackItem> representativeTracks
) {
    static TasteModeItem from(AudioTasteMode mode) {
        return new TasteModeItem(
            mode.modeId(),
            mode.label(),
            mode.trackCount(),
            mode.confidence(),
            CentroidItem.from(mode.centroid()),
            mode.topArtists().stream().map(TopArtistItem::from).toList(),
            mode.representativeTracks().stream().map(RepresentativeTrackItem::from).toList()
        );
    }
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TopArtistItem(String artistName, int trackCount) {
    static TopArtistItem from(AudioTasteMode.TopArtist artist) {
        return new TopArtistItem(artist.artistName(), artist.trackCount());
    }
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RepresentativeTrackItem(
    String trackId,
    String title,
    String artistName,
    String sourcePlatform,
    double distanceToCentroid
) {
    static RepresentativeTrackItem from(AudioTasteMode.RepresentativeTrack track) {
        return new RepresentativeTrackItem(
            track.trackId(),
            track.title(),
            track.artistName(),
            track.sourcePlatform(),
            track.distanceToCentroid()
        );
    }
}
```

Add import:

```java
import io.myforevermusic.api.modules.recommendation.application.AudioTasteMode;
```

- [ ] **Step 4: Add empty array assertion for non-heavy profile**

Add this WebMvc test:

```java
@Test
void shouldReturnEmptyTasteModesForNonHeavyProfile() throws Exception {
    when(profileService.recompute(eq("target-user"), eq(100))).thenReturn(nonHeavyProfile());

    mockMvc.perform(get("/api/v1/recommendations/admin/audio-taste/profile")
            .param("user_id", "admin")
            .param("target_user_id", "target-user")
            .param("event_limit", "100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.profile.profile_type").value("ready"))
        .andExpect(jsonPath("$.profile.taste_modes").isArray())
        .andExpect(jsonPath("$.profile.taste_modes").isEmpty());
}
```

Add helper:

```java
private AudioTasteProfileService.Profile nonHeavyProfile() {
    return new AudioTasteProfileService.Profile(
        "target-user",
        "ok",
        true,
        "ready",
        "balanced",
        0.62d,
        new AudioTasteProfileService.Diversity(8, "Artist A", 0.18d, 2),
        new AudioTasteProfileService.SourceQualityMix(0.72d, 0.18d, 0.04d, 0.06d, 0.0d),
        List.of(),
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
```

- [ ] **Step 5: Run test to verify GREEN**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.presentation.AudioTasteAdminControllerWebMvcTest --rerun-tasks
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add \
  services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminController.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminControllerWebMvcTest.java
git commit -m "feat: expose taste modes in audio taste admin api"
```

---

### Task 4: Docs And Serving Regression Verification

**Files:**
- Modify: `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`
- Modify: `docs/architecture/AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md`
- Test command only: `services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java`

- [ ] **Step 1: Update admin API docs**

In `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`, add this field bullet after `profile.source_quality_mix`:

```markdown
- `profile.taste_modes`: `heavy` profile에서만 채워지는 inspection-only 청취 모드 요약. non-heavy profile은 빈 배열입니다.
```

In the response example, add this block after `source_quality_mix`:

```json
    "taste_modes": [
      {
        "mode_id": "mode-1",
        "label": "high_energy_bright_danceable",
        "track_count": 78,
        "confidence": 0.74,
        "centroid": {
          "acousticness": 0.18,
          "danceability": 0.76,
          "energy": 0.81,
          "instrumentalness": 0.03,
          "liveness": 0.13,
          "speechiness": 0.06,
          "tempo": 0.58,
          "valence": 0.72
        },
        "top_artists": [
          { "artist_name": "Artist A", "track_count": 12 }
        ],
        "representative_tracks": [
          {
            "track_id": "pms-track-001",
            "title": "Track Title",
            "artist_name": "Artist A",
            "source_platform": "tidal",
            "distance_to_centroid": 0.08
          }
        ]
      }
    ],
```

Under `Current limits`, add:

```markdown
- `taste_modes`는 admin inspection-only 응답이며 아직 GMS ranking에는 반영하지 않습니다.
```

- [ ] **Step 2: Update architecture phase note**

In `docs/architecture/AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md`, under Phase 6 1차 구현 상태, add:

```markdown
- `Heavy Taste Modes v1`은 `profile_type=heavy` 사용자의 PMS audio feature rows를 deterministic bucket mode로 나누어 admin profile/dataset의 `taste_modes`에만 노출한다. GMS serving에는 아직 연결하지 않는다.
```

- [ ] **Step 3: Run docs diff check**

Run:

```bash
git diff -- docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md docs/architecture/AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md
```

Expected: Only the new `taste_modes` API/architecture notes are changed.

- [ ] **Step 4: Run serving regression tests**

Run:

```bash
cd services/api
./gradlew test \
  --tests '*AudioTaste*' \
  --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest \
  --rerun-tasks
```

Expected: PASS. The GMS preview tests must still show existing `audio-taste:v1` behavior and no new `taste_modes` serving dependency.

- [ ] **Step 5: Run whitespace check**

Run:

```bash
git diff --check
```

Expected: no output and exit code 0.

- [ ] **Step 6: Commit docs and final test updates**

```bash
git add \
  docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md \
  docs/architecture/AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md
git commit -m "docs: document heavy taste modes admin contract"
```

---

## Self-Review Checklist

- Spec coverage:
  - Heavy-only applicability: Task 1 and Task 2.
  - Deterministic bucket modes: Task 1.
  - Mode fields: Task 1 and Task 3.
  - Admin profile/dataset exposure: Task 2 and Task 3.
  - No GMS serving impact: Task 4 regression command.
  - Docs: Task 4.
- Placeholder scan:
  - Search the plan for incomplete-work markers before execution; the expected result is no matches.
- Type consistency:
  - Domain record is `AudioTasteMode`.
  - Profile field is `List<AudioTasteMode> tasteModes`.
  - JSON field is `taste_modes`.
  - Representative track field is `distance_to_centroid`.

## Execution Choice

Plan complete and saved to `docs/superpowers/plans/2026-05-21-heavy-taste-modes-v1.md`.

Two execution options:

1. **Subagent-Driven (recommended)** - dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** - execute tasks in this session using executing-plans, batch execution with checkpoints.
