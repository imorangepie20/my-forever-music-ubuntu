package io.myforevermusic.api.modules.ems.presentation;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.myforevermusic.api.modules.ems.application.EmsAcquisitionService;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionService.EmsAcquisitionRunCommand;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionService.EmsAcquisitionRunDetailSnapshot;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionService.EmsAcquisitionRunSnapshot;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionService.EmsAcquisitionSeedSnapshot;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionService.EmsAcquisitionSignalSnapshot;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionService.EmsAcquisitionSourcePresetSnapshot;
import io.myforevermusic.api.modules.ems.application.EmsEditorialSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EmsAcquisitionController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class EmsAcquisitionControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmsAcquisitionService acquisitionService;

    @Test
    void shouldRunEditorialAcquisitionManually() throws Exception {
        when(acquisitionService.runNow(org.mockito.ArgumentMatchers.any(EmsAcquisitionRunCommand.class)))
            .thenReturn(new EmsAcquisitionRunDetailSnapshot(
                runSnapshot("completed"),
                List.of(new EmsAcquisitionSignalSnapshot(
                    10L,
                    "Pitchfork",
                    "https://pitchfork.com/feed/",
                    "https://example.test/article",
                    "Best New Tracks",
                    "playlist_query",
                    "best new tracks",
                    0.84d,
                    "Editorial roundup",
                    "ready",
                    Instant.parse("2026-05-14T00:00:01Z")
                )),
                List.of(new EmsAcquisitionSeedSnapshot(
                    20L,
                    10L,
                    "spotify",
                    "best new tracks",
                    "completed",
                    30L,
                    5,
                    5,
                    null,
                    Instant.parse("2026-05-14T00:00:02Z"),
                    Instant.parse("2026-05-14T00:00:03Z")
                ))
            ));

        mockMvc.perform(post("/api/v1/ems/acquisition/run")
                .contentType("application/json")
                .content("""
                    {
                      "user_id": "user-001",
                      "platforms": ["spotify"],
                      "source_preset": "editorial-expanded",
                      "sources": [
                        {
                          "name": "Pitchfork",
                          "type": "rss",
                          "url": "https://pitchfork.com/feed/",
                          "weight": 1.0
                        }
                      ],
                      "max_articles_per_source": 10,
                      "max_signals_per_run": 5,
                      "per_seed_limit": 5
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("completed"))
            .andExpect(jsonPath("$.run.pool_run_count").value(1))
            .andExpect(jsonPath("$.signals[0].query").value("best new tracks"))
            .andExpect(jsonPath("$.seeds[0].pool_run_id").value(30));

        ArgumentCaptor<EmsAcquisitionRunCommand> commandCaptor =
            ArgumentCaptor.forClass(EmsAcquisitionRunCommand.class);
        verify(acquisitionService).runNow(commandCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(commandCaptor.getValue().sources()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(commandCaptor.getValue().sourcePreset()).isEqualTo("editorial-expanded");
    }

    @Test
    void shouldExposeNotRunStatus() throws Exception {
        when(acquisitionService.latestRun()).thenReturn(null);

        mockMvc.perform(get("/api/v1/ems/acquisition/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("not_run"));
    }

    @Test
    void shouldExposeSourcePresets() throws Exception {
        when(acquisitionService.listSourcePresets()).thenReturn(List.of(
            new EmsAcquisitionSourcePresetSnapshot(
                "editorial-expanded",
                "Editorial Expanded",
                "larger source set",
                25,
                120,
                10,
                List.of(new EmsEditorialSource(
                    "Bandcamp Daily",
                    "rss",
                    "https://daily.bandcamp.com/feed",
                    1.1d
                ))
            )
        ));

        mockMvc.perform(get("/api/v1/ems/acquisition/source-presets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.presets[0].id").value("editorial-expanded"))
            .andExpect(jsonPath("$.presets[0].source_count").value(1))
            .andExpect(jsonPath("$.presets[0].max_signals_per_run").value(120))
            .andExpect(jsonPath("$.presets[0].sources[0].name").value("Bandcamp Daily"));
    }

    private EmsAcquisitionRunSnapshot runSnapshot(String status) {
        return new EmsAcquisitionRunSnapshot(
            1L,
            "manual",
            "user-001",
            status,
            1,
            10,
            0,
            1,
            1,
            0,
            1,
            0,
            0,
            "EMS acquisition completed: signals=1 seeds=1 pool_runs=1.",
            null,
            Instant.parse("2026-05-14T00:00:00Z"),
            Instant.parse("2026-05-14T00:00:10Z"),
            Instant.parse("2026-05-14T00:00:10Z")
        );
    }
}
