# Audio Feature Unresolved Retry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an admin unresolved retry path for audio feature completion and make Audio Taste profile gates configurable for safe production defaults and faster server verification.

**Architecture:** Extend the existing completion job store with a filtered unresolved lookup, then add a service/controller requeue endpoint that creates new `manual_llm_retry` jobs without mutating historical `pms_import`/`ems_collect` jobs. The worker treats `manual_llm_retry` as an inference-first job, while `AudioTasteProfileService` keeps default gates (`10`, `0.30`) but accepts environment-backed overrides.

**Tech Stack:** Spring Boot 3.5, Java 21 records/services, Spring MVC WebMvc tests, JPA repository query, JUnit 5, Mockito, AssertJ, Gradle.

---

## File Structure

- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionJobStore.java`
  - Add filtered unresolved lookup method used by admin requeue.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/persistence/AudioFeatureCompletionJobRepository.java`
  - Add JPA query for unresolved jobs by scope/user/error.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/persistence/JpaAudioFeatureCompletionJobStore.java`
  - Implement unresolved lookup.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/local/InMemoryAudioFeatureCompletionJobStore.java`
  - Implement unresolved lookup for local profile and tests.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionService.java`
  - Add `requeueUnresolvedJobs(String adminUserId, String targetUserId, String trackScope, String lastError, String retryReason, int limit)`, result record, filters, and duplicate handling through `enqueueIfAbsent`.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/AudioFeatureCompletionAdminController.java`
  - Add `POST /requeue-unresolved` endpoint and response DTO.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionWorkerService.java`
  - Route `manual_llm_retry` jobs through Last.fm/LLM inference before returning unresolved.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileService.java`
  - Make profile gates configurable with default-compatible constructor.
- Modify `services/api/src/main/resources/application.yml`
  - Add env-backed `app.recommendation.audio-taste.*` defaults.
- Modify docs:
  - `docs/api/AUDIO_FEATURE_COMPLETION_ADMIN_API.md`
  - `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`
  - `docs/architecture/AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md`

Tests:

- Modify `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionServiceTest.java`
- Modify `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionWorkerServiceTest.java`
- Modify `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/AudioFeatureCompletionAdminControllerWebMvcTest.java`
- Modify `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileServiceTest.java`

---

### Task 1: Filtered Unresolved Requeue Service

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionJobStore.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/persistence/AudioFeatureCompletionJobRepository.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/persistence/JpaAudioFeatureCompletionJobStore.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/local/InMemoryAudioFeatureCompletionJobStore.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionService.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionServiceTest.java`

- [ ] **Step 1: Write failing service tests**

Append these tests to `AudioFeatureCompletionServiceTest`.

```java
@Test
void shouldRequeueUnresolvedPmsJobsForManualLlmRetry() {
    AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
    PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
    InMemoryJobStore jobStore = new InMemoryJobStore();
    Instant now = Instant.parse("2026-05-21T00:00:00Z");

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
        List.of(
            pmsTrack("pms-track-unresolved", PmsTrackAudioFeatures.unresolved()),
            pmsTrack("pms-track-complete", completePmsFeatures())
        )
    )));
    jobStore.addExisting(new AudioFeatureCompletionJobStore.StoredJob(
        1L,
        "pms_user_track",
        "pms-track-unresolved",
        "target-user",
        100,
        "unresolved",
        "pms_import",
        1,
        null,
        null,
        null,
        "reccobeats_no_match",
        now,
        now
    ));
    jobStore.addExisting(new AudioFeatureCompletionJobStore.StoredJob(
        2L,
        "pms_user_track",
        "pms-track-complete",
        "target-user",
        100,
        "unresolved",
        "pms_import",
        1,
        null,
        null,
        null,
        "reccobeats_no_match",
        now,
        now
    ));

    AudioFeatureCompletionService service = new AudioFeatureCompletionService(
        authAccountStore,
        pmsUserLibraryStore,
        Optional.empty(),
        jobStore
    );

    AudioFeatureCompletionService.RequeueUnresolvedResult result = service.requeueUnresolvedJobs(
        "admin-user",
        "target-user",
        "pms_user_track",
        "reccobeats_no_match",
        "manual_llm_retry",
        50
    );

    assertThat(result.scannedJobCount()).isEqualTo(2);
    assertThat(result.requeuedJobCount()).isEqualTo(1);
    assertThat(result.skippedExistingJobCount()).isZero();
    assertThat(result.jobs()).singleElement().satisfies(job -> {
        assertThat(job.trackId()).isEqualTo("pms-track-unresolved");
        assertThat(job.status()).isEqualTo("queued");
        assertThat(job.requestedReason()).isEqualTo("manual_llm_retry");
        assertThat(job.priority()).isEqualTo(110);
    });
}

