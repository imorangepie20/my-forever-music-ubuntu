package io.myforevermusic.api.modules.recommendation.application;

import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackRepository;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore.LibraryPlaylistState;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore.LibraryTrackState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AudioFeatureCompletionService {

    private static final String ADMIN_EMAIL = "jowoosungtidal@gmail.com";

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

    public record ProcessCompletionResult(
        int claimedJobCount,
        int completedJobCount,
        int retryWaitJobCount,
        int unresolvedJobCount,
        int failedJobCount
    ) {}
}
