package io.myforevermusic.api.modules.system.presentation;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.myforevermusic.api.modules.ems.application.EmsCollectionService;
import io.myforevermusic.api.modules.ems.application.EmsTidalHomeTrackBackfillScheduler;
import io.myforevermusic.api.modules.system.application.SchedulingAdminService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SchedulingAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class SchedulingAdminControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SchedulingAdminService schedulingAdminService;

    @Test
    void shouldReturnSchedulerStatusWithSnakeCaseFields() throws Exception {
        when(schedulingAdminService.summarize(eq("admin-user"))).thenReturn(sampleReport());

        mockMvc.perform(get("/api/v1/system/admin/schedules")
                .param("user_id", "admin-user"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("api"))
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.generated_at").value("2026-05-15T00:00:00Z"))
            .andExpect(jsonPath("$.schedules[0].id").value("ems-acquisition"))
            .andExpect(jsonPath("$.schedules[0].fixed_delay_ms").value(86400000))
            .andExpect(jsonPath("$.schedules[0].cadence_label").value("daily"))
            .andExpect(jsonPath("$.schedules[0].management_path").value("/ems/acquisition-admin"))
            .andExpect(jsonPath("$.schedules[0].last_started_at").value("2026-05-14T00:00:00Z"))
            .andExpect(jsonPath("$.recommendations[0]").value("daily refresh"))
            .andExpect(jsonPath("$.tidal_home_backfill.batch_size").value(10))
            .andExpect(jsonPath("$.tidal_home_backfill.summary.playlist_count").value(3))
            .andExpect(jsonPath("$.tidal_home_backfill.summary.sources[0].source_id").value("POPULAR_PLAYLISTS"));
    }

    private SchedulingAdminService.SchedulingAdminReport sampleReport() {
        return new SchedulingAdminService.SchedulingAdminReport(
            "ok",
            Instant.parse("2026-05-15T00:00:00Z"),
            List.of(new SchedulingAdminService.ScheduledServiceStatus(
                "ems-acquisition",
                "EMS",
                "EMS Acquisition",
                "scheduled",
                true,
                true,
                "active",
                86_400_000L,
                60_000L,
                "daily",
                "Collect editorial signals.",
                "/ems/acquisition-admin",
                "completed",
                "done",
                Instant.parse("2026-05-14T00:00:00Z"),
                Instant.parse("2026-05-14T00:02:00Z"),
                List.of("app.ems.acquisition.refresh-interval-ms"),
                List.of("Default cadence is daily.")
            )),
            List.of("daily refresh"),
            new EmsTidalHomeTrackBackfillScheduler.EmsTidalHomeTrackBackfillStatus(
                true,
                10,
                null,
                new EmsCollectionService.EmsTidalHomeBackfillSummary(
                    3,
                    2,
                    1,
                    80,
                    0.6667,
                    List.of(new EmsCollectionService.EmsTidalHomeSourceBackfillSummary(
                        "POPULAR_PLAYLISTS",
                        3,
                        2,
                        1,
                        80,
                        0.6667
                    ))
                )
            )
        );
    }
}