@Test
void shouldNotDuplicateExistingManualLlmRetryJobs() {
    AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
    PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
    InMemoryJobStore jobStore = new InMemoryJobStore();
    Instant now = Instant.parse("2026-05-21T00:00:00Z");

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
        List.of(pmsTrack("pms-track-unresolved", PmsTrackAudioFeatures.unresolved()))
    )));
    jobStore.addExisting(new AudioFeatureCompletionJobStore.StoredJob(
        1L,
        "pms_user_track",
        "pms-track-unresolved",
        "target-user",
        100,
        "unresolved",
        "pms_import",
        1,
        null,
        null,
        null,
        "reccobeats_no_match",
        now,
        now
    ));
    jobStore.enqueueIfAbsent(new AudioFeatureCompletionJobStore.Draft(
        "pms_user_track",
        "pms-track-unresolved",
        "target-user",
        110,
        "manual_llm_retry",
        now
    ));

    AudioFeatureCompletionService service = new AudioFeatureCompletionService(
        authAccountStore,
        pmsUserLibraryStore,
        Optional.empty(),
        jobStore
    );

    AudioFeatureCompletionService.RequeueUnresolvedResult result = service.requeueUnresolvedJobs(
        "admin-user",
        "target-user",
        "pms_user_track",
        "reccobeats_no_match",
        "manual_llm_retry",
        50
    );

    assertThat(result.scannedJobCount()).isEqualTo(1);
    assertThat(result.requeuedJobCount()).isZero();
    assertThat(result.skippedExistingJobCount()).isEqualTo(1);
}
```

Update the test helper `InMemoryJobStore` in `AudioFeatureCompletionServiceTest` with:

```java
void addExisting(StoredJob job) {
    jobs.put(job.trackScope() + ":" + job.trackId() + ":" + job.requestedReason(), job);
    sequence = Math.max(sequence, job.jobId() + 1);
}

@Override
public List<StoredJob> findUnresolvedForRequeue(String trackScope, String userId, String lastError, int limit) {
    return jobs.values().stream()
        .filter(job -> "unresolved".equals(job.status()))
        .filter(job -> trackScope == null || trackScope.equals(job.trackScope()))
        .filter(job -> userId == null || userId.equals(job.userId()))
        .filter(job -> lastError == null || lastError.equals(job.lastError()))
        .limit(limit)
        .toList();
}
```

- [ ] **Step 2: Run tests to verify failure**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionServiceTest
```

Expected: FAIL because `requeueUnresolvedJobs` and `findUnresolvedForRequeue` do not exist.

- [ ] **Step 3: Extend store interface and persistence**

In `AudioFeatureCompletionJobStore`, add:

```java
List<StoredJob> findUnresolvedForRequeue(String trackScope, String userId, String lastError, int limit);
```

In `AudioFeatureCompletionJobRepository`, add:

```java
@Query("""
    select job
    from AudioFeatureCompletionJobEntity job
    where job.status = 'unresolved'
      and (:trackScope is null or job.trackScope = :trackScope)
      and (:userId is null or job.userId = :userId)
      and (:lastError is null or job.lastError = :lastError)
    order by job.updatedAt desc, job.jobId desc
    """)
List<AudioFeatureCompletionJobEntity> findUnresolvedForRequeue(
    @Param("trackScope") String trackScope,
    @Param("userId") String userId,
    @Param("lastError") String lastError,
    Pageable pageable
);
```

In `JpaAudioFeatureCompletionJobStore`, add:

```java
@Override
@Transactional(readOnly = true)
public List<StoredJob> findUnresolvedForRequeue(String trackScope, String userId, String lastError, int limit) {
    if (limit <= 0) {
        return List.of();
    }
    return repository.findUnresolvedForRequeue(trackScope, userId, lastError, Pageable.ofSize(limit)).stream()
        .map(AudioFeatureCompletionJobEntity::toState)
        .toList();
}
```

In `InMemoryAudioFeatureCompletionJobStore`, add equivalent stream filtering:

```java
@Override
public List<StoredJob> findUnresolvedForRequeue(String trackScope, String userId, String lastError, int limit) {
    return jobs.values().stream()
        .filter(job -> "unresolved".equals(job.status()))
        .filter(job -> trackScope == null || trackScope.equals(job.trackScope()))
        .filter(job -> userId == null || userId.equals(job.userId()))
        .filter(job -> lastError == null || lastError.equals(job.lastError()))
        .limit(Math.max(0, limit))
        .toList();
}
```

- [ ] **Step 4: Add service method and records**

In `AudioFeatureCompletionService`, add constants:

```java
private static final String MANUAL_LLM_RETRY_REASON = "manual_llm_retry";
```

Add public method:

```java
public RequeueUnresolvedResult requeueUnresolvedJobs(
    String adminUserId,
    String targetUserId,
    String trackScope,
    String lastError,
    String retryReason,
    int limit
) {
    assertAdmin(adminUserId);
    String resolvedTrackScope = normalizeNullableTrackScope(trackScope);
    String resolvedTargetUserId = targetUserId == null || targetUserId.isBlank() ? null : targetUserId.trim();
    String resolvedLastError = lastError == null || lastError.isBlank() ? null : lastError.trim();
    String resolvedRetryReason = normalizeRetryReason(retryReason);
    int resolvedLimit = normalizeLimit(limit <= 0 ? 50 : limit);
    Instant now = Instant.now();
    Map<String, Boolean> pmsCompleteByTrackId = pmsCompletenessByTrackId(resolvedTargetUserId);
    Counter counter = new Counter();
    List<AudioFeatureCompletionJobStore.StoredJob> requeued = new ArrayList<>();
    List<AudioFeatureCompletionJobStore.StoredJob> unresolvedJobs = jobStore.findUnresolvedForRequeue(
        resolvedTrackScope,
        resolvedTargetUserId,
        resolvedLastError,
        resolvedLimit
    );
    for (AudioFeatureCompletionJobStore.StoredJob job : unresolvedJobs) {
        counter.scannedTrackCount++;
        if (isAlreadyComplete(job, pmsCompleteByTrackId)) {
            continue;
        }
        AudioFeatureCompletionJobStore.EnqueueOutcome outcome = jobStore.enqueueIfAbsent(
            new AudioFeatureCompletionJobStore.Draft(
                job.trackScope(),
                job.trackId(),
                job.userId(),
                Math.max(job.priority() + 10, 110),
                resolvedRetryReason,
                now
            )
        );
        if (outcome.inserted()) {
            counter.enqueuedJobCount++;
            requeued.add(outcome.job());
        } else {
            counter.skippedExistingJobCount++;
        }
    }
    return new RequeueUnresolvedResult(
        resolvedTargetUserId,
        resolvedTrackScope == null ? "all" : resolvedTrackScope,
        resolvedLastError,
        resolvedRetryReason,
        counter.scannedTrackCount,
        counter.enqueuedJobCount,
        counter.skippedExistingJobCount,
        List.copyOf(requeued)
    );
}
```

Add helpers:

```java
private String normalizeNullableTrackScope(String trackScope) {
    if (trackScope == null || trackScope.isBlank() || "all".equalsIgnoreCase(trackScope.trim())) {
        return null;
    }
    String normalized = trackScope.trim().toLowerCase(Locale.ROOT);
    if (!"pms_user_track".equals(normalized) && !"ems_collected_track".equals(normalized)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported track_scope: " + trackScope);
    }
    return normalized;
}

private String normalizeRetryReason(String retryReason) {
    if (retryReason == null || retryReason.isBlank()) {
        return MANUAL_LLM_RETRY_REASON;
    }
    String normalized = retryReason.trim().toLowerCase(Locale.ROOT);
    if (!MANUAL_LLM_RETRY_REASON.equals(normalized)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported retry_reason: " + retryReason);
    }
    return normalized;
}

private Map<String, Boolean> pmsCompletenessByTrackId(String targetUserId) {
    if (targetUserId == null || targetUserId.isBlank()) {
        return Map.of();
    }
    Map<String, Boolean> result = new LinkedHashMap<>();
    for (LibraryPlaylistState playlist : pmsUserLibraryStore.findPlaylists(targetUserId)) {
        if (playlist.tracks() == null) {
            continue;
        }
        for (LibraryTrackState track : playlist.tracks()) {
            if (track != null && track.trackId() != null) {
                result.put(track.trackId(), track.audioFeatures() != null && track.audioFeatures().isComplete());
            }
        }
    }
    return result;
}

private boolean isAlreadyComplete(AudioFeatureCompletionJobStore.StoredJob job, Map<String, Boolean> pmsCompleteByTrackId) {
    if ("pms_user_track".equals(job.trackScope())) {
        return Boolean.TRUE.equals(pmsCompleteByTrackId.get(job.trackId()));
    }
    if ("ems_collected_track".equals(job.trackScope()) && emsTrackRepository.isPresent()) {
        Long trackId = parseLong(job.trackId());
        if (trackId == null) {
            return false;
        }
        return emsTrackRepository.get().findById(trackId)
            .map(track -> track.getAudioFeatures() != null && track.getAudioFeatures().isComplete())
            .orElse(false);
    }
    return false;
}

private Long parseLong(String value) {
    try {
        return value == null ? null : Long.parseLong(value);
    } catch (NumberFormatException ignored) {
        return null;
    }
}
```

Add result record near existing records:

```java
public record RequeueUnresolvedResult(
    String targetUserId,
    String trackScope,
    String lastError,
    String retryReason,
    int scannedJobCount,
    int requeuedJobCount,
    int skippedExistingJobCount,
    List<AudioFeatureCompletionJobStore.StoredJob> jobs
) {}
```

Add imports:

```java
import java.util.LinkedHashMap;
import java.util.Map;
```

- [ ] **Step 5: Run service tests to verify pass**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionServiceTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionJobStore.java \
  services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/persistence/AudioFeatureCompletionJobRepository.java \
  services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/persistence/JpaAudioFeatureCompletionJobStore.java \
  services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/local/InMemoryAudioFeatureCompletionJobStore.java \
  services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionServiceTest.java
