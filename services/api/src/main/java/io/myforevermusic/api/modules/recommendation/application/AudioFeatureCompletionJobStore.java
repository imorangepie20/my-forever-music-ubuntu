package io.myforevermusic.api.modules.recommendation.application;

import java.time.Instant;
import java.util.List;

public interface AudioFeatureCompletionJobStore {

    EnqueueOutcome enqueueIfAbsent(Draft draft);

    List<StoredJob> findRecent(String status, int limit);

    List<StoredJob> claimQueued(String workerId, int limit, Instant now);

    void markCompleted(Long jobId, Instant now);

    void markRetryWait(Long jobId, String lastError, Instant nextRetryAt, Instant now);

    void markUnresolved(Long jobId, String lastError, Instant now);

    void markFailed(Long jobId, String lastError, Instant now);

    record Draft(
        String trackScope,
        String trackId,
        String userId,
        int priority,
        String requestedReason,
        Instant createdAt
    ) {}

    record EnqueueOutcome(
        StoredJob job,
        boolean inserted
    ) {}

    record StoredJob(
        Long jobId,
        String trackScope,
        String trackId,
        String userId,
        int priority,
        String status,
        String requestedReason,
        int attemptCount,
        Instant nextRetryAt,
        Instant lockedAt,
        String lockedBy,
        String lastError,
        Instant createdAt,
        Instant updatedAt
    ) {}
}
