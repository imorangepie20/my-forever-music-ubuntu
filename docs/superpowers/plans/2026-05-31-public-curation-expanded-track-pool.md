# Public Curation Expanded Track Pool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Public Curation 생성 시 PMS/EMS 전체 track pool을 넓게 훑고, TIDAL playback target이 있거나 안정적으로 resolve된 후보만 AI scoring에 넘긴다.

**Architecture:** 후보 수집과 재생 가능성 판정을 분리한다. `JdbcPublicCurationCandidatePoolStore`는 raw 후보를 넓게 가져오고, 새 `PublicCurationPlayableCandidateService`가 TIDAL-ready 후보 통과, TIDAL resolve, 실패 후보 제외, 요약 생성을 담당한다. Admin controller는 준비된 playable 후보만 AI generation service에 전달하고, 생성 결과의 `score_summary.candidate_preparation`에 raw/playable/resolve 지표를 저장한다.

**Tech Stack:** Spring Boot 3.5.x, Java 21, Gradle, PostgreSQL/JdbcTemplate, FastAPI/Pydantic, React + TypeScript + Vite.

---

## File Map

- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationCandidatePoolStore.java`
  - `CandidateTrack`에 `playbackResolutionStatus`를 추가하고, native/resolved 상태 상수를 둔다.
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/JdbcPublicCurationCandidatePoolStore.java`
  - `tidalReadyRequired=false`일 때 PMS/EMS metadata 후보를 넓게 조회한다.
  - native TIDAL 후보에는 `native_tidal`, 미해결 raw 후보에는 `unresolved` 상태를 넣는다.
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationPlayableCandidateService.java`
  - raw 후보 조회, TIDAL resolve, playable 후보 제한, 후보 준비 요약 생성을 담당한다.
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationAdminController.java`
  - 후보 store 직접 호출을 새 service 호출로 교체한다.
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationGenerationService.java`
  - 후보 준비 요약을 `score_summary`에 병합해서 저장한다.
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/ai/AiPublicCurationScoringClient.java`
  - AI request candidate에 `playback_resolution_status`를 전달한다.
- Modify: `services/ai/app/schemas/public_curation.py`
  - `PublicCurationCandidateTrack.playback_resolution_status` 필드를 추가한다.
- Modify: `services/ai/app/services/public_curation_service.py`
  - resolved 후보에 작은 confidence penalty를 score breakdown으로 반영한다.
- Modify: `apps/web/src/types/api.ts`
  - `candidate_preparation` summary type을 추가한다.
- Modify: `apps/web/src/pages/PublicCurationAdminPage.tsx`
  - 관리자에게 raw/playable/resolved/excluded 지표를 한국어로 보여준다.
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/JdbcPublicCurationCandidatePoolStoreTest.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationPlayableCandidateServiceTest.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationAdminControllerWebMvcTest.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationGenerationServiceTest.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/infrastructure/ai/AiPublicCurationScoringClientTest.java`
- Test: `services/ai/tests/test_public_curation.py`
- Test: `apps/web/scripts/public-curation-admin-page-harness.mjs`

---

### Task 1: Expand Candidate Store Contract And Raw SQL

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationCandidatePoolStore.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/JdbcPublicCurationCandidatePoolStore.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/JdbcPublicCurationCandidatePoolStoreTest.java`

- [ ] **Step 1: Write failing store tests**

Add this test to `JdbcPublicCurationCandidatePoolStoreTest`:

```java
@Test
@SuppressWarnings("unchecked")
void shouldQueryExpandedRawCandidatesWithoutTidalOnlyPredicates() throws Exception {
    NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    AtomicReference<String> capturedSql = new AtomicReference<>();
    JdbcPublicCurationCandidatePoolStore store = new JdbcPublicCurationCandidatePoolStore(jdbcTemplate);

    when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenAnswer(invocation -> {
            capturedSql.set(invocation.getArgument(0));
            RowMapper<PublicCurationCandidatePoolStore.CandidateTrack> mapper = invocation.getArgument(2);
            return List.of(mapper.mapRow(resultSet(), 0));
        });

    List<PublicCurationCandidatePoolStore.CandidateTrack> candidates = store.findCandidates(
        new PublicCurationCandidatePoolStore.CandidateQuery(50, false)
    );

    assertThat(candidates).hasSize(1);
    assertThat(candidates.getFirst().playbackResolutionStatus()).isEqualTo(
        PublicCurationCandidatePoolStore.PLAYBACK_RESOLUTION_NATIVE_TIDAL
    );
    assertThat(capturedSql.get()).contains("from pms_user_track");
    assertThat(capturedSql.get()).contains("from ems_collected_track track");
    assertThat(capturedSql.get()).contains("btrim(title) <> ''");
    assertThat(capturedSql.get()).contains("btrim(artist_name) <> ''");
    assertThat(capturedSql.get()).contains("btrim(track.title) <> ''");
    assertThat(capturedSql.get()).contains("btrim(track.artist_name) <> ''");
    assertThat(capturedSql.get()).doesNotContain("and tidal_track_id is not null and tidal_uri is not null");
    assertThat(capturedSql.get()).doesNotContain("and track.source_platform = 'tidal' and track.external_track_id is not null");
}
```