git commit -m "feat: requeue unresolved audio feature jobs"
```

---

### Task 2: Admin Requeue Endpoint

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/AudioFeatureCompletionAdminController.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/AudioFeatureCompletionAdminControllerWebMvcTest.java`

- [ ] **Step 1: Write failing WebMvc test**

Append to `AudioFeatureCompletionAdminControllerWebMvcTest`:

```java
@Test
void shouldRequeueUnresolvedJobs() throws Exception {
    when(completionService.requeueUnresolvedJobs(
        eq("admin-user"),
        eq("target-user"),
        eq("pms_user_track"),
        eq("reccobeats_no_match"),
        eq("manual_llm_retry"),
        eq(25)
    )).thenReturn(new AudioFeatureCompletionService.RequeueUnresolvedResult(
        "target-user",
        "pms_user_track",
        "reccobeats_no_match",
        "manual_llm_retry",
        25,
        17,
        8,
        List.of(manualLlmRetryJob())
    ));

    mockMvc.perform(post("/api/v1/recommendations/admin/audio-feature-completion/requeue-unresolved")
            .param("user_id", "admin-user")
            .param("target_user_id", "target-user")
            .param("track_scope", "pms_user_track")
            .param("last_error", "reccobeats_no_match")
            .param("retry_reason", "manual_llm_retry")
            .param("limit", "25"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.target_user_id").value("target-user"))
        .andExpect(jsonPath("$.track_scope").value("pms_user_track"))
        .andExpect(jsonPath("$.last_error").value("reccobeats_no_match"))
        .andExpect(jsonPath("$.retry_reason").value("manual_llm_retry"))
        .andExpect(jsonPath("$.scanned_job_count").value(25))
        .andExpect(jsonPath("$.requeued_job_count").value(17))
        .andExpect(jsonPath("$.skipped_existing_job_count").value(8))
        .andExpect(jsonPath("$.jobs[0].requested_reason").value("manual_llm_retry"));
}
```

Add helper:

```java
private AudioFeatureCompletionJobStore.StoredJob manualLlmRetryJob() {
    Instant now = Instant.parse("2026-05-21T00:00:00Z");
    return new AudioFeatureCompletionJobStore.StoredJob(
        77L,
        "pms_user_track",
        "track-llm-retry",
        "target-user",
        110,
        "queued",
        "manual_llm_retry",
        0,
        null,
        null,
        null,
        null,
        now,
        now
    );
}
```

- [ ] **Step 2: Run test to verify failure**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.presentation.AudioFeatureCompletionAdminControllerWebMvcTest
```

Expected: FAIL with 404 for `/requeue-unresolved` or missing controller method.

- [ ] **Step 3: Add controller endpoint and response**

In `AudioFeatureCompletionAdminController`, add:

```java
@Operation(summary = "Requeue unresolved audio feature completion jobs for manual inference retry")
@PostMapping("/requeue-unresolved")
public RequeueUnresolvedResponse requeueUnresolvedJobs(
    @RequestParam("user_id") String userId,
    @RequestParam(value = "target_user_id", required = false) String targetUserId,
    @RequestParam(value = "track_scope", required = false) String trackScope,
    @RequestParam(value = "last_error", required = false) String lastError,
    @RequestParam(value = "retry_reason", defaultValue = "manual_llm_retry") String retryReason,
    @RequestParam(value = "limit", defaultValue = "50") int limit
) {
    return RequeueUnresolvedResponse.from(completionService.requeueUnresolvedJobs(
        userId,
        targetUserId,
        trackScope,
        lastError,
        retryReason,
        limit
    ));
}
```

Add response record:

```java
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RequeueUnresolvedResponse(
    String targetUserId,
    String trackScope,
    String lastError,
    String retryReason,
    int scannedJobCount,
    int requeuedJobCount,
    int skippedExistingJobCount,
    List<CompletionJobItem> jobs
) {
    static RequeueUnresolvedResponse from(AudioFeatureCompletionService.RequeueUnresolvedResult result) {
        return new RequeueUnresolvedResponse(
            result.targetUserId(),
            result.trackScope(),
            result.lastError(),
            result.retryReason(),
            result.scannedJobCount(),
            result.requeuedJobCount(),
            result.skippedExistingJobCount(),
            result.jobs().stream().map(CompletionJobItem::from).toList()
        );
    }
}
```

- [ ] **Step 4: Run WebMvc test to verify pass**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.presentation.AudioFeatureCompletionAdminControllerWebMvcTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/AudioFeatureCompletionAdminController.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/AudioFeatureCompletionAdminControllerWebMvcTest.java
git commit -m "feat: expose unresolved audio feature requeue API"
```

---

