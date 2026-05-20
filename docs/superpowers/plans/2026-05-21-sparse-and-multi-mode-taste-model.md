# Sparse And Multi-Mode Taste Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend Audio Taste Model v1 so sparse users and heavy users are classified with explicit profile type, confidence, diversity, and source quality before GMS applies audio taste boost.

**Architecture:** Keep the first implementation inside Spring API, extending the current `AudioTasteProfileService` and `AudioTasteScoringService` rather than adding a new training service. The model classifies profile type from PMS feature-ready library size, keeps centroid scoring, and uses profile confidence/focus to dampen GMS audio taste boost. No DB migration or FastAPI training endpoint is part of this plan.

**Tech Stack:** Spring Boot 3.5, Java 21 records/services, JUnit 5, AssertJ, Spring WebMvc tests, Gradle.

---

## File Structure

- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileService.java`
  - Add `profileType`, `profileConfidence`, `profileFocus`, `Diversity`, and `SourceQualityMix` to `Profile`.
  - Compute profile type from PMS library usable feature count, not only positive event count.
  - Compute artist/source diversity, source tier mix, and confidence.
- Modify `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileServiceTest.java`
  - Add profile type boundary tests.
  - Add artist narrow and low quality profile focus tests.
  - Add confidence sanity tests.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteScoringService.java`
  - Scale score influence by profile type, confidence, and focus.
  - Add focus reason tokens.
- Modify `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteScoringServiceTest.java`
  - Add weak/ready/strong influence tests.
  - Add narrow/low-quality token tests.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewService.java`
  - Use the new score `maxBoostWeight`.
  - Add profile type/confidence context to audio taste warnings.
- Modify `services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java`
  - Update existing constructor calls for the expanded `Profile`.
  - Add a weak profile boost cap regression.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminController.java`
  - Expose new profile fields in snake_case.
- Modify `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminControllerWebMvcTest.java`
  - Assert new JSON fields.
- Modify docs:
  - `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`
  - `docs/architecture/AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md`

---

### Task 1: Profile Type Classification

**Files:**
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileServiceTest.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileService.java`

- [ ] **Step 1: Write failing tests for profile type boundaries**

Append these tests to `AudioTasteProfileServiceTest`.

```java
@Test
void shouldClassifyProfileTypeFromFeatureReadyLibrarySize() {
    assertThat(profileWithReadyTracks(4).profileType()).isEqualTo("none");
    assertThat(profileWithReadyTracks(5).profileType()).isEqualTo("weak");
    assertThat(profileWithReadyTracks(10).profileType()).isEqualTo("ready");
    assertThat(profileWithReadyTracks(50).profileType()).isEqualTo("strong");
    assertThat(profileWithReadyTracks(200).profileType()).isEqualTo("heavy");
}

@Test
void shouldKeepWeakProfileNotApplicableForServing() {
    AudioTasteProfileService.Profile profile = profileWithReadyTracks(5);

    assertThat(profile.profileType()).isEqualTo("weak");
    assertThat(profile.audioTasteApplicable()).isFalse();
    assertThat(profile.warnings()).contains("Audio taste profile is weak until at least 10 positive feature-ready tracks.");
}
```

Add these helpers to the same test class.

```java
private AudioTasteProfileService.Profile profileWithReadyTracks(int count) {
    InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
    InMemoryTrackAudioFeatureEvidenceStore evidenceStore = new InMemoryTrackAudioFeatureEvidenceStore();
    PmsUserLibraryStore libraryStore = libraryWithTracks(count, index ->
        track("ready-" + index, "Ready " + index, "Artist " + index, "spotify", features(0.75d, 0.65d, 0.70d))
    );
    IntStream.rangeClosed(1, count)
        .forEach(index -> eventStore.save(event("user-1", "track_saved", "ready-" + index, 2.0d)));

    return new AudioTasteProfileService(
        libraryStore,
        eventStore,
        evidenceStore,
        new EventSignalWeights(),
        10,
        0.0d
    ).recompute("user-1", 500);
}

private interface TrackFactory {
    PmsUserLibraryStore.LibraryTrackState create(int index);
}