Update the existing `shouldQueryTidalReadyCandidatesFromPmsAndEmsPools` assertion:

```java
assertThat(candidates.getFirst().playbackResolutionStatus()).isEqualTo(
    PublicCurationCandidatePoolStore.PLAYBACK_RESOLUTION_NATIVE_TIDAL
);
```

- [ ] **Step 2: Run store test and verify it fails**

Run:

```bash
./gradlew test --tests JdbcPublicCurationCandidatePoolStoreTest
```

Expected: compile failure because `playbackResolutionStatus()` and the playback resolution constants do not exist.

- [ ] **Step 3: Extend the candidate contract**

In `PublicCurationCandidatePoolStore.java`, add constants and the final record field:

```java
String PLAYBACK_RESOLUTION_NATIVE_TIDAL = "native_tidal";
String PLAYBACK_RESOLUTION_RESOLVED_TO_TIDAL = "resolved_to_tidal";
String PLAYBACK_RESOLUTION_UNRESOLVED = "unresolved";

record CandidateTrack(
    String sourceScope,
    String sourceId,
    String title,
    String artistName,
    String albumTitle,
    String imageUrl,
    Integer durationMs,
    String isrc,
    String sourcePlatform,
    String tidalTrackId,
    String tidalUri,
    String tidalExternalUrl,
    Map<String, Double> audioFeatures,
    String audioFeatureSource,
    Double audioFeatureConfidence,
    boolean audioFeaturesFilled,
    List<String> genres,
    List<String> tags,
    List<String> metadataTags,
    SourcePlaylistSignals sourcePlaylistSignals,
    AudienceResponse audienceResponse,
    Double freshness,
    String playbackResolutionStatus
) {
    public boolean hasTidalPlaybackTarget() {
        return hasText(tidalTrackId) && hasText(tidalUri);
    }

    public CandidateTrack withResolvedTidalTarget(
        String resolvedTidalTrackId,
        String resolvedTidalUri,
        String resolvedTidalExternalUrl,
        String resolvedImageUrl,
        Integer resolvedDurationMs
    ) {
        return new CandidateTrack(
            sourceScope,
            sourceId,
            title,
            artistName,
            albumTitle,
            resolvedImageUrl == null || resolvedImageUrl.isBlank() ? imageUrl : resolvedImageUrl,
            resolvedDurationMs == null ? durationMs : resolvedDurationMs,
            isrc,
            sourcePlatform,
            resolvedTidalTrackId,
            resolvedTidalUri,
            resolvedTidalExternalUrl,
            audioFeatures,
            audioFeatureSource,
            audioFeatureConfidence,
            audioFeaturesFilled,
            genres,
            tags,
            metadataTags,
            sourcePlaylistSignals,
            audienceResponse,
            freshness,
            PLAYBACK_RESOLUTION_RESOLVED_TO_TIDAL
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
```

- [ ] **Step 4: Expand SQL predicates and map status**

In `JdbcPublicCurationCandidatePoolStore.sql`, rename predicate locals and use metadata predicates when raw mode is requested:

```java
String pmsCandidatePredicate = tidalReadyRequired
    ? "and tidal_track_id is not null and tidal_uri is not null"
    : "and title is not null and btrim(title) <> '' and artist_name is not null and btrim(artist_name) <> ''";
String emsCandidatePredicate = tidalReadyRequired
    ? "and track.source_platform = 'tidal' and track.external_track_id is not null"
    : "and track.title is not null and btrim(track.title) <> '' and track.artist_name is not null and btrim(track.artist_name) <> ''";
```

Use `.formatted(pmsCandidatePredicate, emsCandidatePredicate)`.

In `mapCandidate`, compute status once:

```java
String tidalTrackId = resultSet.getString("tidal_track_id");
String tidalUri = resultSet.getString("tidal_uri");
String playbackResolutionStatus = hasText(tidalTrackId) && hasText(tidalUri)
    ? PublicCurationCandidatePoolStore.PLAYBACK_RESOLUTION_NATIVE_TIDAL
    : PublicCurationCandidatePoolStore.PLAYBACK_RESOLUTION_UNRESOLVED;
```

