# Taste Mode Affinity Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add inspection-only `taste_mode_affinity` to GMS preview items when `include_explanations=true`, without changing ranking, score, order, or model context.

**Architecture:** Add a focused `AudioTasteModeAffinityService` that compares a candidate audio feature vector with existing `AudioTasteMode` centroids. Extend the GMS preview response item contract with a nullable `tasteModeAffinity` field, then attach it in `GmsRecommendationPreviewService` only for explanation-enabled heavy profiles. Keep existing `audio-taste:v1` ranking behavior unchanged.

**Tech Stack:** Spring Boot 3.5, Java 21 records/services, JUnit 5, AssertJ, Gradle.

---

## File Structure

- Create `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteModeAffinityService.java`
  - Computes nearest `AudioTasteMode` for a candidate `AudioTasteTrackFeature`.
  - Owns distance math, tie-breaks, similarity rounding, and mode explanation tokens.
- Create `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteModeAffinityServiceTest.java`
  - Unit tests for heavy gating, nearest mode selection, tie-breaks, and unusable candidates.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/gms/presentation/GmsRecommendationPreviewResponse.java`
  - Add nullable `TasteModeAffinityItem tasteModeAffinity` to `RecommendationItem`.
  - Preserve existing constructors and `withAxisEvidence(...)` behavior.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewService.java`
  - Inject `AudioTasteModeAffinityService`.
  - Reuse/compute audio taste profile for explanation-only affinity.
  - Attach affinity after ranking is complete and before response item creation.
- Modify `services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java`
  - Add GMS preview tests for `include_explanations=true/false`, order stability, and context stability.
  - Update service constructors to pass `AudioTasteModeAffinityService`.
- Modify docs:
  - `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`
  - `docs/superpowers/specs/2026-05-21-taste-mode-affinity-preview-design.md`

Verification commands:

```bash
cd services/api
./gradlew test --tests '*AudioTaste*' --tests '*GmsRecommendationPreviewServiceTest*' --rerun-tasks
git diff --check
```

---

### Task 1: Add AudioTasteModeAffinityService

**Files:**
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteModeAffinityService.java`
- Create: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteModeAffinityServiceTest.java`

- [ ] **Step 1: Write failing affinity service tests**

Create `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteModeAffinityServiceTest.java`.