private PmsUserLibraryStore libraryWithTracks(int count, TrackFactory factory) {
    return new PmsUserLibraryStore() {
        @Override
        public List<PmsUserLibraryStore.LibraryPlaylistState> findPlaylists(String userId) {
            return List.of(new PmsUserLibraryStore.LibraryPlaylistState(
                userId,
                "playlist-profile-type",
                "external-profile-type",
                "Profile Type",
                "spotify",
                "me",
                "",
                null,
                null,
                null,
                Instant.parse("2026-05-21T00:00:00Z"),
                IntStream.rangeClosed(1, count).mapToObj(factory::create).toList()
            ));
        }

        @Override
        public List<PmsUserLibraryStore.LibraryPlaylistState> savePlaylists(
            String userId,
            List<PmsUserLibraryStore.LibraryPlaylistState> playlists
        ) {
            return playlists;
        }
    };
}

private PmsUserLibraryStore.LibraryTrackState track(
    String trackId,
    String title,
    String artistName,
    String sourcePlatform,
    PmsTrackAudioFeatures audioFeatures
) {
    return new PmsUserLibraryStore.LibraryTrackState(
        trackId,
        trackId,
        title,
        artistName,
        sourcePlatform,
        "pop",
        null,
        null,
        null,
        null,
        null,
        1,
        false,
        audioFeatures
    );
}
```

- [ ] **Step 2: Run test to verify RED**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileServiceTest
```

Expected: FAIL at compile time because `Profile.profileType()` does not exist and the helper calls a not-yet-existing `track` overload.

- [ ] **Step 3: Add profile type fields and resolver**

In `AudioTasteProfileService`, add constants near the existing gate constants.

```java
private static final String PROFILE_NONE = "none";
private static final String PROFILE_WEAK = "weak";
private static final String PROFILE_READY = "ready";
private static final String PROFILE_STRONG = "strong";
private static final String PROFILE_HEAVY = "heavy";
```

In `recompute`, compute profile type from library usable count and keep serving applicability conservative.

```java
Coverage coverage = coverage(featuresByTrackId.values().stream().toList());
String profileType = resolveProfileType(coverage.usableTrackCount());
boolean applicable = !"none".equals(profileType)
    && !"weak".equals(profileType)
    && positives.size() >= minPositiveReadyTracks
    && coverage.featureReadyRatio() >= minFeatureReadyRatio;
List<String> warnings = new ArrayList<>();
if ("weak".equals(profileType)) {
    warnings.add("Audio taste profile is weak until at least 10 positive feature-ready tracks.");
}
if (positives.size() < minPositiveReadyTracks) {
    warnings.add("Audio taste profile requires at least %d positive feature-ready tracks."
        .formatted(minPositiveReadyTracks));
}
if (coverage.featureReadyRatio() < minFeatureReadyRatio) {
    warnings.add("Audio taste feature coverage is below %.2f.".formatted(minFeatureReadyRatio));
}
```

Add this helper near `coverage`.

```java
private String resolveProfileType(long usableTrackCount) {
    if (usableTrackCount >= 200L) {
        return PROFILE_HEAVY;
    }
    if (usableTrackCount >= 50L) {
        return PROFILE_STRONG;
    }
    if (usableTrackCount >= 10L) {
        return PROFILE_READY;
    }
    if (usableTrackCount >= 5L) {
        return PROFILE_WEAK;
    }
    return PROFILE_NONE;
}
```

Update `Profile` record by inserting `String profileType` after `boolean audioTasteApplicable`.

```java
public record Profile(
    String userId,
    String status,
    boolean audioTasteApplicable,
    String profileType,
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
```

Update the `new Profile(...)` call in `recompute` to pass `profileType` after `applicable`.

- [ ] **Step 4: Update existing tests for the new record constructor**

In `AudioTasteAdminControllerWebMvcTest` and `AudioTasteScoringServiceTest`, add `"ready"` after the `audioTasteApplicable` argument in existing `new AudioTasteProfileService.Profile(...)` calls.

Example:

```java
return new AudioTasteProfileService.Profile(
    "target-user",
    "ok",
    true,
    "ready",
    12,
    1,
    100,
    ...
);
```

- [ ] **Step 5: Run GREEN test**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileServiceTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileServiceTest.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteScoringServiceTest.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminControllerWebMvcTest.java
git commit -m "feat: classify sparse audio taste profiles"
```

---

### Task 2: Diversity, Source Quality, And Confidence

**Files:**
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileServiceTest.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileService.java`

- [ ] **Step 1: Write failing tests for diversity and confidence**

Append these tests to `AudioTasteProfileServiceTest`.

