package io.myforevermusic.api.modules.recommendation.infrastructure.persistence;

import io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionJobStore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "audio_feature_completion_job")
public class AudioFeatureCompletionJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audio_feature_completion_job_id")
    private Long jobId;

    @Column(name = "track_scope", nullable = false, length = 80)
    private String trackScope;

    @Column(name = "track_id", nullable = false, length = 200)
    private String trackId;

    @Column(name = "user_id", length = 100)
    private String userId;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "requested_reason", nullable = false, length = 80)
    private String requestedReason;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "locked_by", length = 120)
    private String lockedBy;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AudioFeatureCompletionJobEntity() {
    }

    public AudioFeatureCompletionJobEntity(AudioFeatureCompletionJobStore.Draft draft) {
        this.trackScope = draft.trackScope();
        this.trackId = draft.trackId();
        this.userId = draft.userId();
        this.priority = draft.priority();
        this.status = "queued";
        this.requestedReason = draft.requestedReason();
        this.attemptCount = 0;
        this.createdAt = draft.createdAt();
        this.updatedAt = draft.createdAt();
    }

    public void claim(String workerId, Instant now) {
        this.status = "running";
        this.attemptCount += 1;
        this.lockedAt = now;
        this.lockedBy = workerId;
        this.lastError = null;
        this.updatedAt = now;
    }

    public void markCompleted(Instant now) {
        this.status = "completed";
        this.nextRetryAt = null;
        this.lockedAt = null;
        this.lockedBy = null;
        this.lastError = null;
        this.updatedAt = now;
    }

    public void markRetryWait(String lastError, Instant nextRetryAt, Instant now) {
        this.status = "retry_wait";
        this.nextRetryAt = nextRetryAt;
        this.lockedAt = null;
        this.lockedBy = null;
        this.lastError = lastError;
        this.updatedAt = now;
    }

    public void markUnresolved(String lastError, Instant now) {
        this.status = "unresolved";
        this.nextRetryAt = null;
        this.lockedAt = null;
        this.lockedBy = null;
        this.lastError = lastError;
        this.updatedAt = now;
    }

    public void markFailed(String lastError, Instant now) {
        this.status = "failed";
        this.nextRetryAt = null;
        this.lockedAt = null;
        this.lockedBy = null;
        this.lastError = lastError;
        this.updatedAt = now;
    }

    public AudioFeatureCompletionJobStore.StoredJob toState() {
        return new AudioFeatureCompletionJobStore.StoredJob(
            jobId,
            trackScope,
            trackId,
            userId,
            priority,
            status,
            requestedReason,
            attemptCount,
            nextRetryAt,
            lockedAt,
            lockedBy,
            lastError,
            createdAt,
            updatedAt
        );
    }
}
