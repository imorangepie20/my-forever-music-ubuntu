package io.myforevermusic.api.modules.recommendation.presentation;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.myforevermusic.api.modules.recommendation.application.AudioTasteMode;
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
            .andExpect(jsonPath("$.profile.profile_type").value("heavy"))
            .andExpect(jsonPath("$.profile.profile_focus").value("balanced"))
            .andExpect(jsonPath("$.profile.profile_confidence").value(0.62))
            .andExpect(jsonPath("$.profile.diversity.distinct_artist_count").value(8))
            .andExpect(jsonPath("$.profile.diversity.dominant_artist_name").value("Artist A"))
            .andExpect(jsonPath("$.profile.diversity.dominant_artist_share").value(0.18))
            .andExpect(jsonPath("$.profile.diversity.distinct_source_platform_count").value(2))
            .andExpect(jsonPath("$.profile.source_quality_mix.provider").value(0.72))
            .andExpect(jsonPath("$.profile.source_quality_mix.llm_accepted").value(0.18))
            .andExpect(jsonPath("$.profile.source_quality_mix.llm_weak").value(0.04))
            .andExpect(jsonPath("$.profile.source_quality_mix.lastfm_partial").value(0.06))
            .andExpect(jsonPath("$.profile.source_quality_mix.missing").value(0.0))
            .andExpect(jsonPath("$.profile.taste_modes[0].mode_id").value("mode-1"))
            .andExpect(jsonPath("$.profile.taste_modes[0].label").value("high_energy_bright_danceable"))
            .andExpect(jsonPath("$.profile.taste_modes[0].track_count").value(78))
            .andExpect(jsonPath("$.profile.taste_modes[0].confidence").value(0.74))
            .andExpect(jsonPath("$.profile.taste_modes[0].centroid.energy").value(0.81))
            .andExpect(jsonPath("$.profile.taste_modes[0].top_artists[0].artist_name").value("Artist A"))
            .andExpect(jsonPath("$.profile.taste_modes[0].representative_tracks[0].distance_to_centroid")
                .value(0.08))
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

    @Test
    void shouldReturnEmptyTasteModesForNonHeavyProfile() throws Exception {
        when(profileService.recompute(eq("target-user"), eq(100))).thenReturn(nonHeavyProfile());

        mockMvc.perform(get("/api/v1/recommendations/admin/audio-taste/profile")
                .param("user_id", "admin")
                .param("target_user_id", "target-user")
                .param("event_limit", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profile.profile_type").value("ready"))
            .andExpect(jsonPath("$.profile.taste_modes").isArray())
            .andExpect(jsonPath("$.profile.taste_modes").isEmpty());
    }

    private AudioTasteProfileService.Profile profile() {
        return new AudioTasteProfileService.Profile(
            "target-user",
            "ok",
            true,
            "heavy",
            "balanced",
            0.62d,
            new AudioTasteProfileService.Diversity(8, "Artist A", 0.18d, 2),
            new AudioTasteProfileService.SourceQualityMix(0.72d, 0.18d, 0.04d, 0.06d, 0.0d),
            List.of(new AudioTasteMode(
                "mode-1",
                "high_energy_bright_danceable",
                78,
                0.74d,
                new AudioTasteProfileService.Centroid(0.18d, 0.76d, 0.81d, 0.03d, 0.13d, 0.06d, 0.58d, 0.72d),
                List.of(new AudioTasteMode.TopArtist("Artist A", 12)),
                List.of(new AudioTasteMode.RepresentativeTrack(
                    "pms-track-001",
                    "Track Title",
                    "Artist A",
                    "tidal",
                    0.08d
                ))
            )),
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

    private AudioTasteProfileService.Profile nonHeavyProfile() {
        return new AudioTasteProfileService.Profile(
            "target-user",
            "ok",
            true,
            "ready",
            "balanced",
            0.62d,
            new AudioTasteProfileService.Diversity(8, "Artist A", 0.18d, 2),
            new AudioTasteProfileService.SourceQualityMix(0.72d, 0.18d, 0.04d, 0.06d, 0.0d),
            List.of(),
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