Pass `tidalTrackId`, `tidalUri`, and `playbackResolutionStatus` into the `CandidateTrack` constructor. Add the private helper:

```java
private boolean hasText(String value) {
    return value != null && !value.isBlank();
}
```

- [ ] **Step 5: Run store test and verify it passes**

Run:

```bash
./gradlew test --tests JdbcPublicCurationCandidatePoolStoreTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit task 1**

Run:

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationCandidatePoolStore.java services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/JdbcPublicCurationCandidatePoolStore.java services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/infrastructure/persistence/JdbcPublicCurationCandidatePoolStoreTest.java
git commit -m "feat: expand public curation raw candidate pool"
```

Expected: commit created with only the files listed above.

---

### Task 2: Add Playable Candidate Preparation Service

**Files:**
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationPlayableCandidateService.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationPlayableCandidateServiceTest.java`

- [ ] **Step 1: Write failing service tests**

Create `PublicCurationPlayableCandidateServiceTest.java`:

```java
package io.myforevermusic.api.modules.publiccuration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.common.error.ApiResourceNotFoundException;
import io.myforevermusic.api.modules.platform.application.TidalPlaybackTargetResolverService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class PublicCurationPlayableCandidateServiceTest {

    private final PublicCurationCandidatePoolStore store = mock(PublicCurationCandidatePoolStore.class);
    private final TidalPlaybackTargetResolverService tidalResolver = mock(TidalPlaybackTargetResolverService.class);
    private final PublicCurationPlayableCandidateService service = new PublicCurationPlayableCandidateService(
        store,
        tidalResolver
    );

    @Test
    void shouldPassNativeTidalCandidatesAndResolveMetadataCandidates() {
        when(store.findCandidates(any(PublicCurationCandidatePoolStore.CandidateQuery.class)))
            .thenReturn(List.of(nativeTidalCandidate(), unresolvedSpotifyCandidate()));
        when(tidalResolver.resolve(eq("admin-001"), any(TidalPlaybackTargetResolverService.TrackQuery.class)))
            .thenReturn(new TidalPlaybackTargetResolverService.TidalPlaybackTarget(
                "90002",
                "tidal:track:90002",
                "Soft Rain",
                "Blue Trio",
                "Resolved Album",
                "https://images.example/soft-rain.jpg",
                "https://tidal.com/browse/track/90002",
                null,
                "KRA000000002",
                180000,
                "metadata",
                85
            ));

        PublicCurationPlayableCandidateService.PreparedCandidateBatch batch = service.prepare(
            new PublicCurationPlayableCandidateService.PrepareCommand("admin-001", 5, 2)
        );

        ArgumentCaptor<PublicCurationCandidatePoolStore.CandidateQuery> queryCaptor =
            ArgumentCaptor.forClass(PublicCurationCandidatePoolStore.CandidateQuery.class);
        verify(store).findCandidates(queryCaptor.capture());
        assertThat(queryCaptor.getValue().limit()).isEqualTo(15);
        assertThat(queryCaptor.getValue().tidalReadyRequired()).isFalse();
        assertThat(batch.candidates()).hasSize(2);
        assertThat(batch.candidates().get(0).playbackResolutionStatus()).isEqualTo(
            PublicCurationCandidatePoolStore.PLAYBACK_RESOLUTION_NATIVE_TIDAL
        );
        assertThat(batch.candidates().get(1).tidalTrackId()).isEqualTo("90002");
        assertThat(batch.candidates().get(1).playbackResolutionStatus()).isEqualTo(
            PublicCurationCandidatePoolStore.PLAYBACK_RESOLUTION_RESOLVED_TO_TIDAL
        );
        assertThat(batch.summary().rawCount()).isEqualTo(2);
        assertThat(batch.summary().nativeTidalCount()).isEqualTo(1);
        assertThat(batch.summary().resolvedCount()).isEqualTo(1);
        assertThat(batch.summary().playableCount()).isEqualTo(2);
        assertThat(batch.summary().excludedCount()).isEqualTo(0);
    }

    @Test
    void shouldExcludeResolveFailuresAndReturnConflictWhenPlayableCandidatesAreTooFew() {
        when(store.findCandidates(any(PublicCurationCandidatePoolStore.CandidateQuery.class)))
            .thenReturn(List.of(unresolvedSpotifyCandidate()));
        when(tidalResolver.resolve(eq("admin-001"), any(TidalPlaybackTargetResolverService.TrackQuery.class)))
            .thenThrow(new ApiResourceNotFoundException("No playable TIDAL match was found."));

        assertThatThrownBy(() -> service.prepare(
            new PublicCurationPlayableCandidateService.PrepareCommand("admin-001", 5, 2)
        ))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Not enough playable public curation candidates");
    }

    private PublicCurationCandidatePoolStore.CandidateTrack nativeTidalCandidate() {
        return candidate("pms_user_track", "track-001", "tidal", "90001", "tidal:track:90001", "native_tidal");
    }

    private PublicCurationCandidatePoolStore.CandidateTrack unresolvedSpotifyCandidate() {
        return candidate("ems_collected_track", "track-002", "spotify", null, null, "unresolved");
    }

    private PublicCurationCandidatePoolStore.CandidateTrack candidate(
        String sourceScope,
        String sourceId,
        String sourcePlatform,
        String tidalTrackId,
        String tidalUri,
        String playbackResolutionStatus
    ) {
        return new PublicCurationCandidatePoolStore.CandidateTrack(
            sourceScope,
            sourceId,
            "Soft Rain",
            "Blue Trio",
            "Night Walk",
            "https://images.example/source.jpg",
            181000,
            "KRA000000002",
            sourcePlatform,
            tidalTrackId,
            tidalUri,
            tidalTrackId == null ? null : "https://tidal.com/browse/track/" + tidalTrackId,
            Map.of("energy", 0.42d),
            "reccobeats",
            0.8d,
            true,
            List.of("jazz"),
            List.of(sourcePlatform),
            List.of("rainy jazz"),
            new PublicCurationCandidatePoolStore.SourcePlaylistSignals(
                0,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
            ),
            new PublicCurationCandidatePoolStore.AudienceResponse(0, 0, 0),
            0.9d,
            playbackResolutionStatus
        );
    }
}
```

- [ ] **Step 2: Run service test and verify it fails**

Run:

```bash
./gradlew test --tests PublicCurationPlayableCandidateServiceTest
```

Expected: compile failure because `PublicCurationPlayableCandidateService` does not exist.

- [ ] **Step 3: Implement playable candidate service**

Create `PublicCurationPlayableCandidateService.java`:

```java
package io.myforevermusic.api.modules.publiccuration.application;

