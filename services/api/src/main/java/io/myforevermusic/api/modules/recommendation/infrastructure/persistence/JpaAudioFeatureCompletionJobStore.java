package io.myforevermusic.api.modules.recommendation.infrastructure.persistence;

import io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionJobStore;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!local")
public class JpaAudioFeatureCompletionJobStore implements AudioFeatureCompletionJobStore {

    private final AudioFeatureCompletionJobRepository repository;

    public JpaAudioFeatureCompletionJobStore(AudioFeatureCompletionJobRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public EnqueueOutcome enqueueIfAbsent(Draft draft) {
        return repository.findByTrackScopeAndTrackIdAndRequestedReason(
                draft.trackScope(),
                draft.trackId(),
                draft.requestedReason()
            )
            .map(existing -> new EnqueueOutcome(existing.toState(), false))
            .orElseGet(() -> saveNewJob(draft));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredJob> findRecent(String status, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return repository.findRecent(status, Pageable.ofSize(Math.max(0, limit))).stream()
            .map(AudioFeatureCompletionJobEntity::toState)
            .toList();
    }

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

    @Override
    @Transactional
    public List<StoredJob> claimQueued(String workerId, int limit, Instant now) {
        if (limit <= 0) {
            return List.of();
        }
        List<AudioFeatureCompletionJobEntity> jobs = repository.findClaimable(now, Pageable.ofSize(limit));
        jobs.forEach(job -> job.claim(workerId, now));
        return repository.saveAll(jobs).stream()
            .map(AudioFeatureCompletionJobEntity::toState)
            .toList();
    }

    @Override
    @Transactional
    public void markCompleted(Long jobId, Instant now) {
        repository.findById(jobId).ifPresent(job -> job.markCompleted(now));
    }

    @Override
    @Transactional
    public void markRetryWait(Long jobId, String lastError, Instant nextRetryAt, Instant now) {
        repository.findById(jobId).ifPresent(job -> job.markRetryWait(lastError, nextRetryAt, now));
    }

    @Override
    @Transactional
    public void markUnresolved(Long jobId, String lastError, Instant now) {
        repository.findById(jobId).ifPresent(job -> job.markUnresolved(lastError, now));
    }

    @Override
    @Transactional
    public void markFailed(Long jobId, String lastError, Instant now) {
        repository.findById(jobId).ifPresent(job -> job.markFailed(lastError, now));
    }

    private EnqueueOutcome saveNewJob(Draft draft) {
        try {
            return new EnqueueOutcome(repository.save(new AudioFeatureCompletionJobEntity(draft)).toState(), true);
        } catch (DataIntegrityViolationException ignored) {
            return repository.findByTrackScopeAndTrackIdAndRequestedReason(
                    draft.trackScope(),
                    draft.trackId(),
                    draft.requestedReason()
                )
                .map(existing -> new EnqueueOutcome(existing.toState(), false))
                .orElseThrow(() -> ignored);
        }
    }
}