### Task 3: Manual LLM Retry Worker Path

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionWorkerService.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionWorkerServiceTest.java`

- [ ] **Step 1: Write failing worker test**

Add static import:

```java
import static org.mockito.Mockito.never;
```

Append to `AudioFeatureCompletionWorkerServiceTest`:

```java
@Test
void shouldSkipReccoBeatsAndCompleteManualLlmRetryWithLlmSearchInference() {
    InMemoryJobStore jobStore = new InMemoryJobStore();
    PmsUserTrackRepository pmsTrackRepository = mock(PmsUserTrackRepository.class);
    EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
    ReccoBeatsAudioFeaturesClient reccoBeatsClient = mock(ReccoBeatsAudioFeaturesClient.class);
    LastFmAudioFeatureInferenceService lastFmInferenceService = mock(LastFmAudioFeatureInferenceService.class);
    AudioFeatureLlmSearchInferenceService llmSearchInferenceService = mock(AudioFeatureLlmSearchInferenceService.class);
    PmsUserTrackEntity track = new PmsUserTrackEntity(pmsTrack("pms-track-001", "spotify-track-001"));

    jobStore.addQueued(new AudioFeatureCompletionJobStore.StoredJob(
        1L,
        "pms_user_track",
        "pms-track-001",
        "user-001",
        110,
        "queued",
        "manual_llm_retry",
        0,
        null,
        null,
        null,
        null,
        Instant.parse("2026-05-21T00:00:00Z"),
        Instant.parse("2026-05-21T00:00:00Z")
    ));
    when(pmsTrackRepository.findById("pms-track-001")).thenReturn(Optional.of(track));
    when(lastFmInferenceService.infer(any())).thenReturn(Optional.empty());
    when(llmSearchInferenceService.infer(any()))
        .thenReturn(Optional.of(new AudioFeatureLlmSearchInferenceService.InferredAudioFeatureSnapshot(
            "llm_search_inferred",
            "llm_search_inferred",
            0.82d,
            "audio-feature-llm-search-v1:test-audio-model",
            true,
            180_000,
            5,
            1,
            0.18d,
            0.74d,
            0.79d,
            0.03d,
            0.12d,
            -6.8d,
            0.05d,
            121.0d,
            0.62d,
            Instant.parse("2026-05-21T01:00:00Z")
        )));

    AudioFeatureCompletionWorkerService service = new AudioFeatureCompletionWorkerService(
        jobStore,
        pmsTrackRepository,
        emsTrackRepository,
        reccoBeatsClient,
        Optional.of(lastFmInferenceService),
        Optional.of(llmSearchInferenceService)
    );

    AudioFeatureCompletionWorkerService.ProcessCompletionResult result = service.processQueuedJobs("worker-001", 10);

    assertThat(result.completedJobCount()).isEqualTo(1);
    assertThat(jobStore.findById(1L).status()).isEqualTo("completed");
    assertThat(track.getAudioFeatures().getAudioFeatureSource()).isEqualTo("llm_search_inferred");
    assertThat(track.getAudioFeatures().isComplete()).isTrue();
    verify(reccoBeatsClient, never()).getAudioFeaturesForSpotifyTrackIds(any());
}
```

- [ ] **Step 2: Run test to verify failure**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionWorkerServiceTest
```

Expected: FAIL because `manual_llm_retry` still enters the ReccoBeats path.

- [ ] **Step 3: Add manual retry routing**

In `AudioFeatureCompletionWorkerService`, add:

```java
private static final String MANUAL_LLM_RETRY_REASON = "manual_llm_retry";
```

Change `processJob` switch to pass the full job:

```java
ProcessStatus status = switch (job.trackScope()) {
    case "pms_user_track" -> processPmsTrack(job);
    case "ems_collected_track" -> processEmsTrack(job);
    default -> ProcessStatus.failed("unsupported_track_scope:" + job.trackScope());
};
```

Replace the existing `processPmsTrack(String trackId)` body with:

```java
private ProcessStatus processPmsTrack(AudioFeatureCompletionJobStore.StoredJob job) {
    PmsUserTrackEntity track = pmsTrackRepository.findById(job.trackId()).orElse(null);
    if (track == null) {
        return ProcessStatus.failed("pms_track_not_found");
    }
    if (MANUAL_LLM_RETRY_REASON.equals(job.requestedReason())) {
        return processPmsInferenceOnly(track);
    }

    ReccoBeatsAudioFeaturesSnapshot snapshot = resolvePmsSnapshot(track);
    if (snapshot == null) {
        ProcessStatus inferredStatus = processPmsInferenceOnly(track);
        return "manual_llm_retry_no_inference_result".equals(inferredStatus.message())
            ? ProcessStatus.unresolved("reccobeats_no_match")
            : inferredStatus;
    }

    PmsTrackAudioFeatures audioFeatures = toPmsAudioFeatures(track, snapshot, Instant.now());
    track.applyAudioFeatures(audioFeatures);
    pmsTrackRepository.save(track);
    return audioFeatures.isComplete()
        ? ProcessStatus.completed()
        : ProcessStatus.unresolved("reccobeats_incomplete_audio_features");
}
```

Replace the existing `processEmsTrack(String trackId)` body with:

