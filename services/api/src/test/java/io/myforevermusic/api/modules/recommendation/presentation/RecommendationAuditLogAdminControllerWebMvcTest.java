package io.myforevermusic.api.modules.recommendation.presentation;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.myforevermusic.api.modules.recommendation.application.RecommendationAuditLogAdminService;
import io.myforevermusic.api.modules.recommendation.application.RecommendationAuditLogStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RecommendationAuditLogAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class RecommendationAuditLogAdminControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RecommendationAuditLogAdminService adminService;

    @Test
    void shouldReturnRecentAuditLogEntries() throws Exception {
        when(adminService.listRecent(eq("admin-user"), eq("target-user"), eq(25))).thenReturn(List.of(
            new RecommendationAuditLogStore.StoredAuditLog(
                1L,
                "target-user",
                "recommendation-001",
                "request-001",
                RecommendationAuditLogStore.EVENT_PREVIEW_GENERATED,
                "gms",
                "gms-baseline-v1+sasrec:sasrec-v1",
                "recommendation-sequence-v1",
                "sha256:test",
                10,
                true,
                null,
                null,
                null,
                "playlist-001",
                "{\"dry_run_count\":1}",
                Instant.parse("2026-05-14T00:00:00Z")
            )
        ));

        mockMvc.perform(get("/api/v1/recommendations/admin/audit-log/recent")
                .param("user_id", "admin-user")
                .param("target_user_id", "target-user")
                .param("limit", "25"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("api"))
            .andExpect(jsonPath("$.entries[0].audit_log_id").value(1))
            .andExpect(jsonPath("$.entries[0].event_type").value("preview_generated"))
            .andExpect(jsonPath("$.entries[0].model_version").value("gms-baseline-v1+sasrec:sasrec-v1"))
            .andExpect(jsonPath("$.entries[0].dataset_fingerprint").value("sha256:test"))
            .andExpect(jsonPath("$.entries[0].sasrec_applied").value(true))
            .andExpect(jsonPath("$.entries[0].taste_mode_gate_summary").value("{\"dry_run_count\":1}"));
    }

    @Test
    void shouldReturnTasteModeRolloutSummary() throws Exception {
        when(adminService.summarizeTasteModeRollout(eq("admin-user"), eq("target-user"), eq(25))).thenReturn(
            new RecommendationAuditLogAdminService.TasteModeSummary(
                25,
                10,
                1,
                4,
                100,
                70,
                70,
                30,
                0,
                40,
                12,
                0.0129d,
                0.0d,
                java.util.Map.of("eligible", 70L, "low_mode_similarity", 30L),
                new RecommendationAuditLogAdminService.TasteModeLatestSummary(
                    1L,
                    "rule-based-preview-v1+audio-taste:v1+taste-mode-affinity:v1",
                    Instant.parse("2026-05-22T00:00:00Z"),
                    "{\"boost_applied_count\":7}"
                ),
                "boost_active"
            )
        );

        mockMvc.perform(get("/api/v1/recommendations/admin/audit-log/taste-mode-summary")
                .param("user_id", "admin-user")
                .param("target_user_id", "target-user")
                .param("limit", "25"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("api"))
            .andExpect(jsonPath("$.summary.entries_analyzed").value(25))
            .andExpect(jsonPath("$.summary.boost_applied_total").value(40))
            .andExpect(jsonPath("$.summary.rank_changed_total").value(12))
            .andExpect(jsonPath("$.summary.reason_counts.eligible").value(70))
            .andExpect(jsonPath("$.summary.recommendation").value("boost_active"));
    }
}
