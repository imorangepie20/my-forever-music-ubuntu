package io.myforevermusic.api.modules.recommendation.application;

import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsTrackAudioFeatures;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore.LibraryPlaylistState;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore.LibraryTrackState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AudioFeatureCompletionService {

    private static final String ADMIN_EMAIL = "jowoosungtidal@gmail.com";
    private static final String MANUAL_LLM_RETRY_REASON = "manual_llm_retry";

    private final AuthAccountStore authAccountStore;
    private final PmsUserLibraryStore pmsUserLibraryStore;
    private final Optional<EmsCollectedTrackRepository> emsTrackRepository;
    private final AudioFeatureCompletionJobStore jobStore;
    private final Optional<AudioFeatureCompletionWorkerService> workerService;

    @Autowired
    public AudioFeatureCompletionService(
        AuthAccountStore authAccountStore,
        PmsUserLibraryStore pmsUserLibraryStore,
        Optional<EmsCollectedTrackRepository> emsTrackRepository,
        AudioFeatureCompletionJobStore jobStore,
        Optional<AudioFeatureCompletionWorkerService> workerService
    ) {
        this.authAccountStore = authAccountStore;
        this.pmsUserLibraryStore = pmsUserLibraryStore;
        this.emsTrackRepository = emsTrackRepository;
        this.jobStore = jobStore;
        this.workerService = workerService;
    }

    public AudioFeatureCompletionService(
        AuthAccountStore authAccountStore,
        PmsUserLibraryStore pmsUserLibraryStore,
        Optional<EmsCollectedTrackRepository> emsTrackRepository,
        AudioFeatureCompletionJobStore jobStore
    ) {
        this(authAccountStore, pmsUserLibraryStore, emsTrackRepository, jobStore, Optional.empty());
    }

    public EnqueueCompletionResult enqueueMissingAudioFeatures(
        String adminUserId,
        String targetUserId,
        String scope,
        int pmsLimit,
        int emsLimit
    ) {
        assertAdmin(adminUserId);
        String resolvedScope = normalizeScope(scope);
        String resolvedTargetUserId = targetUserId == null || targetUserId.isBlank()
            ? adminUserId
            : targetUserId.trim();
        Instant now = Instant.now();
        List<AudioFeatureCompletionJobStore.StoredJob> jobs = new ArrayList<>();
        Counter counter = new Counter();

        if ("all".equals(resolvedScope) || "pms".equals(resolvedScope)) {
            enqueuePmsMissingTracks(resolvedTargetUserId, normalizeLimit(pmsLimit), now, counter, jobs);
        }
        if ("all".equals(resolvedScope) || "ems".equals(resolvedScope)) {
            enqueueEmsMissingTracks(normalizeLimit(emsLimit), now, counter, jobs);
        }

        return new EnqueueCompletionResult(
            resolvedTargetUserId,
            resolvedScope,
            counter.scannedTrackCount,
            counter.enqueuedJobCount,
            counter.skippedExistingJobCount,
            jobs
        );
    }

    public List<AudioFeatureCompletionJobStore.StoredJob> findRecentJobs(String adminUserId, String status, int limit) {
        assertAdmin(adminUserId);
        return jobStore.findRecent(status == null || status.isBlank() ? null : status.trim(), normalizeLimit(limit));
    }

    public ProcessCompletionResult processQueuedJobs(String adminUserId, String workerId, int limit) {
        assertAdmin(adminUserId);
        AudioFeatureCompletionWorkerService worker = workerService.orElseThrow(() -> new ResponseStatusException(
            HttpStatus.PRECONDITION_FAILED,
            "Audio feature completion worker is not available for this profile."
        ));
        AudioFeatureCompletionWorkerService.ProcessCompletionResult result = worker.processQueuedJobs(workerId, limit);
        return new ProcessCompletionResult(
            result.claimedJobCount(),
            result.completedJobCount(),
            result.retryWaitJobCount(),
            result.unresolvedJobCount(),
            result.failedJobCount()
        );
    }

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
        int pageSize = Math.max(50, Math.min(200, resolvedLimit));
        Instant now = Instant.now();
        Map<String, Map<String, Boolean>> pmsCompletenessByUserId = new LinkedHashMap<>();
        if (resolvedTargetUserId != null) {
            pmsCompletenessByUserId.put(resolvedTargetUserId, pmsCompletenessByTrackId(resolvedTargetUserId));
        }
        Counter counter = new Counter();
        List<AudioFeatureCompletionJobStore.StoredJob> requeued = new ArrayList<>();
        Instant beforeUpdatedAt = null;
        Long beforeJobId = null;

        while (counter.enqueuedJobCount < resolvedLimit) {
            List<AudioFeatureCompletionJobStore.StoredJob> unresolvedJobs = jobStore.findUnresolvedForRequeue(
                resolvedTrackScope,
                resolvedTargetUserId,
                resolvedLastError,
                beforeUpdatedAt,
                beforeJobId,
                pageSize
            );
            if (unresolvedJobs.isEmpty()) {
                break;
            }
            for (AudioFeatureCompletionJobStore.StoredJob job : unresolvedJobs) {
                if (counter.enqueuedJobCount >= resolvedLimit) {
                    break;
                }
                counter.scannedTrackCount++;
                if (isAlreadyComplete(job, pmsCompletenessByUserId)) {
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
            AudioFeatureCompletionJobStore.StoredJob lastJob = unresolvedJobs.get(unresolvedJobs.size() - 1);
            beforeUpdatedAt = lastJob.updatedAt();
            beforeJobId = lastJob.jobId();
            if (unresolvedJobs.size() < pageSize || beforeUpdatedAt == null || beforeJobId == null) {
                break;
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

    private void enqueuePmsMissingTracks(
        String targetUserId,
        int limit,
        Instant now,
        Counter counter,
        List<AudioFeatureCompletionJobStore.StoredJob> jobs
    ) {
        int scanned = 0;
        for (LibraryPlaylistState playlist : pmsUserLibraryStore.findPlaylists(targetUserId)) {
            if (playlist.tracks() == null) {
                continue;
            }
            for (LibraryTrackState track : playlist.tracks()) {
                if (track == null || scanned >= limit) {
                    return;
                }
                scanned++;
                counter.scannedTrackCount++;
                if (track.audioFeatures() != null && track.audioFeatures().isComplete()) {
                    continue;
                }
                enqueue(
                    new AudioFeatureCompletionJobStore.Draft(
                        "pms_user_track",
                        track.trackId(),
                        targetUserId,
                        100,
                        "pms_import",
                        now
                    ),
                    counter,
                    jobs
                );
            }
        }
    }

    private void enqueueEmsMissingTracks(
        int limit,
        Instant now,
        Counter counter,
        List<AudioFeatureCompletionJobStore.StoredJob> jobs
    ) {
        if (emsTrackRepository.isEmpty()) {
            return;
        }
        List<EmsCollectedTrackEntity> candidates = emsTrackRepository.get()
            .findAudioFeatureCompletionCandidates(Pageable.ofSize(limit));
        for (EmsCollectedTrackEntity track : candidates) {
            if (track == null || track.getId() == null) {
                continue;
            }
            counter.scannedTrackCount++;
            enqueue(
                new AudioFeatureCompletionJobStore.Draft(
                    "ems_collected_track",
                    String.valueOf(track.getId()),
                    null,
                    60,
                    "ems_collect",
                    now
                ),
                counter,
                jobs
            );
        }
    }

    private void enqueue(
        AudioFeatureCompletionJobStore.Draft draft,
        Counter counter,
        List<AudioFeatureCompletionJobStore.StoredJob> jobs
    ) {
        if (draft.trackId() == null || draft.trackId().isBlank()) {
            return;
        }
        AudioFeatureCompletionJobStore.EnqueueOutcome outcome = jobStore.enqueueIfAbsent(draft);
        jobs.add(outcome.job());
        if (outcome.inserted()) {
            counter.enqueuedJobCount++;
        } else {
            counter.skippedExistingJobCount++;
        }
    }

    private void assertAdmin(String userId) {
        String normalizedEmail = authAccountStore.findByUserId(userId)
            .map(account -> account.normalizedEmail())
            .orElse("");
        if (!ADMIN_EMAIL.equals(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Audio feature completion admin access is restricted.");
        }
    }

    private String normalizeScope(String scope) {
        String value = scope == null || scope.isBlank() ? "all" : scope.trim().toLowerCase(Locale.ROOT);
        if ("all".equals(value) || "pms".equals(value) || "ems".equals(value)) {
            return value;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scope must be one of: all, pms, ems.");
    }

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

    private boolean isAlreadyComplete(
        AudioFeatureCompletionJobStore.StoredJob job,
        Map<String, Map<String, Boolean>> pmsCompletenessByUserId
    ) {
        if ("pms_user_track".equals(job.trackScope())) {
            if (job.userId() == null || job.userId().isBlank()) {
                return false;
            }
            Map<String, Boolean> pmsCompleteByTrackId = pmsCompletenessByUserId.computeIfAbsent(
                job.userId(),
                this::pmsCompletenessByTrackId
            );
            return Boolean.TRUE.equals(pmsCompleteByTrackId.get(job.trackId()));
        }
        if ("ems_collected_track".equals(job.trackScope()) && emsTrackRepository.isPresent()) {
            Long trackId = parseLong(job.trackId());
            if (trackId == null) {
                return false;
            }
            return emsTrackRepository.get().findById(trackId)
                .map(track -> hasCompleteAudioFeatures(track.getAudioFeatures()))
                .orElse(false);
        }
        return false;
    }

    private boolean hasCompleteAudioFeatures(EmsTrackAudioFeatures audioFeatures) {
        return audioFeatures != null
            && audioFeatures.isAudioFeaturesFilled()
            && audioFeatures.getDurationMs() != null
            && audioFeatures.getMusicalKey() != null
            && audioFeatures.getMode() != null
            && audioFeatures.getAcousticness() != null
            && audioFeatures.getDanceability() != null
            && audioFeatures.getEnergy() != null
            && audioFeatures.getInstrumentalness() != null
            && audioFeatures.getLiveness() != null
            && audioFeatures.getLoudness() != null
            && audioFeatures.getSpeechiness() != null
            && audioFeatures.getTempo() != null
            && audioFeatures.getValence() != null;
    }

    private Long parseLong(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 100;
        }
        return Math.min(limit, 1000);
    }

    private static class Counter {
        private int scannedTrackCount;
        private int enqueuedJobCount;
        private int skippedExistingJobCount;
    }

    public record EnqueueCompletionResult(
        String targetUserId,
        String scope,
        int scannedTrackCount,
        int enqueuedJobCount,
        int skippedExistingJobCount,
        List<AudioFeatureCompletionJobStore.StoredJob> jobs
    ) {}

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

    public record ProcessCompletionResult(
        int claimedJobCount,
        int completedJobCount,
        int retryWaitJobCount,
        int unresolvedJobCount,
        int failedJobCount
    ) {}
}