```java
private ProcessStatus processEmsTrack(AudioFeatureCompletionJobStore.StoredJob job) {
    Long emsTrackId = parseLong(job.trackId());
    if (emsTrackId == null) {
        return ProcessStatus.failed("invalid_ems_track_id");
    }
    EmsCollectedTrackEntity track = emsTrackRepository.findById(emsTrackId).orElse(null);
    if (track == null) {
        return ProcessStatus.failed("ems_track_not_found");
    }
    if (MANUAL_LLM_RETRY_REASON.equals(job.requestedReason())) {
        return processEmsInferenceOnly(track);
    }

    ReccoBeatsAudioFeaturesSnapshot snapshot = resolveEmsSnapshot(track);
    if (snapshot == null) {
        ProcessStatus inferredStatus = processEmsInferenceOnly(track);
        return "manual_llm_retry_no_inference_result".equals(inferredStatus.message())
            ? ProcessStatus.unresolved("reccobeats_no_match")
            : inferredStatus;
    }

    EmsTrackAudioFeatures audioFeatures = toEmsAudioFeatures(track, snapshot, Instant.now());
    track.applyAudioFeatures(audioFeatures);
    emsTrackRepository.save(track);
    return hasCompleteAudioFeatures(snapshot, track.getDurationMs())
        ? ProcessStatus.completed()
        : ProcessStatus.unresolved("reccobeats_incomplete_audio_features");
}
```

Add helper methods by extracting the existing inference blocks:

```java
private ProcessStatus processPmsInferenceOnly(PmsUserTrackEntity track) {
    LastFmAudioFeatureInferenceService.InferredAudioFeatureSnapshot inferredSnapshot = inferPmsSnapshot(track);
    if (inferredSnapshot != null) {
        track.applyAudioFeatures(toPmsAudioFeatures(track, inferredSnapshot));
        pmsTrackRepository.save(track);
        return ProcessStatus.unresolved("lastfm_tag_inferred_partial_audio_features");
    }
    AudioFeatureLlmSearchInferenceService.InferredAudioFeatureSnapshot llmSnapshot = inferPmsLlmSearchSnapshot(track);
    if (llmSnapshot != null) {
        PmsTrackAudioFeatures audioFeatures = toPmsAudioFeatures(track, llmSnapshot);
        track.applyAudioFeatures(audioFeatures);
        pmsTrackRepository.save(track);
        return audioFeatures.isComplete()
            ? ProcessStatus.completed()
            : ProcessStatus.unresolved("llm_search_inferred_partial_audio_features");
    }
    return ProcessStatus.unresolved("manual_llm_retry_no_inference_result");
}

private ProcessStatus processEmsInferenceOnly(EmsCollectedTrackEntity track) {
    LastFmAudioFeatureInferenceService.InferredAudioFeatureSnapshot inferredSnapshot = inferEmsSnapshot(track);
    if (inferredSnapshot != null) {
        track.applyAudioFeatures(toEmsAudioFeatures(track, inferredSnapshot));
        emsTrackRepository.save(track);
        return ProcessStatus.unresolved("lastfm_tag_inferred_partial_audio_features");
    }
    AudioFeatureLlmSearchInferenceService.InferredAudioFeatureSnapshot llmSnapshot = inferEmsLlmSearchSnapshot(track);
    if (llmSnapshot != null) {
        EmsTrackAudioFeatures audioFeatures = toEmsAudioFeatures(track, llmSnapshot);
        track.applyAudioFeatures(audioFeatures);
        emsTrackRepository.save(track);
        return hasCompleteLlmAudioFeatures(llmSnapshot)
            ? ProcessStatus.completed()
            : ProcessStatus.unresolved("llm_search_inferred_partial_audio_features");
    }
    return ProcessStatus.unresolved("manual_llm_retry_no_inference_result");
}
```

The `snapshot == null` block in `processPmsTrack` must preserve the final `reccobeats_no_match` message for default jobs:

```java
if (snapshot == null) {
    ProcessStatus inferredStatus = processPmsInferenceOnly(track);
    return "manual_llm_retry_no_inference_result".equals(inferredStatus.message())
        ? ProcessStatus.unresolved("reccobeats_no_match")
        : inferredStatus;
}
```

The `snapshot == null` block in `processEmsTrack` must use the same mapping:

```java
if (snapshot == null) {
    ProcessStatus inferredStatus = processEmsInferenceOnly(track);
    return "manual_llm_retry_no_inference_result".equals(inferredStatus.message())
        ? ProcessStatus.unresolved("reccobeats_no_match")
        : inferredStatus;
}
```

