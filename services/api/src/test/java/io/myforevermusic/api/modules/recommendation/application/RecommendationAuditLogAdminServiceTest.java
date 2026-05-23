package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import io.myforevermusic.api.modules.auth.application.AuthRegisteredAccount;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RecommendationAuditLogAdminServiceTest {

    @Test
    void shouldSummarizeTasteModeGateAuditLogs() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        RecommendationAuditLogStore auditLogStore = mock(RecommendationAuditLogStore.class);
        when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(account("admin-user", "jowoosungtidal@gmail.com")));
        when(auditLogStore.findRecentByUserId("target-user", 50)).thenReturn(List.of(
            audit(1L, "{\"apply_ranking_boost\":true,\"evaluated_count\":10,\"eligible_count\":7,\"dry_run_count\":7,\"blocked_count\":3,\"not_applicable_count\":0,\"max_positive_delta\":0.0129,\"max_negative_delta\":0.0,\"boost_applied_count\":7,\"rank_changed_count\":8,\"reason_counts\":{\"eligible\":7,\"low_mode_similarity\":3}}"),
            audit(2L, "{\"apply_ranking_boost\":false,\"evaluated_count\":10,\"eligible_count\":0,\"dry_run_count\":0,\"blocked_count\":10,\"not_applicable_count\":0,\"max_positive_delta\":0.0,\"max_negative_delta\":0.0,\"boost_applied_count\":0,\"rank_changed_count\":0,\"reason_counts\":{\"low_profile_confidence\":10}}"),
            audit(3L, "{bad-json")
        ));
        RecommendationAuditLogAdminService service = new RecommendationAuditLogAdminService(
            authAccountStore,
            auditLogStore,
            new ObjectMapper()
        );

        RecommendationAuditLogAdminService.TasteModeSummary summary = service.summarizeTasteModeRollout(
            "admin-user",
            "target-user",
            50
        );

        assertThat(summary.entriesAnalyzed()).isEqualTo(3);
        assertThat(summary.entriesWithSummary()).isEqualTo(2);
        assertThat(summary.parseErrorCount()).isEqualTo(1);
        assertThat(summary.boostEnabledCount()).isEqualTo(1);
        assertThat(summary.evaluatedTotal()).isEqualTo(20);
        assertThat(summary.eligibleTotal()).isEqualTo(7);
        assertThat(summary.blockedTotal()).isEqualTo(13);
        assertThat(summary.boostAppliedTotal()).isEqualTo(7);
        assertThat(summary.rankChangedTotal()).isEqualTo(8);
        assertThat(summary.maxPositiveDelta()).isEqualTo(0.0129d);
        assertThat(summary.reasonCounts()).containsEntry("eligible", 7L);
        assertThat(summary.reasonCounts()).containsEntry("low_profile_confidence", 10L);
        assertThat(summary.recommendation()).isEqualTo("boost_active");
        assertThat(summary.latestSummary()).isNotNull();
    }

    private RecommendationAuditLogStore.StoredAuditLog audit(Long id, String tasteModeGateSummary) {
        return new RecommendationAuditLogStore.StoredAuditLog(
            id,
            "target-user",
            "recommendation-%d".formatted(id),
            "request-%d".formatted(id),
            RecommendationAuditLogStore.EVENT_PREVIEW_GENERATED,
            "gms",
            "rule-based-preview-v1",
            null,
            null,
            10,
            false,
            null,
            null,
            null,
            null,
            tasteModeGateSummary,
            null,
            Instant.parse("2026-05-22T00:00:00Z").plusSeconds(id)
        );
    }

    private AuthRegisteredAccount account(String userId, String normalizedEmail) {
        return new AuthRegisteredAccount(
            userId,
            normalizedEmail,
            normalizedEmail,
            "Admin",
            "tidal",
            null,
            null,
            false,
            "complete",
            Instant.parse("2026-05-22T00:00:00Z"),
            Instant.parse("2026-05-22T00:00:00Z"),
            Instant.parse("2026-05-22T00:00:00Z")
        );
    }
}
