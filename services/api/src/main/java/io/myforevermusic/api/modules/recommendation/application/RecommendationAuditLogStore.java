package io.myforevermusic.api.modules.recommendation.application;

import java.time.Instant;
import java.util.List;

public interface RecommendationAuditLogStore {

    String EVENT_PREVIEW_GENERATED = "preview_generated";
    String EVENT_FEEDBACK_RECORDED = "feedback_recorded";

    StoredAuditLog save(AuditDraft draft);

    List<StoredAuditLog> findRecentByUserId(String userId, int limit);

    record AuditDraft(
        String userId,
        String recommendationId,
        String requestId,
        String eventType,
        String sourceSpace,
        String modelVersion,
        String datasetVersion,
        String datasetFingerprint,
        Integer itemCount,
        Boolean sasrecApplied,
        String fallbackReason,
        String feedbackType,
        String targetTrackId,
        String targetPlaylistId,
        String tasteModeGateSummary,
        Instant createdAt
    ) {}

    record StoredAuditLog(
        Long auditLogId,
        String userId,
        String recommendationId,
        String requestId,
        String eventType,
        String sourceSpace,
        String modelVersion,
        String datasetVersion,
        String datasetFingerprint,
        Integer itemCount,
        Boolean sasrecApplied,
        String fallbackReason,
        String feedbackType,
        String targetTrackId,
        String targetPlaylistId,
        String tasteModeGateSummary,
        Instant createdAt
    ) {}
}
