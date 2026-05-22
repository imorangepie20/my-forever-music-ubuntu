package io.myforevermusic.api.modules.recommendation.presentation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.modules.recommendation.application.RecommendationAuditLogAdminService;
import io.myforevermusic.api.modules.recommendation.application.RecommendationAuditLogStore;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recommendations/admin/audit-log")
public class RecommendationAuditLogAdminController {

    private final RecommendationAuditLogAdminService adminService;

    public RecommendationAuditLogAdminController(RecommendationAuditLogAdminService adminService) {
        this.adminService = adminService;
    }

    @Operation(summary = "List recent recommendation audit log entries for admin debugging")
    @GetMapping("/recent")
    public RecommendationAuditLogRecentResponse listRecent(
        @RequestParam("user_id") String userId,
        @RequestParam(value = "target_user_id", required = false) String targetUserId,
        @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        List<RecommendationAuditLogStore.StoredAuditLog> entries = adminService.listRecent(
            userId,
            targetUserId,
            limit
        );
        return new RecommendationAuditLogRecentResponse(
            "api",
            "ok",
            Instant.now(),
            entries.stream().map(RecommendationAuditLogItem::from).toList()
        );
    }

    @Operation(summary = "Summarize taste-mode rollout audit log entries for admin debugging")
    @GetMapping("/taste-mode-summary")
    public RecommendationTasteModeSummaryResponse summarizeTasteModeRollout(
        @RequestParam("user_id") String userId,
        @RequestParam(value = "target_user_id", required = false) String targetUserId,
        @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        RecommendationAuditLogAdminService.TasteModeSummary summary = adminService.summarizeTasteModeRollout(
            userId,
            targetUserId,
            limit
        );
        return new RecommendationTasteModeSummaryResponse(
            "api",
            "ok",
            Instant.now(),
            RecommendationTasteModeSummaryItem.from(summary)
        );
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RecommendationAuditLogRecentResponse(
        String service,
        String status,
        Instant generatedAt,
        List<RecommendationAuditLogItem> entries
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RecommendationTasteModeSummaryResponse(
        String service,
        String status,
        Instant generatedAt,
        RecommendationTasteModeSummaryItem summary
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RecommendationTasteModeSummaryItem(
        int entriesAnalyzed,
        int entriesWithSummary,
        int parseErrorCount,
        int boostEnabledCount,
        int evaluatedTotal,
        int eligibleTotal,
        int dryRunTotal,
        int blockedTotal,
        int notApplicableTotal,
        int boostAppliedTotal,
        int rankChangedTotal,
        Double maxPositiveDelta,
        Double maxNegativeDelta,
        Map<String, Long> reasonCounts,
        RecommendationTasteModeLatestSummaryItem latestSummary,
        String recommendation
    ) {
        static RecommendationTasteModeSummaryItem from(RecommendationAuditLogAdminService.TasteModeSummary summary) {
            return new RecommendationTasteModeSummaryItem(
                summary.entriesAnalyzed(),
                summary.entriesWithSummary(),
                summary.parseErrorCount(),
                summary.boostEnabledCount(),
                summary.evaluatedTotal(),
                summary.eligibleTotal(),
                summary.dryRunTotal(),
                summary.blockedTotal(),
                summary.notApplicableTotal(),
                summary.boostAppliedTotal(),
                summary.rankChangedTotal(),
                summary.maxPositiveDelta(),
                summary.maxNegativeDelta(),
                summary.reasonCounts(),
                RecommendationTasteModeLatestSummaryItem.from(summary.latestSummary()),
                summary.recommendation()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RecommendationTasteModeLatestSummaryItem(
        Long auditLogId,
        String modelVersion,
        Instant createdAt,
        String tasteModeGateSummary
    ) {
        static RecommendationTasteModeLatestSummaryItem from(
            RecommendationAuditLogAdminService.TasteModeLatestSummary latestSummary
        ) {
            if (latestSummary == null) {
                return null;
            }
            return new RecommendationTasteModeLatestSummaryItem(
                latestSummary.auditLogId(),
                latestSummary.modelVersion(),
                latestSummary.createdAt(),
                latestSummary.tasteModeGateSummary()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RecommendationAuditLogItem(
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
    ) {
        static RecommendationAuditLogItem from(RecommendationAuditLogStore.StoredAuditLog entry) {
            return new RecommendationAuditLogItem(
                entry.auditLogId(),
                entry.userId(),
                entry.recommendationId(),
                entry.requestId(),
                entry.eventType(),
                entry.sourceSpace(),
                entry.modelVersion(),
                entry.datasetVersion(),
                entry.datasetFingerprint(),
                entry.itemCount(),
                entry.sasrecApplied(),
                entry.fallbackReason(),
                entry.feedbackType(),
                entry.targetTrackId(),
                entry.targetPlaylistId(),
                entry.tasteModeGateSummary(),
                entry.createdAt()
            );
        }
    }
}