```java
@Test
void shouldMarkReadyProfileAsArtistNarrowWhenOneArtistDominates() {
    InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
    PmsUserLibraryStore libraryStore = libraryWithTracks(10, index -> {
        String artist = index <= 8 ? "Dominant Artist" : "Other Artist " + index;
        return track("narrow-" + index, "Narrow " + index, artist, "spotify", features(0.75d, 0.65d, 0.70d));
    });
    IntStream.rangeClosed(1, 10)
        .forEach(index -> eventStore.save(event("user-1", "track_saved", "narrow-" + index, 2.0d)));

    AudioTasteProfileService.Profile profile = new AudioTasteProfileService(
        libraryStore,
        eventStore,
        new InMemoryTrackAudioFeatureEvidenceStore(),
        new EventSignalWeights(),
        10,
        0.0d
    ).recompute("user-1", 500);

    assertThat(profile.profileType()).isEqualTo("ready");
    assertThat(profile.profileFocus()).isEqualTo("artist_narrow");
    assertThat(profile.diversity().distinctArtistCount()).isEqualTo(3);
    assertThat(profile.diversity().dominantArtistName()).isEqualTo("Dominant Artist");
    assertThat(profile.diversity().dominantArtistShare()).isEqualTo(0.8d);
    assertThat(profile.warnings()).contains("Audio taste profile is artist-narrow; boost will be dampened.");
}

@Test
void shouldMarkProfileAsLowQualityWhenWeakTierDominates() {
    InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
    InMemoryTrackAudioFeatureEvidenceStore evidenceStore = new InMemoryTrackAudioFeatureEvidenceStore();
    PmsUserLibraryStore libraryStore = libraryWithTracks(10, index ->
        track("weak-quality-" + index, "Weak " + index, "Artist " + index, "spotify",
            featuresWithSource("llm_search_low_confidence", false, 0.75d, 0.65d, 0.70d))
    );
    IntStream.rangeClosed(1, 10).forEach(index -> {
        String trackId = "weak-quality-" + index;
        eventStore.save(event("user-1", "track_saved", trackId, 2.0d));
        evidenceStore.saveAll(List.of(new TrackAudioFeatureEvidenceStore.Draft(
            "pms_user_track",
            trackId,
            "llm",
            "llm_search_inferred",
            null,
            "model_output",
            "{\"confidence\":0.55}",
            0.55d,
            Instant.parse("2026-05-21T00:00:00Z"),
            null
        )));
    });

    AudioTasteProfileService.Profile profile = new AudioTasteProfileService(
        libraryStore,
        eventStore,
        evidenceStore,
        new EventSignalWeights(),
        10,
        0.0d
    ).recompute("user-1", 500);

    assertThat(profile.profileFocus()).isEqualTo("low_quality");
    assertThat(profile.sourceQualityMix().llmWeak()).isEqualTo(1.0d);
    assertThat(profile.profileConfidence()).isLessThan(0.40d);
    assertThat(profile.warnings()).contains("Audio taste profile is low-quality; weak inferred features dominate.");
}
```

Add this helper to the same test class.

```java
private PmsTrackAudioFeatures featuresWithSource(
    String source,
    boolean filled,
    double energy,
    double valence,
    double danceability
) {
    return new PmsTrackAudioFeatures(
        "spotify-track",
        source,
        filled,
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

- [ ] **Step 2: Run tests to verify RED**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileServiceTest
```

Expected: FAIL at compile time because `profileFocus()`, `diversity()`, `sourceQualityMix()`, and `profileConfidence()` do not exist.

- [ ] **Step 3: Add records to `AudioTasteProfileService`**

Extend `Profile` record after `profileType`.

```java
String profileFocus,
double profileConfidence,
Diversity diversity,
SourceQualityMix sourceQualityMix,
```

Add the records near `Coverage`.

```java
public record Diversity(
    int distinctArtistCount,
    String dominantArtistName,
    double dominantArtistShare,
    int distinctSourcePlatformCount
) {
}

public record SourceQualityMix(
    double provider,
    double llmAccepted,
    double llmWeak,
    double lastfmPartial,
    double missing
) {
}
```

- [ ] **Step 4: Implement diversity/source quality helpers**

Add these helpers to `AudioTasteProfileService`.