```java
package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AudioTasteModeAffinityServiceTest {

    private final AudioTasteModeAffinityService service = new AudioTasteModeAffinityService();

    @Test
    void shouldReturnNearestModeForHeavyProfileCandidate() {
        AudioTasteProfileService.Profile profile = profile(
            "heavy",
            List.of(
                mode("mode-1", "high_energy_bright_danceable", 0.18d, 0.76d, 0.81d, 0.03d, 0.13d, 0.06d, 0.58d, 0.72d, 0.74d, 78),
                mode("mode-2", "low_energy_dark_acoustic_slow", 0.82d, 0.32d, 0.24d, 0.01d, 0.12d, 0.04d, 0.18d, 0.30d, 0.68d, 65)
            )
        );

        AudioTasteModeAffinityService.TasteModeAffinity affinity = service.findNearestMode(
            profile,
            candidate("candidate-1", 0.20d, 0.78d, 0.82d, 0.02d, 0.12d, 0.05d, 142.0d, 0.70d)
        ).orElseThrow();

        assertThat(affinity.applied()).isTrue();
        assertThat(affinity.modeId()).isEqualTo("mode-1");
        assertThat(affinity.label()).isEqualTo("high_energy_bright_danceable");
        assertThat(affinity.similarity()).isGreaterThan(0.80d);
        assertThat(affinity.distance()).isBetween(0.0d, 0.20d);
        assertThat(affinity.tokens()).contains("mode_energy_match", "mode_valence_match", "mode_danceability_match");
    }

    @Test
    void shouldPreferHigherConfidenceThenTrackCountThenModeIdOnDistanceTie() {
        AudioTasteProfileService.Profile profile = profile(
            "heavy",
            List.of(
                mode("mode-b", "same_shape_lower_confidence", 0.20d, 0.70d, 0.80d, 0.01d, 0.10d, 0.05d, 0.50d, 0.70d, 0.60d, 120),
                mode("mode-a", "same_shape_higher_confidence", 0.20d, 0.70d, 0.80d, 0.01d, 0.10d, 0.05d, 0.50d, 0.70d, 0.80d, 80)
            )
        );

        AudioTasteModeAffinityService.TasteModeAffinity affinity = service.findNearestMode(
            profile,
            candidate("candidate-1", 0.20d, 0.70d, 0.80d, 0.01d, 0.10d, 0.05d, 130.0d, 0.70d)
        ).orElseThrow();

        assertThat(affinity.modeId()).isEqualTo("mode-a");
    }

    @Test
    void shouldReturnEmptyForNonHeavyProfile() {
        AudioTasteProfileService.Profile profile = profile(
            "strong",
            List.of(mode("mode-1", "high_energy_bright_danceable", 0.18d, 0.76d, 0.81d, 0.03d, 0.13d, 0.06d, 0.58d, 0.72d, 0.74d, 78))
        );

        assertThat(service.findNearestMode(
            profile,
            candidate("candidate-1", 0.20d, 0.78d, 0.82d, 0.02d, 0.12d, 0.05d, 142.0d, 0.70d)
        )).isEmpty();
    }

    @Test
    void shouldReturnEmptyForUnusableCandidate() {
        AudioTasteProfileService.Profile profile = profile(
            "heavy",
            List.of(mode("mode-1", "high_energy_bright_danceable", 0.18d, 0.76d, 0.81d, 0.03d, 0.13d, 0.06d, 0.58d, 0.72d, 0.74d, 78))
        );

        assertThat(service.findNearestMode(profile, new AudioTasteTrackFeature(
            "pms_user_track",
            "candidate-1",
            "Candidate",
            "Artist",
            "spotify",
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
        ))).isEmpty();
    }

    private AudioTasteProfileService.Profile profile(String profileType, List<AudioTasteMode> tasteModes) {
        return new AudioTasteProfileService.Profile(
            "user-1",
            "ok",
            true,
            profileType,
            "balanced",
            0.82d,
            new AudioTasteProfileService.Diversity(12, "Artist A", 0.18d, 2),
            new AudioTasteProfileService.SourceQualityMix(1.0d, 0.0d, 0.0d, 0.0d, 0.0d),
            tasteModes,
            80,
            2,
            500,
            new AudioTasteProfileService.Centroid(0.20d, 0.70d, 0.80d, 0.01d, 0.10d, 0.05d, 0.50d, 0.70d),
            AudioTasteProfileService.Centroid.empty(),
            new AudioTasteProfileService.Coverage(220, 220, 1.0d, 0, 0),
            List.of(),
            Instant.parse("2026-05-21T00:00:00Z")
        );
    }

    private AudioTasteMode mode(
        String modeId,
        String label,
        double acousticness,
        double danceability,
        double energy,
        double instrumentalness,
        double liveness,
        double speechiness,
        double tempo,
        double valence,
        double confidence,
        int trackCount
    ) {
        return new AudioTasteMode(
            modeId,
            label,
            trackCount,
            confidence,
            new AudioTasteProfileService.Centroid(
                acousticness,
                danceability,
                energy,
                instrumentalness,
                liveness,
                speechiness,
                tempo,
                valence
            ),
            List.of(),
            List.of()
        );
    }

    private AudioTasteTrackFeature candidate(
        String trackId,
        double acousticness,
        double danceability,
        double energy,
        double instrumentalness,
        double liveness,
        double speechiness,
        double tempo,
        double valence
    ) {
        return new AudioTasteTrackFeature(
            "pms_user_track",
            trackId,
            "Candidate",
            "Artist",
            "spotify",
            "reccobeats_lookup",
            true,
            1.0d,
            "provider",
            acousticness,
            danceability,
            energy,
            instrumentalness,
            liveness,
            speechiness,
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
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteModeAffinityServiceTest
```

Expected: FAIL at compile time because `AudioTasteModeAffinityService` does not exist.

- [ ] **Step 3: Add minimal affinity service**

Create `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteModeAffinityService.java`.

