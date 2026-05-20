package io.myforevermusic.api.modules.recommendation.presentation;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AudioTasteAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class AudioTasteAdminControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AudioTasteProfileService profileService;

    @Test
    void shouldReturnAudioTasteProfile() throws Exception {
        when(profileService.recompute(eq("target-user"), eq(100))).thenReturn(profile());

        mockMvc.perform(get("/api/v1/recommendations/admin/audio-taste/profile")
                .param("user_id", "admin")
                .param("target_user_id", "target-user")
                .param("event_limit", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.profile.user_id").value("target-user"))
            .andExpect(jsonPath("$.profile.audio_taste_applicable").value(true))
            .andExpect(jsonPath("$.profile.coverage.feature_ready_ratio").value(0.75));
    }

    @Test
    void shouldRecomputeAudioTasteProfile() throws Exception {
        when(profileService.recompute(eq("target-user"), eq(100))).thenReturn(profile());

        mockMvc.perform(post("/api/v1/recommendations/admin/audio-taste/recompute")
                .param("user_id", "admin")
                .param("target_user_id", "target-user")
                .param("event_limit", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.profile.positive_track_count").value(12));
    }

    private AudioTasteProfileService.Profile profile() {
        return new AudioTasteProfileService.Profile(
            "target-user",
            "ok",
            true,
            12,
            1,
            100,
            new AudioTasteProfileService.Centroid(0.2d, 0.7d, 0.7d, 0.01d, 0.1d, 0.05d, 0.43d, 0.7d),
            AudioTasteProfileService.Centroid.empty(),
            new AudioTasteProfileService.Coverage(20, 15, 0.75d, 2, 1),
            List.of(),
            Instant.parse("2026-05-21T00:00:00Z")
        );
    }
}