```java
private Diversity diversity(List<AudioTasteTrackFeature> rows) {
    List<AudioTasteTrackFeature> usableRows = rows.stream().filter(AudioTasteTrackFeature::usable).toList();
    if (usableRows.isEmpty()) {
        return new Diversity(0, null, 0.0d, 0);
    }
    Map<String, Long> byArtist = usableRows.stream()
        .collect(java.util.stream.Collectors.groupingBy(
            row -> normalizeGroupValue(row.artistName()),
            java.util.stream.Collectors.counting()
        ));
    Map.Entry<String, Long> dominant = byArtist.entrySet().stream()
        .max(Map.Entry.comparingByValue())
        .orElse(Map.entry("", 0L));
    long sourceCount = usableRows.stream()
        .map(row -> normalizeGroupValue(row.sourcePlatform()))
        .filter(value -> !value.isBlank())
        .distinct()
        .count();
    return new Diversity(
        byArtist.size(),
        dominant.getKey().isBlank() ? null : dominant.getKey(),
        round((double) dominant.getValue() / usableRows.size()),
        Math.toIntExact(sourceCount)
    );
}

private SourceQualityMix sourceQualityMix(List<AudioTasteTrackFeature> rows) {
    if (rows.isEmpty()) {
        return new SourceQualityMix(0.0d, 0.0d, 0.0d, 0.0d, 0.0d);
    }
    return new SourceQualityMix(
        ratio(rows, "provider"),
        ratio(rows, "llm_accepted"),
        ratio(rows, "llm_weak"),
        ratio(rows, "lastfm_partial"),
        ratio(rows, "missing")
    );
}

private double ratio(List<AudioTasteTrackFeature> rows, String tier) {
    long count = rows.stream().filter(row -> Objects.equals(row.featureTier(), tier)).count();
    return round((double) count / rows.size());
}

private String normalizeGroupValue(String value) {
    return value == null ? "" : value.trim();
}
```

- [ ] **Step 5: Implement focus and confidence**

Add helpers.

```java
private String resolveProfileFocus(Diversity diversity, SourceQualityMix mix) {
    if (mix.llmWeak() >= 0.50d) {
        return "low_quality";
    }
    if (diversity.dominantArtistShare() >= 0.70d) {
        return "artist_narrow";
    }
    if (diversity.distinctSourcePlatformCount() <= 1) {
        return "source_narrow";
    }
    return "balanced";
}

private double profileConfidence(
    String profileType,
    Coverage coverage,
    SourceQualityMix mix,
    String profileFocus,
    int negativeTrackCount
) {
    double base = switch (profileType) {
        case PROFILE_HEAVY -> 0.90d;
        case PROFILE_STRONG -> 0.75d;
        case PROFILE_READY -> 0.55d;
        case PROFILE_WEAK -> 0.25d;
        default -> 0.0d;
    };
    double sourceQuality = (mix.provider() * 1.0d)
        + (mix.llmAccepted() * 0.70d)
        + (mix.llmWeak() * 0.35d)
        + (mix.lastfmPartial() * 0.25d);
    double diversityMultiplier = switch (profileFocus) {
        case "artist_narrow" -> 0.70d;
        case "source_narrow" -> 0.85d;
        case "low_quality" -> 0.65d;
        default -> 1.0d;
    };
    double negativeBonus = negativeTrackCount > 0 ? 0.03d : 0.0d;
    return round(clamp(
        (base * clamp(0.35d + coverage.featureReadyRatio(), 0.35d, 1.0d) * sourceQuality * diversityMultiplier)
            + negativeBonus
    ));
}

private double clamp(double value) {
    return Math.max(0.0d, Math.min(1.0d, value));
}
```

In `recompute`, compute these before the `new Profile(...)` call.

```java
List<AudioTasteTrackFeature> featureRows = featuresByTrackId.values().stream().toList();
Coverage coverage = coverage(featureRows);
String profileType = resolveProfileType(coverage.usableTrackCount());
Diversity diversity = diversity(featureRows);
SourceQualityMix sourceQualityMix = sourceQualityMix(featureRows);
String profileFocus = resolveProfileFocus(diversity, sourceQualityMix);
double profileConfidence = profileConfidence(profileType, coverage, sourceQualityMix, profileFocus, negatives.size());
```

Append focus warnings after gate warnings.

```java
if ("artist_narrow".equals(profileFocus)) {
    warnings.add("Audio taste profile is artist-narrow; boost will be dampened.");
}
if ("source_narrow".equals(profileFocus)) {
    warnings.add("Audio taste profile is source-narrow; boost will be dampened.");
}
if ("low_quality".equals(profileFocus)) {
    warnings.add("Audio taste profile is low-quality; weak inferred features dominate.");
}
```