```java
package io.myforevermusic.api.modules.recommendation.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AudioTasteModeAffinityService {

    private static final String PROFILE_HEAVY = "heavy";

    public Optional<TasteModeAffinity> findNearestMode(
        AudioTasteProfileService.Profile profile,
        AudioTasteTrackFeature candidate
    ) {
        if (profile == null || !PROFILE_HEAVY.equals(profile.profileType())) {
            return Optional.empty();
        }
        if (profile.tasteModes() == null || profile.tasteModes().isEmpty()) {
            return Optional.empty();
        }
        if (candidate == null || !candidate.usable()) {
            return Optional.empty();
        }

        return profile.tasteModes().stream()
            .map(mode -> new ModeDistance(mode, normalizedDistance(candidate, mode.centroid())))
            .min(Comparator
                .comparingDouble(ModeDistance::distance)
                .thenComparing((ModeDistance distance) -> distance.mode().confidence(), Comparator.reverseOrder())
                .thenComparing((ModeDistance distance) -> distance.mode().trackCount(), Comparator.reverseOrder())
                .thenComparing(distance -> normalizeText(distance.mode().modeId())))
            .map(distance -> toAffinity(distance.mode(), distance.distance(), candidate));
    }

    private TasteModeAffinity toAffinity(AudioTasteMode mode, double distance, AudioTasteTrackFeature candidate) {
        return new TasteModeAffinity(
            true,
            mode.modeId(),
            mode.label(),
            round(clamp(1.0d - distance)),
            round(clamp(distance)),
            tokens(candidate, mode.centroid())
        );
    }

    private List<String> tokens(AudioTasteTrackFeature candidate, AudioTasteProfileService.Centroid centroid) {
        List<String> tokens = new ArrayList<>();
        if (Math.abs(value(candidate.energy()) - centroid.energy()) <= 0.15d) {
            tokens.add("mode_energy_match");
        }
        if (Math.abs(value(candidate.valence()) - centroid.valence()) <= 0.15d) {
            tokens.add("mode_valence_match");
        }
        if (Math.abs(value(candidate.danceability()) - centroid.danceability()) <= 0.15d) {
            tokens.add("mode_danceability_match");
        }
        if (Math.abs(normalizeTempo(candidate.tempo()) - centroid.tempo()) <= 0.15d) {
            tokens.add("mode_tempo_match");
        }
        if (tokens.isEmpty()) {
            tokens.add("mode_profile_distance");
        }
        return List.copyOf(tokens);
    }

    private double normalizedDistance(AudioTasteTrackFeature candidate, AudioTasteProfileService.Centroid centroid) {
        double sum = 0.0d;
        sum += squared(candidate.acousticness(), centroid.acousticness());
        sum += squared(candidate.danceability(), centroid.danceability());
        sum += squared(candidate.energy(), centroid.energy());
        sum += squared(candidate.instrumentalness(), centroid.instrumentalness());
        sum += squared(candidate.liveness(), centroid.liveness());
        sum += squared(candidate.speechiness(), centroid.speechiness());
        sum += squared(normalizeTempo(candidate.tempo()), centroid.tempo());
        sum += squared(candidate.valence(), centroid.valence());
        return Math.min(1.0d, Math.sqrt(sum / 8.0d));
    }

    private double squared(Double left, double right) {
        double value = value(left);
        return (value - right) * (value - right);
    }

    private double squared(double left, double right) {
        return (left - right) * (left - right);
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

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    public record TasteModeAffinity(
        boolean applied,
        String modeId,
        String label,
        double similarity,
        double distance,
        List<String> tokens
    ) {
        public TasteModeAffinity {
            tokens = tokens == null ? List.of() : List.copyOf(tokens);
        }
    }

    private record ModeDistance(AudioTasteMode mode, double distance) {
    }
}
```

- [ ] **Step 4: Run test to verify GREEN**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteModeAffinityServiceTest --rerun-tasks
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add \
  services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteModeAffinityService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteModeAffinityServiceTest.java
git commit -m "feat: add taste mode affinity service"
```

---

### Task 2: Extend GMS Preview Response Contract

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/gms/presentation/GmsRecommendationPreviewResponse.java`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java`

- [ ] **Step 1: Write failing response contract test**

Add this assertion to an existing GMS preview test fixture after Task 1 exists, preferably a new focused test in `GmsRecommendationPreviewServiceTest` that serializes a response item through Jackson.

```java
@Test
void shouldSerializeTasteModeAffinityInRecommendationItem() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    GmsRecommendationPreviewResponse response = new GmsRecommendationPreviewResponse(
        "request-001",
        Instant.parse("2026-05-21T00:00:00Z"),
        "api",
        "ok",
        new GmsRecommendationPreviewResponse.RecommendationContext(
            "gms-hybrid-blend",
            "rule-based-preview-v1",
            "gms",
            "upbeat",
            4,
            List.of()
        ),
        new GmsRecommendationPreviewResponse.RecommendationInputSummary(
            "user-1",
            "playlist-1",
            0,
            0,
            0,
            2,
            1
        ),
        List.of(new GmsRecommendationPreviewResponse.RecommendationItem(
            1,
            "track-1",
            "Track",
            "Artist",
            "spotify",
            "playlist-1",
            "Playlist",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0.72d,
            "gms",
            4,
            "Reason.",
            List.of(),
            new GmsRecommendationPreviewResponse.TasteModeAffinityItem(
                true,
                "mode-1",
                "high_energy_bright_danceable",
                0.86d,
                0.14d,
                List.of("mode_energy_match")
            )
        )),
        List.of()
    );

    String json = mapper.writeValueAsString(response);

    assertThat(json).contains("\"taste_mode_affinity\"");
    assertThat(json).contains("\"mode_id\":\"mode-1\"");
    assertThat(json).contains("\"mode_energy_match\"");
}
```

- [ ] **Step 2: Run test to verify RED**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteModeAffinityServiceTest
```

