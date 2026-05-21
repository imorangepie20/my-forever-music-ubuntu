package io.myforevermusic.api.modules.recommendation.infrastructure.persistence;

import io.myforevermusic.api.modules.recommendation.application.RecommendationAuditLogStore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "recommendation_audit_log")
public class RecommendationAuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recommendation_audit_log_id")
    private Long auditLogId;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "recommendation_id", length = 160)
    private String recommendationId;

    @Column(name = "request_id", length = 160)
    private String requestId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "source_space", length = 50)
    private String sourceSpace;

    @Column(name = "model_version", length = 160)
    private String modelVersion;

    @Column(name = "dataset_version", length = 120)
    private String datasetVersion;

    @Column(name = "dataset_fingerprint", length = 160)
    private String datasetFingerprint;

    @Column(name = "item_count")
    private Integer itemCount;

    @Column(name = "sasrec_applied")
    private Boolean sasrecApplied;

    @Column(name = "fallback_reason", length = 500)
    private String fallbackReason;

    @Column(name = "feedback_type", length = 30)
    private String feedbackType;

    @Column(name = "target_track_id", length = 200)
    private String targetTrackId;

    @Column(name = "target_playlist_id", length = 200)
    private String targetPlaylistId;

    @Column(name = "taste_mode_gate_summary", columnDefinition = "TEXT")
    private String tasteModeGateSummary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RecommendationAuditLogEntity() {
    }

    public RecommendationAuditLogEntity(RecommendationAuditLogStore.AuditDraft draft) {
        this.userId = draft.userId();
        this.recommendationId = draft.recommendationId();
        this.requestId = draft.requestId();
        this.eventType = draft.eventType();
        this.sourceSpace = draft.sourceSpace();
        this.modelVersion = draft.modelVersion();
        this.datasetVersion = draft.datasetVersion();
        this.datasetFingerprint = draft.datasetFingerprint();
        this.itemCount = draft.itemCount();
        this.sasrecApplied = draft.sasrecApplied();
        this.fallbackReason = draft.fallbackReason();
        this.feedbackType = draft.feedbackType();
        this.targetTrackId = draft.targetTrackId();
        this.targetPlaylistId = draft.targetPlaylistId();
        this.tasteModeGateSummary = draft.tasteModeGateSummary();
        this.createdAt = draft.createdAt();
    }

    public RecommendationAuditLogStore.StoredAuditLog toState() {
        return new RecommendationAuditLogStore.StoredAuditLog(
            auditLogId,
            userId,
            recommendationId,
            requestId,
            eventType,
            sourceSpace,
            modelVersion,
            datasetVersion,
            datasetFingerprint,
            itemCount,
            sasrecApplied,
            fallbackReason,
            feedbackType,
            targetTrackId,
            targetPlaylistId,
            tasteModeGateSummary,
            createdAt
        );
    }
}