- [ ] **Step 6: Run GREEN test**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileServiceTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileServiceTest.java
git commit -m "feat: add audio taste confidence signals"
```

---

### Task 3: Admin API Contract For Sparse Profile Fields

**Files:**
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminControllerWebMvcTest.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminController.java`

- [ ] **Step 1: Write failing WebMvc assertions**

Update `shouldReturnAudioTasteProfile` in `AudioTasteAdminControllerWebMvcTest` with:

```java
.andExpect(jsonPath("$.profile.profile_type").value("ready"))
.andExpect(jsonPath("$.profile.profile_focus").value("balanced"))
.andExpect(jsonPath("$.profile.profile_confidence").value(0.62))
.andExpect(jsonPath("$.profile.diversity.distinct_artist_count").value(8))
.andExpect(jsonPath("$.profile.diversity.dominant_artist_name").value("Artist A"))
.andExpect(jsonPath("$.profile.diversity.dominant_artist_share").value(0.18))
.andExpect(jsonPath("$.profile.diversity.distinct_source_platform_count").value(2))
.andExpect(jsonPath("$.profile.source_quality_mix.provider").value(0.72))
.andExpect(jsonPath("$.profile.source_quality_mix.llm_accepted").value(0.18))
.andExpect(jsonPath("$.profile.source_quality_mix.llm_weak").value(0.04))
.andExpect(jsonPath("$.profile.source_quality_mix.lastfm_partial").value(0.06))
.andExpect(jsonPath("$.profile.source_quality_mix.missing").value(0.0));
```

Update the test `profile()` helper to construct the expanded profile.

```java
return new AudioTasteProfileService.Profile(
    "target-user",
    "ok",
    true,
    "ready",
    "balanced",
    0.62d,
    new AudioTasteProfileService.Diversity(8, "Artist A", 0.18d, 2),
    new AudioTasteProfileService.SourceQualityMix(0.72d, 0.18d, 0.04d, 0.06d, 0.0d),
    12,
    1,
    100,
    new AudioTasteProfileService.Centroid(0.2d, 0.7d, 0.7d, 0.01d, 0.1d, 0.05d, 0.43d, 0.7d),
    AudioTasteProfileService.Centroid.empty(),
    new AudioTasteProfileService.Coverage(20, 15, 0.75d, 2, 1),
    List.of(),
    Instant.parse("2026-05-21T00:00:00Z")
);
```