Expected: FAIL at compile time because `TasteModeAffinityItem` and the extended `RecommendationItem` constructor do not exist.

- [ ] **Step 3: Add response record and preserve constructors**

In `services/api/src/main/java/io/myforevermusic/api/modules/gms/presentation/GmsRecommendationPreviewResponse.java`, import the domain affinity record:

```java
import io.myforevermusic.api.modules.recommendation.application.AudioTasteModeAffinityService;
```

Update `RecommendationItem` by adding `TasteModeAffinityItem tasteModeAffinity` after `List<AxisEvidence> axisEvidence`.

The canonical record header should end like this:

```java
String reason,
List<AxisEvidence> axisEvidence,
TasteModeAffinityItem tasteModeAffinity
```

Update the compact constructor to keep existing behavior:

```java
public RecommendationItem {
    if (
        (audioFeatureTrackId == null || audioFeatureTrackId.isBlank())
            && spotifyTrackId != null
            && !spotifyTrackId.isBlank()
    ) {
        audioFeatureTrackId = spotifyTrackId;
    }
    if (
        (spotifyTrackId == null || spotifyTrackId.isBlank())
            && audioFeatureTrackId != null
            && !audioFeatureTrackId.isBlank()
    ) {
        spotifyTrackId = audioFeatureTrackId;
    }
    if (axisEvidence == null) {
        axisEvidence = List.of();
    }
}
```

Add an overloaded constructor that preserves all existing full constructor call sites:

```java
public RecommendationItem(
    Integer rank,
    String trackId,
    String title,
    String artistName,
    String sourcePlatform,
    String sourcePlaylistId,
    String sourcePlaylistTitle,
    String albumTitle,
    String albumImageUrl,
    String platformExternalUrl,
    String platformUri,
    String previewUrl,
    String spotifyTrackId,
    String audioFeatureTrackId,
    Integer durationMs,
    Double score,
    String sourceSpace,
    Integer energyLevel,
    String reason,
    List<AxisEvidence> axisEvidence
) {
    this(
        rank,
        trackId,
        title,
        artistName,
        sourcePlatform,
        sourcePlaylistId,
        sourcePlaylistTitle,
        albumTitle,
        albumImageUrl,
        platformExternalUrl,
        platformUri,
        previewUrl,
        spotifyTrackId,
        audioFeatureTrackId,
        durationMs,
        score,
        sourceSpace,
        energyLevel,
        reason,
        axisEvidence,
        null
    );
}
```

Update existing short constructors so they continue to delegate to the constructor above. Update `withAxisEvidence(...)` to preserve affinity:

```java
public RecommendationItem withAxisEvidence(List<AxisEvidence> evidence) {
    return new RecommendationItem(
        rank,
        trackId,
        title,
        artistName,
        sourcePlatform,
        sourcePlaylistId,
        sourcePlaylistTitle,
        albumTitle,
        albumImageUrl,
        platformExternalUrl,
        platformUri,
        previewUrl,
        spotifyTrackId,
        audioFeatureTrackId,
        durationMs,
        score,
        sourceSpace,
        energyLevel,
        reason,
        evidence == null ? List.of() : evidence,
        tasteModeAffinity
    );
}
```

Add a helper for later GMS integration:

```java
public RecommendationItem withTasteModeAffinity(TasteModeAffinityItem affinity) {
    return new RecommendationItem(
        rank,
        trackId,
        title,
        artistName,
        sourcePlatform,
        sourcePlaylistId,
        sourcePlaylistTitle,
        albumTitle,
        albumImageUrl,
        platformExternalUrl,
        platformUri,
        previewUrl,
        spotifyTrackId,
        audioFeatureTrackId,
        durationMs,
        score,
        sourceSpace,
        energyLevel,
        reason,
        axisEvidence,
        affinity
    );
}
```

