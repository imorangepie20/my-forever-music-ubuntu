package io.myforevermusic.api.modules.recommendation.infrastructure.local;

import io.myforevermusic.api.modules.recommendation.application.RecommendationAuditLogStore;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class InMemoryRecommendationAuditLogStore implements RecommendationAuditLogStore {

    private final AtomicLong sequence = new AtomicLong(1);
    private final Map<Long, StoredAuditLog> auditLogById = new ConcurrentHashMap<>();

    @Override
    public StoredAuditLog save(AuditDraft draft) {
        long auditLogId = sequence.getAndIncrement();
        StoredAuditLog stored = new StoredAuditLog(
            auditLogId,
            draft.userId(),
            draft.recommendationId(),
            draft.requestId(),
            draft.eventType(),
            draft.sourceSpace(),
            draft.modelVersion(),
            draft.datasetVersion(),
            draft.datasetFingerprint(),
            draft.itemCount(),
            draft.sasrecApplied(),
            draft.fallbackReason(),
            draft.feedbackType(),
            draft.targetTrackId(),
            draft.targetPlaylistId(),
            draft.tasteModeGateSummary(),
            draft.createdAt()
        );
        auditLogById.put(auditLogId, stored);
        return stored;
    }

    @Override
    public List<StoredAuditLog> findRecentByUserId(String userId, int limit) {
        return auditLogById.values().stream()
            .filter(entry -> entry.userId().equals(userId))
            .sorted(Comparator.comparing(StoredAuditLog::createdAt).reversed()
                .thenComparing(StoredAuditLog::auditLogId, Comparator.reverseOrder()))
            .limit(Math.max(0, limit))
            .toList();
    }
}
