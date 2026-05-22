package io.myforevermusic.api.modules.recommendation.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecommendationAuditLogAdminService {

    private static final String ADMIN_EMAIL = "jowoosungtidal@gmail.com";

    private final AuthAccountStore authAccountStore;
    private final RecommendationAuditLogStore auditLogStore;
    private final ObjectMapper objectMapper;

    public RecommendationAuditLogAdminService(
        AuthAccountStore authAccountStore,
        RecommendationAuditLogStore auditLogStore,
        ObjectMapper objectMapper
    ) {
        this.authAccountStore = authAccountStore;
        this.auditLogStore = auditLogStore;
        this.objectMapper = objectMapper;
    }

    public List<RecommendationAuditLogStore.StoredAuditLog> listRecent(
        String adminUserId,
        String targetUserId,
        int limit
    ) {
        assertAdmin(adminUserId);
        return auditLogStore.findRecentByUserId(resolveTargetUserId(adminUserId, targetUserId), safeLimit(limit));
    }

    public TasteModeSummary summarizeTasteModeRollout(
        String adminUserId,
        String targetUserId,
        int limit
    ) {
        assertAdmin(adminUserId);
        List<RecommendationAuditLogStore.StoredAuditLog> entries = auditLogStore.findRecentByUserId(
            resolveTargetUserId(adminUserId, targetUserId),
            safeLimit(limit)
        );

        int entriesWithSummary = 0;
        int parseErrorCount = 0;
        int boostEnabledCount = 0;
        int evaluatedTotal = 0;
        int eligibleTotal = 0;
        int dryRunTotal = 0;
        int blockedTotal = 0;
        int notApplicableTotal = 0;
        int boostAppliedTotal = 0;
        int rankChangedTotal = 0;
        Double maxPositiveDelta = null;
        Double maxNegativeDelta = null;
        Map<String, Long> reasonCounts = new LinkedHashMap<>();
        TasteModeLatestSummary latestSummary = null;

        for (RecommendationAuditLogStore.StoredAuditLog entry : entries) {
            String rawSummary = entry.tasteModeGateSummary();
            if (rawSummary == null || rawSummary.isBlank()) {
                continue;
            }

            Map<String, Object> summary;
            try {
                summary = objectMapper.readValue(rawSummary, new TypeReference<>() {});
            } catch (Exception ignored) {
                parseErrorCount++;
                continue;
            }

            entriesWithSummary++;
            if (latestSummary == null) {
                latestSummary = new TasteModeLatestSummary(
                    entry.auditLogId(),
                    entry.modelVersion(),
                    entry.createdAt(),
                    rawSummary
                );
            }

            if (booleanValue(summary, "apply_ranking_boost")) {
                boostEnabledCount++;
            }
            evaluatedTotal += intValue(summary, "evaluated_count");
            eligibleTotal += intValue(summary, "eligible_count");
            dryRunTotal += intValue(summary, "dry_run_count");
            blockedTotal += intValue(summary, "blocked_count");
            notApplicableTotal += intValue(summary, "not_applicable_count");
            boostAppliedTotal += intValue(summary, "boost_applied_count");
            rankChangedTotal += intValue(summary, "rank_changed_count");
            maxPositiveDelta = maxDouble(maxPositiveDelta, doubleValue(summary, "max_positive_delta"));
            maxNegativeDelta = minDouble(maxNegativeDelta, doubleValue(summary, "max_negative_delta"));
            mergeReasonCounts(reasonCounts, summary.get("reason_counts"));
        }

        return new TasteModeSummary(
            entries.size(),
            entriesWithSummary,
            parseErrorCount,
            boostEnabledCount,
            evaluatedTotal,
            eligibleTotal,
            dryRunTotal,
            blockedTotal,
            notApplicableTotal,
            boostAppliedTotal,
            rankChangedTotal,
            maxPositiveDelta,
            maxNegativeDelta,
            reasonCounts,
            latestSummary,
            recommendationFor(entriesWithSummary, boostAppliedTotal, rankChangedTotal, eligibleTotal, dryRunTotal, reasonCounts)
        );
    }

    private void assertAdmin(String userId) {
        String normalizedEmail = authAccountStore.findByUserId(userId)
            .map(account -> account.normalizedEmail())
            .orElse("");
        if (!ADMIN_EMAIL.equals(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Recommendation audit log admin access is restricted.");
        }
    }

    private String resolveTargetUserId(String adminUserId, String targetUserId) {
        return targetUserId == null || targetUserId.isBlank() ? adminUserId : targetUserId.trim();
    }

    private int safeLimit(int limit) {
        return Math.min(200, Math.max(1, limit));
    }

    private boolean booleanValue(Map<String, Object> summary, String key) {
        Object value = summary.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        return false;
    }

    private int intValue(Map<String, Object> summary, String key) {
        Object value = summary.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private Double doubleValue(Map<String, Object> summary, String key) {
        Object value = summary.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Double.parseDouble(stringValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Double maxDouble(Double current, Double candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null ? candidate : Math.max(current, candidate);
    }

    private Double minDouble(Double current, Double candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null ? candidate : Math.min(current, candidate);
    }

    private void mergeReasonCounts(Map<String, Long> totals, Object rawReasonCounts) {
        if (!(rawReasonCounts instanceof Map<?, ?> reasonCounts)) {
            return;
        }
        for (Map.Entry<?, ?> entry : reasonCounts.entrySet()) {
            String reason = String.valueOf(entry.getKey());
            Long count = longValue(entry.getValue());
            if (!reason.isBlank() && count != null) {
                totals.merge(reason, count, Long::sum);
            }
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Long.parseLong(stringValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String recommendationFor(
        int entriesWithSummary,
        int boostAppliedTotal,
        int rankChangedTotal,
        int eligibleTotal,
        int dryRunTotal,
        Map<String, Long> reasonCounts
    ) {
        if (boostAppliedTotal > 0 || rankChangedTotal > 0) {
            return "boost_active";
        }
        if (entriesWithSummary == 0) {
            return "no_taste_mode_data";
        }
        if (eligibleTotal > 0 || dryRunTotal > 0) {
            return "dry_run_only";
        }
        if (reasonCounts.getOrDefault("low_profile_confidence", 0L) > 0) {
            return "blocked_by_confidence";
        }
        return "blocked_by_gate";
    }

    public record TasteModeSummary(
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
        TasteModeLatestSummary latestSummary,
        String recommendation
    ) {
        public TasteModeSummary {
            reasonCounts = reasonCounts == null ? Map.of() : Map.copyOf(reasonCounts);
        }
    }

    public record TasteModeLatestSummary(
        Long auditLogId,
        String modelVersion,
        Instant createdAt,
        String tasteModeGateSummary
    ) {}
}