import io.myforevermusic.api.common.error.ApiResourceNotFoundException;
import io.myforevermusic.api.modules.platform.application.PlatformReconnectRequiredException;
import io.myforevermusic.api.modules.platform.application.TidalPlaybackTargetResolverService;
import io.myforevermusic.api.modules.platform.application.TidalPlaybackTargetResolverService.TrackQuery;
import io.myforevermusic.api.modules.platform.application.TidalPlaybackTargetResolverService.TidalPlaybackTarget;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PublicCurationPlayableCandidateService {

    private static final int RAW_LIMIT_MULTIPLIER = 3;

    private final PublicCurationCandidatePoolStore candidatePoolStore;
    private final TidalPlaybackTargetResolverService tidalResolver;

    public PublicCurationPlayableCandidateService(
        PublicCurationCandidatePoolStore candidatePoolStore,
        TidalPlaybackTargetResolverService tidalResolver
    ) {
        this.candidatePoolStore = candidatePoolStore;
        this.tidalResolver = tidalResolver;
    }

    public PreparedCandidateBatch prepare(PrepareCommand command) {
        int playableLimit = Math.max(1, command.playableLimit());
        int rawLimit = Math.max(playableLimit, playableLimit * RAW_LIMIT_MULTIPLIER);
        List<PublicCurationCandidatePoolStore.CandidateTrack> rawCandidates = candidatePoolStore.findCandidates(
            new PublicCurationCandidatePoolStore.CandidateQuery(rawLimit, false)
        );

        java.util.ArrayList<PublicCurationCandidatePoolStore.CandidateTrack> playableCandidates =
            new java.util.ArrayList<>();
        int nativeTidalCount = 0;
        int resolvedCount = 0;
        int resolveAttemptCount = 0;
        int resolveFailedCount = 0;
        int skippedMetadataCount = 0;

        for (PublicCurationCandidatePoolStore.CandidateTrack candidate : rawCandidates) {
            if (playableCandidates.size() >= playableLimit) {
                break;
            }
            if (candidate.hasTidalPlaybackTarget()) {
                nativeTidalCount += 1;
                playableCandidates.add(candidate);
                continue;
            }
            if (!canResolve(candidate)) {
                skippedMetadataCount += 1;
                continue;
            }

            resolveAttemptCount += 1;
            try {
                TidalPlaybackTarget target = tidalResolver.resolve(command.adminUserId(), toTrackQuery(candidate));
                playableCandidates.add(candidate.withResolvedTidalTarget(
                    target.tidalTrackId(),
                    target.tidalUri(),
                    target.platformExternalUrl(),
                    target.albumImageUrl(),
                    target.durationMs()
                ));
                resolvedCount += 1;
            } catch (ApiResourceNotFoundException | PlatformReconnectRequiredException | IllegalArgumentException exception) {
                resolveFailedCount += 1;
            }
        }

        CandidatePreparationSummary summary = new CandidatePreparationSummary(
            rawCandidates.size(),
            nativeTidalCount,
            resolveAttemptCount,
            resolvedCount,
            resolveFailedCount,
            skippedMetadataCount,
            playableCandidates.size(),
            Math.max(0, rawCandidates.size() - playableCandidates.size()),
            resolveAttemptCount == 0 ? 0.0d : round4((double) resolvedCount / resolveAttemptCount)
        );

        if (playableCandidates.size() < command.targetTrackCount()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Not enough playable public curation candidates. raw=%d, playable=%d, target=%d"
                    .formatted(rawCandidates.size(), playableCandidates.size(), command.targetTrackCount())
            );
        }

        return new PreparedCandidateBatch(List.copyOf(playableCandidates), summary);
    }

    private boolean canResolve(PublicCurationCandidatePoolStore.CandidateTrack candidate) {
        return hasText(candidate.title()) && hasText(candidate.artistName());
    }

    private TrackQuery toTrackQuery(PublicCurationCandidatePoolStore.CandidateTrack candidate) {
        return new TrackQuery(
            candidate.title(),
            candidate.artistName(),
            candidate.sourcePlatform(),
            candidate.sourceId(),
            null,
            null,
            candidate.isrc(),
            candidate.durationMs()
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private double round4(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    public record PrepareCommand(
        String adminUserId,
        int playableLimit,
        int targetTrackCount
    ) {
    }

    public record PreparedCandidateBatch(
        List<PublicCurationCandidatePoolStore.CandidateTrack> candidates,
        CandidatePreparationSummary summary
    ) {
    }

    public record CandidatePreparationSummary(
        int rawCount,
        int nativeTidalCount,
        int resolveAttemptCount,
        int resolvedCount,
        int resolveFailedCount,
        int skippedMetadataCount,
        int playableCount,
        int excludedCount,
        double resolveSuccessRatio
    ) {
        public Map<String, Object> toJsonMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("raw_count", rawCount);
            values.put("native_tidal_count", nativeTidalCount);
            values.put("resolve_attempt_count", resolveAttemptCount);
            values.put("resolved_count", resolvedCount);
            values.put("resolve_failed_count", resolveFailedCount);
            values.put("skipped_metadata_count", skippedMetadataCount);
            values.put("playable_count", playableCount);
            values.put("excluded_count", excludedCount);
            values.put("resolve_success_ratio", resolveSuccessRatio);
            return values;
        }
    }
}
```

- [ ] **Step 4: Run service test and verify it passes**

Run:

```bash
./gradlew test --tests PublicCurationPlayableCandidateServiceTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit task 2**

Run:

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationPlayableCandidateService.java services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationPlayableCandidateServiceTest.java
git commit -m "feat: prepare playable public curation candidates"
```

Expected: commit created with only the files listed above.

---

### Task 3: Wire Admin Run Through Prepared Playable Candidates

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationAdminController.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationGenerationService.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationAdminControllerWebMvcTest.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationGenerationServiceTest.java`

- [ ] **Step 1: Update controller test to depend on preparation service**

In `PublicCurationAdminControllerWebMvcTest`, replace the `PublicCurationCandidatePoolStore` mock bean with:

```java
@MockBean
private PublicCurationPlayableCandidateService playableCandidateService;
```

In `shouldGenerateDraftFromCandidatePool`, replace the store stub:

```java
when(playableCandidateService.prepare(any(PublicCurationPlayableCandidateService.PrepareCommand.class)))
    .thenReturn(new PublicCurationPlayableCandidateService.PreparedCandidateBatch(
        List.of(candidateTrack()),
        new PublicCurationPlayableCandidateService.CandidatePreparationSummary(
            75,
            1,
            3,
            1,
            2,
            0,
            2,
            73,
            0.3333d
        )
    ));
```

Replace the `CandidateQuery` verification with:

```java
ArgumentCaptor<PublicCurationPlayableCandidateService.PrepareCommand> prepareCaptor =
    ArgumentCaptor.forClass(PublicCurationPlayableCandidateService.PrepareCommand.class);
verify(playableCandidateService).prepare(prepareCaptor.capture());
assertThat(prepareCaptor.getValue().adminUserId()).isEqualTo("admin-001");
assertThat(prepareCaptor.getValue().playableLimit()).isEqualTo(25);
assertThat(prepareCaptor.getValue().targetTrackCount()).isEqualTo(1);
```

Add a JSON response assertion:

```java
.andExpect(jsonPath("$.playlist.score_summary.candidate_preparation.raw_count").value(75))
.andExpect(jsonPath("$.playlist.score_summary.candidate_preparation.resolved_count").value(1))
```

Update `candidateTrack()` constructor call by appending:

```java
PublicCurationCandidatePoolStore.PLAYBACK_RESOLUTION_NATIVE_TIDAL
```

- [ ] **Step 2: Update generation service test to expect summary merge**

In `PublicCurationGenerationServiceTest.command()`, add a `Map.of(...)` argument before the candidate list:

```java
Map.of(
    "raw_count", 75,
    "native_tidal_count", 1,
    "resolved_count", 1,
    "playable_count", 2
),
```

Add assertions after draft creation:

```java
assertThat(playlistStore.capturedDraft.run().scoreSummaryJson())
    .contains("\"candidate_preparation\"")
    .contains("\"raw_count\":75")
    .contains("\"resolved_count\":1");
```

- [ ] **Step 3: Run controller and generation tests and verify they fail**

Run:

```bash
./gradlew test --tests PublicCurationAdminControllerWebMvcTest --tests PublicCurationGenerationServiceTest
```

Expected: compile failures because controller constructor and `GenerateDraftCommand` still use the old shape.

- [ ] **Step 4: Wire controller through preparation service**

In `PublicCurationAdminController`, replace the field and constructor parameter:

```java
private final PublicCurationPlayableCandidateService playableCandidateService;
```

In `generateDraft`, replace the direct store query with:

```java
PublicCurationPlayableCandidateService.PreparedCandidateBatch preparedCandidates =
    playableCandidateService.prepare(new PublicCurationPlayableCandidateService.PrepareCommand(
        request.adminUserId(),
        candidateLimit,
        targetTrackCount
    ));
```

Pass `preparedCandidates.summary().toJsonMap()` into `GenerateDraftCommand` and map `preparedCandidates.candidates()`.

In `toAiCandidate`, append:

```java
candidate.playbackResolutionStatus()
```

- [ ] **Step 5: Merge candidate preparation into score summary**

In `PublicCurationGenerationService.GenerateDraftCommand`, insert:

```java
Map<String, Object> candidatePreparationSummary,
```

before `List<AiPublicCurationCandidateTrack> candidateTracks`.

Replace `scoreSummaryJson(AiPublicCurationScoreResponse response)` with:

```java
private String scoreSummaryJson(AiPublicCurationScoreResponse response, GenerateDraftCommand command) {
    java.util.LinkedHashMap<String, Object> summary = new java.util.LinkedHashMap<>(response.scoreSummary());
    if (command.candidatePreparationSummary() != null && !command.candidatePreparationSummary().isEmpty()) {
        summary.put("candidate_preparation", command.candidatePreparationSummary());
    }
    return writeJson(summary);
}
```

Update the call site:

```java
scoreSummaryJson(response, command),
```

- [ ] **Step 6: Run controller and generation tests and verify they pass**

Run:

```bash
./gradlew test --tests PublicCurationAdminControllerWebMvcTest --tests PublicCurationGenerationServiceTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit task 3**

Run:

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationAdminController.java services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationGenerationService.java services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/presentation/PublicCurationAdminControllerWebMvcTest.java services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/application/PublicCurationGenerationServiceTest.java
git commit -m "feat: surface public curation candidate preparation summary"
```

Expected: commit created with only the files listed above.

---

### Task 4: Pass Playback Resolution Status To AI Scoring

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/ai/AiPublicCurationScoringClient.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/infrastructure/ai/AiPublicCurationScoringClientTest.java`
- Modify: `services/ai/app/schemas/public_curation.py`
- Modify: `services/ai/app/services/public_curation_service.py`
- Test: `services/ai/tests/test_public_curation.py`

- [ ] **Step 1: Update Java AI client test**

In `AiPublicCurationScoringClientTest`, assert the outbound body includes the new snake_case field:

```java
assertThat(capturedRequestBody.get()).contains("\"playback_resolution_status\":\"native_tidal\"");
```

Append this argument to the sample `AiPublicCurationCandidateTrack` constructor:

```java
"native_tidal"
```

- [ ] **Step 2: Run Java AI client test and verify it fails**

Run:

```bash
./gradlew test --tests AiPublicCurationScoringClientTest
```

Expected: compile failure or assertion failure because the field is not serialized yet.

- [ ] **Step 3: Add field to Java AI candidate record**

In `AiPublicCurationScoringClient.AiPublicCurationCandidateTrack`, append:

```java
String playbackResolutionStatus
```

Update all constructor calls in public curation tests and controller mappings with either:

```java
PublicCurationCandidatePoolStore.PLAYBACK_RESOLUTION_NATIVE_TIDAL
```

or the candidate’s `playbackResolutionStatus()`.

- [ ] **Step 4: Add Python schema field**

In `services/ai/app/schemas/public_curation.py`, add:

```python
playback_resolution_status: str | None = None
```

to `PublicCurationCandidateTrack`.

- [ ] **Step 5: Add small resolved-target confidence factor**

In `services/ai/app/services/public_curation_service.py`, add this helper:

```python
def _playback_resolution_confidence(self, candidate: PublicCurationCandidateTrack) -> float:
    if candidate.playback_resolution_status == "resolved_to_tidal":
        return 0.96
    if candidate.playback_resolution_status == "native_tidal":
        return 1.0
    return 0.92 if candidate.tidal_track_id and candidate.tidal_uri else 0.0
```

In `_score_candidate`, add:

```python
playback_resolution_confidence = self._playback_resolution_confidence(candidate)
score = score * playback_resolution_confidence
```

and include the rounded value in the returned breakdown:

```python
"playback_resolution_confidence": round(playback_resolution_confidence, 4),
```

- [ ] **Step 6: Add Python scoring test**

In `services/ai/tests/test_public_curation.py`, add a test that uses two identical candidates except `playback_resolution_status` and asserts the resolved candidate receives the small confidence factor:

```python
def test_public_curation_scores_resolved_tidal_candidates_with_small_penalty() -> None:
    payload = sample_payload()
    native = payload["candidate_tracks"][0]
    resolved = {**native, "source_id": "resolved-001", "tidal_track_id": "90002", "playback_resolution_status": "resolved_to_tidal"}
    native = {**native, "source_id": "native-001", "tidal_track_id": "90001", "playback_resolution_status": "native_tidal"}
    payload["candidate_tracks"] = [native, resolved]
    payload["target_track_count"] = 2

    response = client.post("/v1/public-curations/score", json=payload)

    assert response.status_code == 200
    tracks = response.json()["tracks"]
    breakdowns = {track["source_track_id"]: track["score_breakdown"] for track in tracks}
    assert breakdowns["native-001"]["playback_resolution_confidence"] == 1.0
    assert breakdowns["resolved-001"]["playback_resolution_confidence"] == 0.96
```

- [ ] **Step 7: Run AI tests**

Run:

```bash
./gradlew test --tests AiPublicCurationScoringClientTest
cd services/ai && .venv/bin/pytest tests/test_public_curation.py -q
```

Expected: Java test `BUILD SUCCESSFUL`, Python test output shows all selected tests passing.

- [ ] **Step 8: Commit task 4**

Run:

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/publiccuration/infrastructure/ai/AiPublicCurationScoringClient.java services/api/src/test/java/io/myforevermusic/api/modules/publiccuration/infrastructure/ai/AiPublicCurationScoringClientTest.java services/ai/app/schemas/public_curation.py services/ai/app/services/public_curation_service.py services/ai/tests/test_public_curation.py
git commit -m "feat: include playback resolution confidence in public curation scoring"
```

Expected: commit created with only the files listed above.

---

### Task 5: Show Candidate Preparation Summary In Admin UI

**Files:**
- Modify: `apps/web/src/types/api.ts`
- Modify: `apps/web/src/pages/PublicCurationAdminPage.tsx`
- Test: `apps/web/scripts/public-curation-admin-page-harness.mjs`

- [ ] **Step 1: Add frontend type**

In `apps/web/src/types/api.ts`, add:

```ts
export interface PublicCurationCandidatePreparationSummary {
    raw_count?: number
    native_tidal_count?: number
    resolve_attempt_count?: number
    resolved_count?: number
    resolve_failed_count?: number
    skipped_metadata_count?: number
    playable_count?: number
    excluded_count?: number
    resolve_success_ratio?: number
}
```

Change `score_summary` type:

```ts
score_summary: Record<string, unknown> & {
    candidate_preparation?: PublicCurationCandidatePreparationSummary
}
```

- [ ] **Step 2: Add UI helper and labels**

In `PublicCurationAdminPage.tsx`, add:

```ts
const candidatePreparationLabels: Record<string, string> = {
    raw_count: 'Raw 후보',
    playable_count: '재생 가능',
    native_tidal_count: '기존 TIDAL',
    resolved_count: 'Resolve 성공',
    resolve_failed_count: 'Resolve 실패',
    excluded_count: '제외',
}
```

Inside the component:

```ts
const candidatePreparation = draft?.playlist.score_summary.candidate_preparation
```

Update the 후보 풀 help text:

```tsx
<FieldHelp>관리자가 지정하는 최종 playable 후보 수입니다. 내부 raw 후보는 이 값의 3배까지 넓게 확인합니다. 예: 220</FieldHelp>
```

- [ ] **Step 3: Render summary chips in draft result**

Inside the draft result card, below the existing model/status chips, add:

```tsx
{candidatePreparation ? (
    <div className="mt-4 rounded-lg border border-hud-border-secondary bg-hud-bg-secondary p-3">
        <p className="mb-2 text-xs font-semibold uppercase text-hud-text-muted">후보 준비 결과</p>
        <div className="flex flex-wrap gap-2">
            {Object.entries(candidatePreparationLabels).map(([key, label]) => {
                const value = candidatePreparation[key as keyof typeof candidatePreparation]
                if (typeof value !== 'number') {
                    return null
                }
                return (
                    <span key={key} className="rounded-lg border border-hud-border-secondary px-3 py-1.5 text-xs text-hud-text-secondary">
                        {label} {value}
                    </span>
                )
            })}
            {typeof candidatePreparation.resolve_success_ratio === 'number' ? (
                <span className="rounded-lg border border-hud-border-secondary px-3 py-1.5 text-xs text-hud-text-secondary">
                    Resolve 성공률 {Math.round(candidatePreparation.resolve_success_ratio * 100)}%
                </span>
            ) : null}
        </div>
    </div>
) : null}
```

- [ ] **Step 4: Extend admin page harness**

In `apps/web/scripts/public-curation-admin-page-harness.mjs`, extend the mocked run response `score_summary`:

```js
candidate_preparation: {
  raw_count: 75,
  playable_count: 25,
  native_tidal_count: 18,
  resolved_count: 7,
  resolve_failed_count: 3,
  excluded_count: 50,
  resolve_success_ratio: 0.7,
},
```

Add text checks:

```js
await expect(page.getByText('후보 준비 결과')).toBeVisible()
await expect(page.getByText('Raw 후보 75')).toBeVisible()
await expect(page.getByText('Resolve 성공 7')).toBeVisible()
await expect(page.getByText('Resolve 성공률 70%')).toBeVisible()
```

- [ ] **Step 5: Run frontend harness**

Run:

```bash
cd apps/web && node scripts/public-curation-admin-page-harness.mjs
```

Expected: harness exits 0.

- [ ] **Step 6: Commit task 5**

Run:

```bash
git add apps/web/src/types/api.ts apps/web/src/pages/PublicCurationAdminPage.tsx apps/web/scripts/public-curation-admin-page-harness.mjs
git commit -m "feat: show public curation candidate preparation summary"
```

Expected: commit created with only the files listed above.

---

### Task 6: Full Verification And Real DB Smoke

**Files:**
- Verify only.

- [ ] **Step 1: Run public curation backend tests**

Run:

```bash
./gradlew test --tests '*PublicCuration*'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run AI public curation tests**

Run:

```bash
cd services/ai && .venv/bin/pytest tests/test_public_curation.py -q
```

Expected: all tests pass.

- [ ] **Step 3: Run frontend build and admin harness**

Run:

```bash
cd apps/web && node scripts/public-curation-admin-page-harness.mjs && npm run build
```

Expected: harness exits 0 and Vite build succeeds.

- [ ] **Step 4: Run SQL smoke against local PostgreSQL**

Run:

```bash
docker exec my-forever-music-postgres psql -U my_forever_music -d my_forever_music -c "select count(*) from pms_user_track where title is not null and btrim(title) <> '' and artist_name is not null and btrim(artist_name) <> ''; select count(*) from ems_collected_track where title is not null and btrim(title) <> '' and artist_name is not null and btrim(artist_name) <> '';"
```

Expected: both count queries return successfully. Counts may be zero on an empty development database, but SQL must not fail.

- [ ] **Step 5: Run whitespace check**

Run:

```bash
git diff --check
```

Expected: no output and exit 0.

- [ ] **Step 6: Commit verification notes if docs changed during execution**

If this plan or the expanded pool spec was edited during implementation, run:

```bash
git add docs/superpowers/plans/2026-05-31-public-curation-expanded-track-pool.md docs/superpowers/specs/2026-05-31-public-curation-expanded-track-pool-design.md
git commit -m "docs: document expanded public curation track pool rollout"
```

Expected: commit created only when documentation was edited during execution.

---

## Self-Review

- Spec coverage:
  - Raw pool 확장: Task 1.
  - TIDAL resolve gate: Task 2.
  - playable 후보만 AI scoring 전달: Task 3.
  - `playback_resolution_status`: Task 1, Task 3, Task 4.
  - 관리자 요약 표시: Task 3, Task 5.
  - 실제 DB smoke와 public curation regression: Task 6.
- Type consistency:
  - Backend status field: `playbackResolutionStatus`.
  - JSON field: `playback_resolution_status`.
  - Admin summary JSON key: `candidate_preparation`.
  - Summary count keys use snake_case because they are stored in JSON and rendered directly by frontend.