Add the nested response record inside `GmsRecommendationPreviewResponse`:

```java
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TasteModeAffinityItem(
    boolean applied,
    String modeId,
    String label,
    double similarity,
    double distance,
    List<String> tokens
) {
    public TasteModeAffinityItem {
        tokens = tokens == null ? List.of() : List.copyOf(tokens);
    }

    public static TasteModeAffinityItem from(AudioTasteModeAffinityService.TasteModeAffinity affinity) {
        if (affinity == null) {
            return null;
        }
        return new TasteModeAffinityItem(
            affinity.applied(),
            affinity.modeId(),
            affinity.label(),
            affinity.similarity(),
            affinity.distance(),
            affinity.tokens()
        );
    }
}
```

- [ ] **Step 4: Run test to verify GREEN**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteModeAffinityServiceTest --rerun-tasks
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add \
  services/api/src/main/java/io/myforevermusic/api/modules/gms/presentation/GmsRecommendationPreviewResponse.java \
  services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java
git commit -m "feat: expose taste mode affinity response contract"
```

---

### Task 3: Attach Affinity In GMS Preview Explanations

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewService.java`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java`

- [ ] **Step 1: Write failing GMS integration tests**

Add two tests to `GmsRecommendationPreviewServiceTest`.

The first test proves affinity appears when explanations are enabled:

```java
@Test
void shouldAttachTasteModeAffinityWhenExplanationsAreIncludedForHeavyProfile() {
    InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
    InMemoryPmsUserLibraryStore pmsUserLibraryStore = new InMemoryPmsUserLibraryStore();
    InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
    pmsUserLibraryStore.savePlaylists(
        "audio-user",
        List.of(sampleHeavyLibraryPlaylistForTasteModeAffinity())
    );
    for (int index = 1; index <= 20; index++) {
        eventStore.save(audioTasteEvent("audio-user", "heavy-mode-dance-" + index, index));
    }
    AudioTasteProfileService audioTasteProfileService = new AudioTasteProfileService(
        pmsUserLibraryStore,
        eventStore,
        new InMemoryTrackAudioFeatureEvidenceStore(),
        new EventSignalWeights(),
        10,
        0.0d
    );
    GmsRecommendationPreviewService service = serviceForAudioTaste(
        authAccountStore,
        pmsUserLibraryStore,
        audioTasteProfileService
    );

    GmsRecommendationPreviewResponse response = service.previewRecommendations(new GmsRecommendationPreviewRequest(
        "request-taste-mode-affinity",
        "audio-user",
        "playlist-heavy-affinity",
        "gms",
        "upbeat",
        4,
        2,
        2,
        List.of(),
        List.of(),
        List.of(),
        true
    ));

    assertThat(response.items()).hasSize(2);
    assertThat(response.items()).extracting(GmsRecommendationPreviewResponse.RecommendationItem::trackId)
        .allMatch(trackId -> ((String) trackId).startsWith("heavy-mode-"));
    assertThat(response.items().getFirst().tasteModeAffinity()).isNotNull();
    assertThat(response.items().getFirst().tasteModeAffinity().applied()).isTrue();
    assertThat(response.items().getFirst().tasteModeAffinity().modeId()).startsWith("mode-");
    assertThat(response.items().getFirst().tasteModeAffinity().tokens()).isNotEmpty();
    assertThat(response.context().engine()).doesNotContain("taste-mode");
}
```

The second test proves explanations gate the field and order remains stable:

```java
@Test
void shouldNotAttachTasteModeAffinityWhenExplanationsAreDisabledAndShouldKeepOrderStable() {
    InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
    InMemoryPmsUserLibraryStore pmsUserLibraryStore = new InMemoryPmsUserLibraryStore();
    InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
    pmsUserLibraryStore.savePlaylists(
        "audio-user",
        List.of(sampleHeavyLibraryPlaylistForTasteModeAffinity())
    );
    for (int index = 1; index <= 20; index++) {
        eventStore.save(audioTasteEvent("audio-user", "heavy-mode-dance-" + index, index));
    }
    AudioTasteProfileService audioTasteProfileService = new AudioTasteProfileService(
        pmsUserLibraryStore,
        eventStore,
        new InMemoryTrackAudioFeatureEvidenceStore(),
        new EventSignalWeights(),
        10,
        0.0d
    );
    GmsRecommendationPreviewService service = serviceForAudioTaste(
        authAccountStore,
        pmsUserLibraryStore,
        audioTasteProfileService
    );

    GmsRecommendationPreviewResponse enabledResponse = service.previewRecommendations(new GmsRecommendationPreviewRequest(
        "request-taste-mode-affinity-enabled",
        "audio-user",
        "playlist-heavy-affinity",
        "gms",
        "upbeat",
        4,
        2,
        2,
        List.of(),
        List.of(),
        List.of(),
        true
    ));
    GmsRecommendationPreviewResponse disabledResponse = service.previewRecommendations(new GmsRecommendationPreviewRequest(
        "request-taste-mode-affinity-disabled",
        "audio-user",
        "playlist-heavy-affinity",
        "gms",
        "upbeat",
        4,
        2,
        2,
        List.of(),
        List.of(),
        List.of(),
        false
    ));

    assertThat(disabledResponse.items()).extracting(GmsRecommendationPreviewResponse.RecommendationItem::trackId)
        .containsExactlyElementsOf(enabledResponse.items().stream()
            .map(GmsRecommendationPreviewResponse.RecommendationItem::trackId)
            .toList());
    assertThat(disabledResponse.items()).allSatisfy(item -> assertThat(item.tasteModeAffinity()).isNull());
}
```

Add this helper so all service construction includes the new dependency in one place:

```java
private GmsRecommendationPreviewService serviceForAudioTaste(
    InMemoryAuthAccountStore authAccountStore,
    InMemoryPmsUserLibraryStore pmsUserLibraryStore,
    AudioTasteProfileService audioTasteProfileService
) {
    return new GmsRecommendationPreviewService(
        new TwoItemAiRecommendationPreviewClient(),
        Optional.empty(),
        authAccountStore,
        new InMemoryLastFmScrobbleStore(),
        pmsUserLibraryStore,
        Optional.empty(),
        new RecommendationSnapshotService(new InMemoryRecommendationSnapshotStore()),
        new InMemoryRecommendationAuditLogStore(),
        new PlaylistQualityEvaluator(),
        new InMemoryUserPersonalizationProfileStore(),
        new RecommendationReranker(),
        audioTasteProfileService,
        new AudioTasteScoringService(),
        new AudioTasteModeAffinityService(),
        new ColdStartFallbackService(authAccountStore, pmsUserLibraryStore, Optional.empty())
    );
}
```

Add this heavy library fixture. It creates 200 usable rows so `profile_type=heavy` and `tasteModes()` are non-empty:

```java
private PmsUserLibraryStore.LibraryPlaylistState sampleHeavyLibraryPlaylistForTasteModeAffinity() {
    List<PmsUserLibraryStore.LibraryTrackState> tracks = new ArrayList<>();
    for (int index = 1; index <= 100; index++) {
        tracks.add(audioTasteLibraryTrack(
            "heavy-mode-dance-" + index,
            "Heavy Dance " + index,
            "Dance Artist",
            audioTasteFeatures("spotify-heavy-mode-dance-" + index, 0.18d, 0.82d, 0.80d, 0.01d, 0.12d, 0.05d, 128.0d, 0.74d)
        ));
    }
    for (int index = 1; index <= 100; index++) {
        tracks.add(audioTasteLibraryTrack(
            "heavy-mode-calm-" + index,
            "Heavy Calm " + index,
            "Calm Artist",
            audioTasteFeatures("spotify-heavy-mode-calm-" + index, 0.82d, 0.24d, 0.34d, 0.01d, 0.12d, 0.05d, 82.0d, 0.35d)
        ));
    }
    return new PmsUserLibraryStore.LibraryPlaylistState(
        "audio-user",
        "playlist-heavy-affinity",
        "spotify-playlist-heavy-affinity",
        "Heavy Affinity",
        "spotify",
        "Forever Listener",
        "Heavy taste affinity fixture.",
        null,
        "https://open.spotify.com/playlist/spotify-playlist-heavy-affinity",
        "spotify:playlist:spotify-playlist-heavy-affinity",
        Instant.parse("2026-05-21T00:00:00Z"),
        tracks
    );
}
```

- [ ] **Step 2: Run test to verify RED**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest
```