- [ ] **Step 2: Run WebMvc test to verify RED**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.presentation.AudioTasteAdminControllerWebMvcTest
```

Expected: FAIL because `ProfileItem` does not expose `profile_type`, `diversity`, or `source_quality_mix`.

- [ ] **Step 3: Extend controller DTOs**

In `AudioTasteAdminController.ProfileItem`, add fields after `audioTasteApplicable`.

```java
String profileType,
String profileFocus,
double profileConfidence,
DiversityItem diversity,
SourceQualityMixItem sourceQualityMix,
```

Update `ProfileItem.from`.

```java
return new ProfileItem(
    profile.userId(),
    profile.status(),
    profile.audioTasteApplicable(),
    profile.profileType(),
    profile.profileFocus(),
    profile.profileConfidence(),
    DiversityItem.from(profile.diversity()),
    SourceQualityMixItem.from(profile.sourceQualityMix()),
    profile.positiveTrackCount(),
    profile.negativeTrackCount(),
    profile.eventLimit(),
    CentroidItem.from(profile.positiveCentroid()),
    CentroidItem.from(profile.negativeCentroid()),
    CoverageItem.from(profile.coverage()),
    profile.warnings(),
    profile.recomputedAt()
);
```

Add DTO records near `CoverageItem`.

```java
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DiversityItem(
    int distinctArtistCount,
    String dominantArtistName,
    double dominantArtistShare,
    int distinctSourcePlatformCount
) {
    static DiversityItem from(AudioTasteProfileService.Diversity diversity) {
        return new DiversityItem(
            diversity.distinctArtistCount(),
            diversity.dominantArtistName(),
            diversity.dominantArtistShare(),
            diversity.distinctSourcePlatformCount()
        );
    }
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SourceQualityMixItem(
    double provider,
    double llmAccepted,
    double llmWeak,
    double lastfmPartial,
    double missing
) {
    static SourceQualityMixItem from(AudioTasteProfileService.SourceQualityMix mix) {
        return new SourceQualityMixItem(
            mix.provider(),
            mix.llmAccepted(),
            mix.llmWeak(),
            mix.lastfmPartial(),
            mix.missing()
        );
    }
}
```

- [ ] **Step 4: Run WebMvc test**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.presentation.AudioTasteAdminControllerWebMvcTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminController.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/AudioTasteAdminControllerWebMvcTest.java
git commit -m "feat: expose sparse audio taste profile fields"
```

---

### Task 4: Confidence-Aware GMS Audio Taste Boost

**Files:**
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteScoringServiceTest.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteScoringService.java`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewService.java`

- [ ] **Step 1: Write failing scoring tests**

Append these tests to `AudioTasteScoringServiceTest`.

```java
@Test
void shouldScaleCoverageWeightByProfileConfidence() {
    AudioTasteScoringService scoring = new AudioTasteScoringService();
    AudioTasteProfileService.Profile weak = profile("weak", "balanced", 0.25d, true);
    AudioTasteProfileService.Profile strong = profile("strong", "balanced", 0.75d, true);

    AudioTasteScoringService.Score weakScore = scoring.score(weak, feature("track-a", 0.70d, 0.70d, 0.70d, 120.0d));
    AudioTasteScoringService.Score strongScore = scoring.score(strong, feature("track-a", 0.70d, 0.70d, 0.70d, 120.0d));

    assertThat(weakScore.coverageWeight()).isLessThan(strongScore.coverageWeight());
    assertThat(weakScore.maxBoostWeight()).isEqualTo(0.03d);
    assertThat(strongScore.maxBoostWeight()).isEqualTo(0.12d);
}

@Test
void shouldAddFocusTokensForNarrowAndLowQualityProfiles() {
    AudioTasteScoringService scoring = new AudioTasteScoringService();

    AudioTasteScoringService.Score narrow = scoring.score(
        profile("ready", "artist_narrow", 0.40d, true),
        feature("track-a", 0.70d, 0.70d, 0.70d, 120.0d)
    );
    AudioTasteScoringService.Score lowQuality = scoring.score(
        profile("ready", "low_quality", 0.30d, true),
        feature("track-b", 0.70d, 0.70d, 0.70d, 120.0d)
    );

    assertThat(narrow.explanationTokens()).contains("artist_narrow_audio_profile");
    assertThat(lowQuality.explanationTokens()).contains("low_quality_audio_profile");
}
```

Add this overloaded helper to the same test class.

```java
private AudioTasteProfileService.Profile profile(
    String profileType,
    String profileFocus,
    double profileConfidence,
    boolean applicable
) {
    return new AudioTasteProfileService.Profile(
        "user-1",
        applicable ? "ok" : "insufficient_data",
        applicable,
        profileType,
        profileFocus,
        profileConfidence,
        new AudioTasteProfileService.Diversity(8, "Artist A", 0.20d, 2),
        new AudioTasteProfileService.SourceQualityMix(1.0d, 0.0d, 0.0d, 0.0d, 0.0d),
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
```

- [ ] **Step 2: Run scoring test to verify RED**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteScoringServiceTest
```

Expected: FAIL because `Score.maxBoostWeight()` does not exist and focus tokens are not emitted.

- [ ] **Step 3: Update `AudioTasteScoringService`**

Replace coverage weight calculation in `score`.

```java
double profileInfluence = profileInfluence(profile);
double coverageWeight = Math.min(1.0d, profile.coverage().featureReadyRatio() * candidate.featureWeight() * profileInfluence);
double score = clamp(coverageWeight * ((0.70d * positiveSimilarity) + (0.30d * negativeDistanceBonus)));
List<String> tokens = explanationTokens(candidate, profile.positiveCentroid());
if ("llm_weak".equals(candidate.featureTier())) {
    tokens.add("low_confidence_audio");
}
if ("artist_narrow".equals(profile.profileFocus())) {
    tokens.add("artist_narrow_audio_profile");
}
if ("low_quality".equals(profile.profileFocus())) {
    tokens.add("low_quality_audio_profile");
}
return new Score(true, round(score), round(coverageWeight), maxBoostWeight(profile.profileType()), List.copyOf(tokens));
```

Add helpers.

```java
private double profileInfluence(AudioTasteProfileService.Profile profile) {
    return profile.profileConfidence() * switch (profile.profileType()) {
        case "weak" -> 0.50d;
        case "ready" -> 0.85d;
        case "strong", "heavy" -> 1.0d;
        default -> 0.0d;
    };
}

private double maxBoostWeight(String profileType) {
    return switch (profileType) {
        case "weak" -> 0.03d;
        case "ready" -> 0.08d;
        case "strong", "heavy" -> 0.12d;
        default -> 0.0d;
    };
}
```

Update the no-op returns and record.

```java
return new Score(false, 0.0d, 0.0d, 0.0d, List.of("audio_taste_not_applicable"));
```

```java
public record Score(
    boolean applied,
    double score,
    double coverageWeight,
    double maxBoostWeight,
    List<String> explanationTokens
) {
}
```

- [ ] **Step 4: Run scoring test**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteScoringServiceTest
```

Expected: PASS.

- [ ] **Step 5: Write failing GMS warning/boost cap test**

Append a focused test to `GmsRecommendationPreviewServiceTest`.

```java
@Test
void shouldDampenAudioTasteBoostForWeakSparseProfile() {
    InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
    InMemoryPmsUserLibraryStore pmsUserLibraryStore = new InMemoryPmsUserLibraryStore();
    InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
    pmsUserLibraryStore.savePlaylists("audio-user", List.of(sampleWeakLibraryPlaylistForAudioTaste()));
    for (int index = 1; index <= 5; index++) {
        eventStore.save(audioTasteEvent("audio-user", "track-audio-match", index));
    }
    AudioTasteProfileService audioTasteProfileService = new AudioTasteProfileService(
        pmsUserLibraryStore,
        eventStore,
        new InMemoryTrackAudioFeatureEvidenceStore(),
        new EventSignalWeights(),
        5,
        0.0d
    );
    GmsRecommendationPreviewService service = new GmsRecommendationPreviewService(
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
        new ColdStartFallbackService(authAccountStore, pmsUserLibraryStore, Optional.empty())
    );

    GmsRecommendationPreviewResponse response = service.previewRecommendations(new GmsRecommendationPreviewRequest(
        "request-audio-taste-weak",
        "audio-user",
        "playlist-audio",
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

    assertThat(response.warnings())
        .anyMatch(warning -> warning.contains("Audio taste ranking adjusted")
            && warning.contains("profile_type=weak"));
    assertThat(response.context().engine()).contains("audio-taste:v1");
}
```

Add helper playlist with five feature-ready tracks.

```java
private PmsUserLibraryStore.LibraryPlaylistState sampleWeakLibraryPlaylistForAudioTaste() {
    List<PmsUserLibraryStore.LibraryTrackState> tracks = new ArrayList<>();
    tracks.add(audioTasteLibraryTrack("track-audio-match", "Audio Match", "Artist A",
        audioTasteFeatures("spotify-track-audio-match", 0.20d, 0.70d, 0.70d, 0.01d, 0.12d, 0.05d, 120.0d, 0.70d)));
    tracks.add(audioTasteLibraryTrack("track-audio-far", "Audio Far", "Artist B",
        audioTasteFeatures("spotify-track-audio-far", 0.80d, 0.20d, 0.20d, 0.01d, 0.12d, 0.05d, 80.0d, 0.20d)));
    for (int index = 3; index <= 5; index++) {
        String trackId = "track-audio-extra-" + index;
        tracks.add(audioTasteLibraryTrack(trackId, "Audio Extra " + index, "Artist " + index,
            audioTasteFeatures("spotify-" + trackId, 0.25d, 0.65d, 0.65d, 0.01d, 0.12d, 0.05d, 118.0d, 0.65d)));
    }
    return new PmsUserLibraryStore.LibraryPlaylistState(
        "audio-user",
        "playlist-audio",
        "external-playlist-audio",
        "Audio Taste",
        "spotify",
        "me",
        "",
        null,
        null,
        null,
        Instant.parse("2026-05-21T00:00:00Z"),
        tracks
    );
}

private PmsUserLibraryStore.LibraryTrackState audioTasteLibraryTrack(
    String trackId,
    String title,
    String artistName,
    PmsTrackAudioFeatures audioFeatures
) {
    return new PmsUserLibraryStore.LibraryTrackState(
        trackId,
        "spotify-" + trackId,
        title,
        artistName,
        "spotify",
        "synth-pop",
        "Audio Taste",
        null,
        "https://open.spotify.com/track/spotify-" + trackId,
        "spotify:track:spotify-" + trackId,
        null,
        1,
        false,
        audioFeatures
    );
}
```

- [ ] **Step 6: Run GMS test to verify RED**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest
```

Expected: FAIL because the audio taste warning does not include `profile_type=weak`, and weak profile may still be considered not applicable until Task 4 adjusts the profile scoring policy.

- [ ] **Step 7: Update GMS boost and warning**

In `GmsRecommendationPreviewService.resolveAudioBoostWeight`, use the score cap.

```java
private double resolveAudioBoostWeight(AudioTasteScoringService.Score score) {
    if (!score.applied()) {
        return 0.0d;
    }
    return Math.min(score.maxBoostWeight(), Math.max(0.0d, score.coverageWeight() * 0.12d));
}
```

In `applyAudioTasteRanking`, collect profile context for the warning.

```java
enrichmentWarnings.add(
    "Audio taste ranking adjusted %d playable GMS candidate(s) via audio-taste:v1 (profile_type=%s, confidence=%.2f, focus=%s)."
        .formatted(appliedCount, profile.profileType(), profile.profileConfidence(), profile.profileFocus())
);
```

If weak profiles should be allowed only when the service is constructed with a lower gate, update `AudioTasteProfileService` applicability so profile type `weak` remains not applicable by default but can apply when `minPositiveReadyTracks <= 5`.

```java
boolean profileTypeAllowed = switch (profileType) {
    case PROFILE_WEAK -> minPositiveReadyTracks <= 5;
    case PROFILE_READY, PROFILE_STRONG, PROFILE_HEAVY -> true;
    default -> false;
};
boolean applicable = profileTypeAllowed
    && positives.size() >= minPositiveReadyTracks
    && coverage.featureReadyRatio() >= minFeatureReadyRatio;
```

- [ ] **Step 8: Run GMS and scoring tests**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteScoringServiceTest \
  --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteScoringService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteScoringServiceTest.java \
  services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java \
  services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileServiceTest.java
git commit -m "feat: apply sparse audio taste confidence to gms"
```

---

### Task 5: Docs And Full Verification

**Files:**
- Modify: `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`
- Modify: `docs/architecture/AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md`

- [ ] **Step 1: Update API docs**

In `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`, update the response field list with:

```markdown
- `profile.profile_type`: `none`, `weak`, `ready`, `strong`, `heavy`
- `profile.profile_confidence`: 0.0-1.0 confidence for applying audio taste signals
- `profile.profile_focus`: `balanced`, `artist_narrow`, `source_narrow`, `low_quality`
- `profile.diversity`: artist/source diversity summary
- `profile.source_quality_mix`: provider/LLM/Last.fm/missing tier ratio
```

Add this profile type table under `Readiness gates`.

```markdown
| Type | Feature-ready tracks | Serving |
| --- | ---: | --- |
| `none` | `0-4` | no audio taste boost |
| `weak` | `5-9` | only available when verification gate is lowered; very small boost |
| `ready` | `10-49` | conservative sparse profile boost |
| `strong` | `50-199` | stable centroid profile boost |
| `heavy` | `200+` | multi-mode candidate; cluster expansion follows after v1 confidence rollout |
```

- [ ] **Step 2: Update architecture plan**

In `docs/architecture/AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md`, extend Phase 6 1차 구현 상태 with:

```markdown
- `SparseAndMultiModeTasteModel v1`은 feature-ready track 수로 `none/weak/ready/strong/heavy` profile type을 계산하고, profile confidence와 diversity/source-quality summary로 audio taste boost를 감쇠한다.
- 10곡 이상은 기본 sparse profile로 사용하고, 200곡 이상은 heavy user로 표시해 다음 cluster 기반 multi-mode model의 입력으로 삼는다.
```

- [ ] **Step 3: Run targeted tests**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileServiceTest \
  --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteScoringServiceTest \
  --tests io.myforevermusic.api.modules.recommendation.presentation.AudioTasteAdminControllerWebMvcTest \
  --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest
```

Expected: PASS.

- [ ] **Step 4: Run full API tests**

```bash
cd services/api
./gradlew test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit docs**

```bash
git add docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md \
  docs/architecture/AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md
git commit -m "docs: document sparse audio taste profiles"
```

- [ ] **Step 6: Final status check**

```bash
git status --short --branch
git log --oneline --decorate --max-count=8
```

Expected:

- Only known untracked local IDE/theme paths remain, if present.
- Latest commits include the sparse audio taste profile implementation commits.
