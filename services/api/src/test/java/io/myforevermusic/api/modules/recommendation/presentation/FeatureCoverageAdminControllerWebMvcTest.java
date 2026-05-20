package io.myforevermusic.api.modules.recommendation.presentation;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.myforevermusic.api.modules.recommendation.application.FeatureCoverageAdminService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FeatureCoverageAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class FeatureCoverageAdminControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FeatureCoverageAdminService adminService;

    @Test
    void shouldReturnFeatureCoverageWithSnakeCaseFields() throws Exception {
        when(adminService.summarize(eq("admin-user"), eq("target-user"))).thenReturn(sampleReport());

        mockMvc.perform(get("/api/v1/recommendations/admin/feature-coverage")
                .param("user_id", "admin-user")
                .param("target_user_id", "target-user"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("api"))
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.target_user_id").value("target-user"))
            .andExpect(jsonPath("$.pms_library.playlist_count").value(2))
            .andExpect(jsonPath("$.pms_library.audio_feature_coverage_ratio").value(0.75))
            .andExpect(jsonPath("$.pms_library.audio_feature_source_classes[0].source_class").value("provider_lookup"))
            .andExpect(jsonPath("$.pms_library.audio_feature_source_classes[0].track_count").value(9))
            .andExpect(jsonPath("$.pms_library.stale_audio_feature_ratio").value(0.1111))
            .andExpect(jsonPath("$.ems_pool.audio_feature_source_classes[0].source_class").value("provider_lookup"))
            .andExpect(jsonPath("$.ems_pool.audio_feature_source_classes[1].source_class").value("unresolved"))
            .andExpect(jsonPath("$.ems_pool.sources[0].source_platform").value("spotify"))
            .andExpect(jsonPath("$.ems_pool.sources[0].latest_audio_resolved_at").value("2026-05-14T00:00:00Z"))
            .andExpect(jsonPath("$.ems_pool.sources[0].canonical_track_coverage_ratio").value(0.6))
            .andExpect(jsonPath("$.ems_acquisition.skipped_item_ratio").value(0.2))
            .andExpect(jsonPath("$.audio_feature_completion.recent_job_count").value(4))
            .andExpect(jsonPath("$.audio_feature_completion.status_counts[0].status").value("queued"))
            .andExpect(jsonPath("$.audio_feature_completion.status_counts[0].job_count").value(2))
            .andExpect(jsonPath("$.audio_feature_completion.top_reasons[0].reason").value("lastfm_tag_inferred_partial_audio_features"))
            .andExpect(jsonPath("$.learning_data.event_count").value(21))
            .andExpect(jsonPath("$.learning_data.recent_recommendation_snapshot_limit").value(1000));
    }

    private FeatureCoverageAdminService.FeatureCoverageReport sampleReport() {
        return new FeatureCoverageAdminService.FeatureCoverageReport(
            "target-user",
            Instant.parse("2026-05-14T00:00:00Z"),
            "ok",
            new FeatureCoverageAdminService.PmsLibraryCoverage(
                2,
                12L,
                9L,
                0.75d,
                1L,
                0.1111d,
                Instant.parse("2026-05-14T00:00:00Z"),
                List.of(new FeatureCoverageAdminService.AudioFeatureSourceClassCoverage(
                    "provider_lookup",
                    9L,
                    9L,
                    1.0d,
                    1L,
                    0.1111d,
                    Instant.parse("2026-05-14T00:00:00Z")
                )),
                10L,
                0.8333d,
                11L,
                0.9167d
            ),
            new FeatureCoverageAdminService.EmsPoolCoverage(
                20L,
                16L,
                0.8d,
                2L,
                0.125d,
                Instant.parse("2026-05-14T00:00:00Z"),
                List.of(
                    new FeatureCoverageAdminService.AudioFeatureSourceClassCoverage(
                        "provider_lookup",
                        16L,
                        16L,
                        1.0d,
                        2L,
                        0.125d,
                        Instant.parse("2026-05-14T00:00:00Z")
                    ),
                    new FeatureCoverageAdminService.AudioFeatureSourceClassCoverage(
                        "unresolved",
                        4L,
                        0L,
                        0.0d,
                        0L,
                        0.0d,
                        null
                    )
                ),
                18L,
                0.9d,
                12L,
                0.6d,
                List.of(new FeatureCoverageAdminService.EmsSourceCoverage(
                    "spotify",
                    20L,
                    16L,
                    0.8d,
                    2L,
                    0.125d,
                    Instant.parse("2026-05-14T00:00:00Z"),
                    18L,
                    0.9d,
                    12L,
                    0.6d
                )),
                List.of()
            ),
            new FeatureCoverageAdminService.EmsAcquisitionCoverage(
                2L,
                20L,
                3L,
                5L,
                2L,
                27L,
                5L,
                0.2d,
                List.of()
            ),
            new FeatureCoverageAdminService.AudioFeatureCompletionCoverage(
                4L,
                List.of(
                    new FeatureCoverageAdminService.AudioFeatureCompletionStatusCount("queued", 2L),
                    new FeatureCoverageAdminService.AudioFeatureCompletionStatusCount("unresolved", 2L)
                ),
                List.of(new FeatureCoverageAdminService.AudioFeatureCompletionReasonCount(
                    "lastfm_tag_inferred_partial_audio_features",
                    2L
                )),
                List.of()
            ),
            new FeatureCoverageAdminService.LearningDataCoverage(21L, 5L, 1000),
            List.of(),
            List.of()
        );
    }
}