Expected: FAIL because `GmsRecommendationPreviewService` does not inject/use `AudioTasteModeAffinityService` and items have `tasteModeAffinity=null`.

- [ ] **Step 3: Inject affinity service**

In `GmsRecommendationPreviewService`, import:

```java
import io.myforevermusic.api.modules.recommendation.application.AudioTasteModeAffinityService;
```

Add field:

```java
private final AudioTasteModeAffinityService audioTasteModeAffinityService;
```

Update constructor to accept the new dependency between `AudioTasteScoringService` and `ColdStartFallbackService`:

```java
AudioTasteScoringService audioTasteScoringService,
AudioTasteModeAffinityService audioTasteModeAffinityService,
ColdStartFallbackService coldStartFallbackService
```

Assign:

```java
this.audioTasteModeAffinityService = audioTasteModeAffinityService;
```

Update every `new GmsRecommendationPreviewService(...)` call in tests to pass `new AudioTasteModeAffinityService()` before `new ColdStartFallbackService(...)`.

- [ ] **Step 4: Compute profile once for ranking and explanation affinity**

In `projectPlayableItems(...)`, replace the current audio ranking block:

```java
rankedCandidates = applyAudioTasteRanking(
    request,
    rankedCandidates,
    enrichmentWarnings,
    appliedAudioTasteModelVersions
);
rankedCandidates = rankedCandidates.stream()
    .limit(aiItems.size())
    .toList();
```

