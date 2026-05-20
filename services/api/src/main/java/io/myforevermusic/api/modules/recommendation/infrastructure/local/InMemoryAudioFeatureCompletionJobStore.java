package io.myforevermusic.api.modules.recommendation.infrastructure.local;

import io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionJobStore;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class InMemoryAudioFeatureCompletionJobStore implements AudioFeatureCompletionJobStore {

    private final AtomicLong sequence = new AtomicLong(1);
    private final Map<String, StoredJob> jobByIdentity = new ConcurrentHashMap<>();

    @Override
    public EnqueueOutcome enqueueIfAbsent(Draft draft) {
        String identity = identity(draft.trackScope(), draft.trackId(), draft.requestedReason());
        StoredJob existing = jobByIdentity.get(identity);
        if (existing != null) {
            return new EnqueueOutcome(existing, false);
        }
        StoredJob stored = new StoredJob(
            sequence.getAndIncrement(),
            draft.trackScope(),
            draft.trackId(),
            draft.userId(),
            draft.priority(),
            "queued",
            draft.requestedReason(),
            0,
            null,
            null,
            null,
            null,
            draft.createdAt(),
            draft.createdAt()
        );
        StoredJob raced = jobByIdentity.putIfAbsent(identity, stored);
        if (raced != null) {
            return new EnqueueOutcome(raced, false);
        }
        return new EnqueueOutcome(stored, true);
    }

    @Override
    public List<StoredJob> findRecent(String status, int limit) {
        return jobByIdentity.values().stream()
            .filter(job -> status == null || status.equals(job.status()))
            .sorted(Comparator.comparing(StoredJob::createdAt).reversed()
                .thenComparing(StoredJob::jobId, Comparator.reverseOrder()))
            .limit(Math.max(0, limit))
            .toList();
    }

    @Override
    public List<StoredJob> findUnresolvedForRequeue(String trackScope, String userId, String lastError, int limit) {
        return jobByIdentity.values().stream()
            .filter(job -> "unresolved".equals(job.status()))
            .filter(job -> trackScope == null || trackScope.equals(job.trackScope()))
            .filter(job -> userId == null || userId.equals(job.userId()))
            .filter(job -> lastError == null || lastError.equals(job.lastError()))
            .sorted(Comparator.comparing(StoredJob::updatedAt).reversed()
                .thenComparing(StoredJob::jobId, Comparator.reverseOrder()))
            .limit(Math.max(0, limit))
            .toList();
    }

    @Override
    public List<StoredJob> claimQueued(String workerId, int limit, Instant now) {
        if (limit <= 0) {
            return List.of();
        }
        return jobByIdentity.values().stream()
            .filter(job -> "queued".equals(job.status())
                || ("retry_wait".equals(job.status()) && job.nextRetryAt() != null && !job.nextRetryAt().isAfter(now)))
            .sorted(Comparator.comparing(StoredJob::priority).reversed()
                .thenComparing(StoredJob::createdAt)
                .thenComparing(StoredJob::jobId))
            .limit(limit)
            .map(job -> replace(job, copy(job, "running", job.attemptCount() + 1, null, now, workerId, null, now)))
            .toList();
    }

    @Override
    public void markCompleted(Long jobId, Instant now) {
        update(jobId, job -> copy(job, "completed", job.attemptCount(), null, null, null, null, now));
    }

    @Override
    public void markRetryWait(Long jobId, String lastError, Instant nextRetryAt, Instant now) {
        update(jobId, job -> copy(job, "retry_wait", job.attemptCount(), nextRetryAt, null, null, lastError, now));
    }

    @Override
    public void markUnresolved(Long jobId, String lastError, Instant now) {
        update(jobId, job -> copy(job, "unresolved", job.attemptCount(), null, null, null, lastError, now));
    }

    @Override
    public void markFailed(Long jobId, String lastError, Instant now) {
        update(jobId, job -> copy(job, "failed", job.attemptCount(), null, null, null, lastError, now));
    }

    private String identity(String trackScope, String trackId, String requestedReason) {
        return trackScope + "\n" + trackId + "\n" + requestedReason;
    }

    private StoredJob replace(StoredJob original, StoredJob replacement) {
        jobByIdentity.replace(identity(original.trackScope(), original.trackId(), original.requestedReason()), original, replacement);
        return jobByIdentity.get(identity(original.trackScope(), original.trackId(), original.requestedReason()));
    }

    private void update(Long jobId, java.util.function.Function<StoredJob, StoredJob> updater) {
        jobByIdentity.forEach((identity, job) -> {
            if (job.jobId().equals(jobId)) {
                jobByIdentity.replace(identity, job, updater.apply(job));
            }
        });
    }

    private StoredJob copy(
        StoredJob job,
        String status,
        int attemptCount,
        Instant nextRetryAt,
        Instant lockedAt,
        String lockedBy,
        String lastError,
        Instant updatedAt
    ) {
        return new StoredJob(
            job.jobId(),
            job.trackScope(),
            job.trackId(),
            job.userId(),
            job.priority(),
            status,
            job.requestedReason(),
            attemptCount,
            nextRetryAt,
            lockedAt,
            lockedBy,
            lastError,
            job.createdAt(),
            updatedAt
        );
    }
}