- [ ] **Step 4: Run worker tests to verify pass**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionWorkerServiceTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionWorkerService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioFeatureCompletionWorkerServiceTest.java
git commit -m "feat: prioritize inference for manual audio retries"
```

---

### Task 4: Configurable Audio Taste Gates

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileService.java`
- Modify: `services/api/src/main/resources/application.yml`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileServiceTest.java`

- [ ] **Step 1: Write failing profile gate test**

Append to `AudioTasteProfileServiceTest`:

```java
@Test
void shouldAllowLowerFeatureCoverageGateForServerVerification() {
    InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
    InMemoryTrackAudioFeatureEvidenceStore evidenceStore = new InMemoryTrackAudioFeatureEvidenceStore();
    PmsUserLibraryStore libraryStore = new PmsUserLibraryStore() {
        @Override
        public List<PmsUserLibraryStore.LibraryPlaylistState> findPlaylists(String userId) {
            List<PmsUserLibraryStore.LibraryTrackState> tracks = new java.util.ArrayList<>();
            IntStream.rangeClosed(1, 10)
                .mapToObj(index -> track("ready-" + index, "Ready " + index, 0.80d, 0.70d, 0.90d))
                .forEach(tracks::add);
            IntStream.rangeClosed(1, 90)
                .mapToObj(index -> track("missing-" + index, "Missing " + index, 0.80d, 0.70d, 0.90d, PmsTrackAudioFeatures.unresolved()))
                .forEach(tracks::add);
            return List.of(new PmsUserLibraryStore.LibraryPlaylistState(
                userId,
                "playlist-coverage",
                "external-coverage",
                "Coverage",
                "spotify",
                "me",
                "",
                null,
                null,
                null,
                Instant.parse("2026-05-21T00:00:00Z"),
                tracks
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
    IntStream.rangeClosed(1, 10)
        .forEach(index -> eventStore.save(event("user-1", "track_saved", "ready-" + index, 2.0d)));

    AudioTasteProfileService.Profile defaultProfile = new AudioTasteProfileService(
        libraryStore,
        eventStore,
        evidenceStore,
        new EventSignalWeights()
    ).recompute("user-1", 100);
    AudioTasteProfileService.Profile relaxedProfile = new AudioTasteProfileService(
        libraryStore,
        eventStore,
        evidenceStore,
        new EventSignalWeights(),
        10,
        0.07d
    ).recompute("user-1", 100);

    assertThat(defaultProfile.audioTasteApplicable()).isFalse();
    assertThat(defaultProfile.warnings()).contains("Audio taste feature coverage is below 0.30.");
    assertThat(relaxedProfile.audioTasteApplicable()).isTrue();
    assertThat(relaxedProfile.status()).isEqualTo("ok");
}
```

Overload test helper `track`:

```java
private PmsUserLibraryStore.LibraryTrackState track(
    String trackId,
    String title,
    double energy,
    double valence,
    double danceability,
    PmsTrackAudioFeatures audioFeatures
) {
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
        audioFeatures
    );
}
```

Change the existing five-argument `track` helper to delegate:

```java
private PmsUserLibraryStore.LibraryTrackState track(
    String trackId,
    String title,
    double energy,
    double valence,
    double danceability
) {
    return track(trackId, title, energy, valence, danceability, features(energy, valence, danceability));
}
```

- [ ] **Step 2: Run test to verify failure**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileServiceTest
```

Expected: FAIL because the six-argument constructor does not exist and warning is hard-coded.

- [ ] **Step 3: Implement configurable gates**

In `AudioTasteProfileService`, add import:

```java
import org.springframework.beans.factory.annotation.Value;
```

Replace constants with defaults:

```java
private static final int DEFAULT_EVENT_LIMIT = 500;
private static final int DEFAULT_MIN_POSITIVE_READY_TRACKS = 10;
private static final double DEFAULT_MIN_FEATURE_READY_RATIO = 0.30d;
```

Add fields:

```java
private final int minPositiveReadyTracks;
private final double minFeatureReadyRatio;
```

Keep the existing four-argument constructor as default:

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
        DEFAULT_MIN_POSITIVE_READY_TRACKS,
        DEFAULT_MIN_FEATURE_READY_RATIO
    );
}
```

Add Spring constructor:

```java
@Autowired
public AudioTasteProfileService(
    PmsUserLibraryStore libraryStore,
    UserMusicEventStore eventStore,
    TrackAudioFeatureEvidenceStore evidenceStore,
    EventSignalWeights eventSignalWeights,
    @Value("${app.recommendation.audio-taste.min-positive-ready-tracks:10}") int minPositiveReadyTracks,
    @Value("${app.recommendation.audio-taste.min-feature-ready-ratio:0.30}") double minFeatureReadyRatio
) {
    this.libraryStore = libraryStore;
    this.eventStore = eventStore;
    this.evidenceStore = evidenceStore;
    this.eventSignalWeights = eventSignalWeights;
    this.minPositiveReadyTracks = Math.max(1, minPositiveReadyTracks);
    this.minFeatureReadyRatio = Math.max(0.0d, Math.min(1.0d, minFeatureReadyRatio));
}
```

If Spring complains about two constructors, annotate only the six-argument constructor with `@Autowired`.

Update gate logic:

```java
boolean applicable = positives.size() >= minPositiveReadyTracks
    && coverage.featureReadyRatio() >= minFeatureReadyRatio;
if (positives.size() < minPositiveReadyTracks) {
    warnings.add("Audio taste profile requires at least %d positive feature-ready tracks.".formatted(minPositiveReadyTracks));
}
if (coverage.featureReadyRatio() < minFeatureReadyRatio) {
    warnings.add("Audio taste feature coverage is below %.2f.".formatted(minFeatureReadyRatio));
}
```

In `application.yml`, add under `app.recommendation`:

```yaml
  recommendation:
    audio-taste:
      min-positive-ready-tracks: ${AUDIO_TASTE_MIN_POSITIVE_READY_TRACKS:10}
      min-feature-ready-ratio: ${AUDIO_TASTE_MIN_FEATURE_READY_RATIO:0.30}
```

If `app.recommendation` already has nested keys lower in the file, merge the `audio-taste` block into the existing `recommendation` section rather than duplicating keys.

- [ ] **Step 4: Run profile tests to verify pass**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileServiceTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileService.java \
  services/api/src/main/resources/application.yml \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/AudioTasteProfileServiceTest.java
git commit -m "feat: configure audio taste readiness gates"
```

---

### Task 5: Documentation and Verification

**Files:**
- Modify: `docs/api/AUDIO_FEATURE_COMPLETION_ADMIN_API.md`
- Modify: `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`
- Modify: `docs/architecture/AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md`

- [ ] **Step 1: Update API docs**

In `docs/api/AUDIO_FEATURE_COMPLETION_ADMIN_API.md`, add a section after “Process queued jobs”:

```markdown
## 5. Requeue unresolved jobs

```http
POST /api/v1/recommendations/admin/audio-feature-completion/requeue-unresolved
```

이 endpoint는 `unresolved` 상태로 굳은 completion job을 새 `manual_llm_retry` job으로 다시 queue에 넣습니다. 기존 `pms_import`/`ems_collect` job은 수정하지 않고, `track_scope + track_id + requested_reason` identity 규칙에 따라 retry 이력을 분리합니다.

Query parameters:

| Name | Required | Default | Description |
| --- | --- | --- | --- |
| `user_id` | yes | - | 관리자 사용자 ID |
| `target_user_id` | no | - | PMS job 대상 사용자 ID |
| `track_scope` | no | `all` | `pms_user_track`, `ems_collected_track`, `all` |
| `last_error` | no | all | exact match. 예: `reccobeats_no_match` |
| `retry_reason` | no | `manual_llm_retry` | v1은 `manual_llm_retry`만 허용 |
| `limit` | no | `50` | 최대 requeue job 수 |

`manual_llm_retry` job은 ReccoBeats 반복 조회를 줄이고 Last.fm/LLM search inference를 우선 시도합니다. `completed_job_count=0`이 반복될 때는 먼저 `claimed_job_count`와 `unresolved_job_count`를 확인하고, `reccobeats_no_match` unresolved가 많을 때 이 endpoint를 사용합니다.
```

After inserting this section, renumber later headings so the document remains sequential.

In `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`, add under “Current limits”:

```markdown
## Runtime gate configuration

| Env | Default | Description |
| --- | ---: | --- |
| `AUDIO_TASTE_MIN_POSITIVE_READY_TRACKS` | `10` | positive event 기반 feature-ready track 최소 수 |
| `AUDIO_TASTE_MIN_FEATURE_READY_RATIO` | `0.30` | PMS library usable audio feature coverage 최소 비율 |

운영 기본값은 `0.30`을 유지합니다. 서버 검증 중에는 `AUDIO_TASTE_MIN_FEATURE_READY_RATIO=0.07` 또는 `0.10`으로 낮춰 `audio-taste:v1` serving 연결만 먼저 확인할 수 있습니다. 검증 후 기본값으로 되돌려야 합니다.
```

In architecture plan, add a note under Phase 6 implementation state:

```markdown
- `unresolved` audio feature job은 `manual_llm_retry`로 별도 requeue 할 수 있게 하여, ReccoBeats no-match 이후 Last.fm/LLM search inference를 운영자가 다시 시도할 수 있다.
```

- [ ] **Step 2: Run targeted tests**

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionServiceTest \
  --tests io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionWorkerServiceTest \
  --tests io.myforevermusic.api.modules.recommendation.presentation.AudioFeatureCompletionAdminControllerWebMvcTest \
  --tests io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileServiceTest
```

Expected: PASS.

- [ ] **Step 3: Run full API tests**

```bash
cd services/api
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit docs**

```bash
git add docs/api/AUDIO_FEATURE_COMPLETION_ADMIN_API.md \
  docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md \
  docs/architecture/AUDIO_FEATURE_COMPLETION_AND_HYBRID_PERSONALIZATION_PLAN.md
git commit -m "docs: document unresolved audio retry operations"
```

---

## Self-Review

- Spec coverage: unresolved requeue API, manual LLM retry worker path, configurable Audio Taste gates, operating docs, tests, and verification are covered.
- Scope control: no new tables, no frontend, no LLM schema change, no provider resolver expansion.
- Type consistency: `manual_llm_retry`, `findUnresolvedForRequeue`, `RequeueUnresolvedResult`, `requeueUnresolvedJobs`, and `RequeueUnresolvedResponse` names are used consistently.
- Test discipline: every behavior-changing task starts with failing tests, then implementation, then targeted verification and commit.