with:

```java
AudioTasteProfileService.Profile audioTasteProfile = resolveAudioTasteProfile(request, rankedCandidates);
rankedCandidates = applyAudioTasteRanking(
    request,
    rankedCandidates,
    audioTasteProfile,
    enrichmentWarnings,
    appliedAudioTasteModelVersions
);
rankedCandidates = applyTasteModeAffinity(request, rankedCandidates, audioTasteProfile);
rankedCandidates = rankedCandidates.stream()
    .limit(aiItems.size())
    .toList();
```

Add:

```java
private AudioTasteProfileService.Profile resolveAudioTasteProfile(
    GmsRecommendationPreviewRequest request,
    List<RankedLibraryCandidate> rankedCandidates
) {
    if (request.userId() == null || request.userId().isBlank() || rankedCandidates.isEmpty()) {
        return null;
    }
    if (rankedCandidates.size() < 2 && !request.includeExplanations()) {
        return null;
    }
    return audioTasteProfileService.recompute(request.userId(), null);
}
```

- [ ] **Step 5: Make audio ranking use the resolved profile**

Change `applyAudioTasteRanking(...)` signature to:

```java
private List<RankedLibraryCandidate> applyAudioTasteRanking(
    GmsRecommendationPreviewRequest request,
    List<RankedLibraryCandidate> rankedCandidates,
    AudioTasteProfileService.Profile profile,
    List<String> enrichmentWarnings,
    List<String> appliedAudioTasteModelVersions
)
```

Replace the internal profile recompute:

```java
AudioTasteProfileService.Profile profile = audioTasteProfileService.recompute(request.userId(), null);
```

with an early return:

```java
if (profile == null || !profile.audioTasteApplicable()) {
    return rankedCandidates;
}
```

Keep the existing `rankedCandidates.size() < 2` guard so ranking remains unchanged for a single candidate:

```java
if (rankedCandidates.size() < 2 || request.userId() == null || request.userId().isBlank()) {
    return rankedCandidates;
}
```

- [ ] **Step 6: Attach taste mode affinity after ranking**

Add:

```java
private List<RankedLibraryCandidate> applyTasteModeAffinity(
    GmsRecommendationPreviewRequest request,
    List<RankedLibraryCandidate> rankedCandidates,
    AudioTasteProfileService.Profile profile
) {
    if (!request.includeExplanations() || profile == null || rankedCandidates.isEmpty()) {
        return rankedCandidates;
    }
    return rankedCandidates.stream()
        .map(ranked -> {
            AudioTasteTrackFeature feature = toAudioTasteTrackFeature(ranked.candidate());
            return ranked.withTasteModeAffinity(
                audioTasteModeAffinityService.findNearestMode(profile, feature).orElse(null)
            );
        })
        .toList();
}
```

Update `toRecommendationItem(...)` to pass the mapped response item:

```java
GmsRecommendationPreviewResponse.TasteModeAffinityItem.from(rankedCandidate.tasteModeAffinity())
```

as the final constructor argument when building `RecommendationItem`.

- [ ] **Step 7: Extend RankedLibraryCandidate**

Update private record:

```java
private record RankedLibraryCandidate(
    LibraryCandidateTrack candidate,
    double affinityScore,
    boolean sasrecRanked,
    boolean audioTasteRanked,
    List<String> audioTasteTokens,
    AudioTasteModeAffinityService.TasteModeAffinity tasteModeAffinity
) {
    private RankedLibraryCandidate {
        audioTasteTokens = audioTasteTokens == null ? List.of() : List.copyOf(audioTasteTokens);
    }

    private RankedLibraryCandidate(LibraryCandidateTrack candidate, double affinityScore) {
        this(candidate, affinityScore, false, false, List.of(), null);
    }

    private RankedLibraryCandidate(LibraryCandidateTrack candidate, double affinityScore, boolean sasrecRanked) {
        this(candidate, affinityScore, sasrecRanked, false, List.of(), null);
    }

    private RankedLibraryCandidate withAudioTasteScore(
        double nextAffinityScore,
        List<String> nextAudioTasteTokens
    ) {
        return new RankedLibraryCandidate(
            candidate,
            nextAffinityScore,
            sasrecRanked,
            true,
            nextAudioTasteTokens,
            tasteModeAffinity
        );
    }

    private RankedLibraryCandidate withTasteModeAffinity(
        AudioTasteModeAffinityService.TasteModeAffinity nextTasteModeAffinity
    ) {
        return new RankedLibraryCandidate(
            candidate,
            affinityScore,
            sasrecRanked,
            audioTasteRanked,
            audioTasteTokens,
            nextTasteModeAffinity
        );
    }
}
```

- [ ] **Step 8: Run test to verify GREEN**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteModeAffinityServiceTest --rerun-tasks
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add \
  services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java
git commit -m "feat: attach taste mode affinity to gms explanations"
```

---

### Task 4: Docs And Regression Verification

**Files:**
- Modify: `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`
- Modify: `docs/superpowers/specs/2026-05-21-taste-mode-affinity-preview-design.md`

- [ ] **Step 1: Update API docs**

In `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`, under the GMS Serving section, add:

```markdown
When `include_explanations=true`, heavy audio taste profiles may also include inspection-only `items[].taste_mode_affinity`. This field identifies the nearest `taste_modes[]` entry for the playable candidate. It does not change item order, score, `context.engine`, or audit model version.
```

Add example snippet:

```json
{
  "track_id": "pms-track-001",
  "taste_mode_affinity": {
    "applied": true,
    "mode_id": "mode-1",
    "label": "high_energy_bright_danceable",
    "similarity": 0.86,
    "distance": 0.14,
    "tokens": ["mode_energy_match", "mode_tempo_match"]
  }
}
```

- [ ] **Step 2: Update design spec status note**

In `docs/superpowers/specs/2026-05-21-taste-mode-affinity-preview-design.md`, add this implementation status near the top:

```markdown
구현 상태: planned in this document; implementation tracks `docs/superpowers/plans/2026-05-21-taste-mode-affinity-preview.md`.
```

After implementation, update it to:

```markdown
구현 상태: implemented in Spring API. `taste_mode_affinity` is exposed only when `include_explanations=true`.
```

- [ ] **Step 3: Run full regression tests**

Run:

```bash
cd services/api
./gradlew test --tests '*AudioTaste*' --tests '*GmsRecommendationPreviewServiceTest*' --rerun-tasks
```

Expected: PASS. Existing `audio-taste:v1` boost behavior remains unchanged, and no new taste-mode model marker appears in `context.engine`.

- [ ] **Step 4: Run whitespace check**

Run:

```bash
git diff --check
```

Expected: no output and exit code 0.

- [ ] **Step 5: Commit docs**

```bash
git add \
  docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md \
  docs/superpowers/specs/2026-05-21-taste-mode-affinity-preview-design.md
git commit -m "docs: document taste mode affinity preview contract"
```

---

## Self-Review Checklist

- Spec coverage:
  - `include_explanations=true` gating: Task 3.
  - Heavy-only and non-empty mode gating: Task 1 and Task 3.
  - Nullable `taste_mode_affinity` response field: Task 2 and Task 3.
  - No ranking/order/context impact: Task 3 tests and Task 4 regression.
  - Docs: Task 4.
- Type consistency:
  - Domain record is `AudioTasteModeAffinityService.TasteModeAffinity`.
  - Response record is `GmsRecommendationPreviewResponse.TasteModeAffinityItem`.
  - JSON field is `taste_mode_affinity`.
  - Tokens use `mode_*` prefixes.
- Placeholder scan:
  - Search this plan for incomplete-work markers before execution; expected result is no matches.

## Execution Choice

Plan complete and saved to `docs/superpowers/plans/2026-05-21-taste-mode-affinity-preview.md`.

Two execution options:

1. **Subagent-Driven (recommended)** - dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** - execute tasks in this session using executing-plans, batch execution with checkpoints.
