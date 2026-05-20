package io.myforevermusic.api.modules.artist.presentation;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.myforevermusic.api.modules.artist.application.ArtistDetailService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ArtistDetailController.class)
@AutoConfigureMockMvc(addFilters = false)
class ArtistDetailControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ArtistDetailService artistDetailService;

    @Test
    void shouldReturnArtistDetailFromCanonicalSlug() throws Exception {
        when(artistDetailService.getArtistDetail("newjeans", "user-001", "NewJeans"))
            .thenReturn(new ArtistDetailResponse(
                "api",
                "ok",
                Instant.parse("2026-05-20T00:00:00Z"),
                new ArtistDetailResponse.ArtistProfile(
                    "newjeans",
                    "NewJeans",
                    "https://images.example/newjeans.jpg",
                    "Matched by artist_name across PMS and EMS tracks."
                ),
                new ArtistDetailResponse.ArtistSummary(1, 1, 2, 2),
                List.of(
                    new ArtistDetailResponse.PlatformCandidate(
                        "spotify",
                        null,
                        "NewJeans",
                        "https://images.example/newjeans.jpg",
                        "https://open.spotify.com/artist/example",
                        null,
                        null,
                        null,
                        "inferred_from_tracks"
                    )
                ),
                List.of(new ArtistDetailResponse.ArtistTrack(
                    "pms-track-001",
                    "Ditto",
                    "NewJeans",
                    "spotify",
                    "OMG",
                    "https://images.example/omg.jpg",
                    "https://open.spotify.com/track/pms-track-001",
                    "spotify:track:pms-track-001",
                    null,
                    "US1234567890",
                    185000,
                    "pms_user_library"
                )),
                List.of(new ArtistDetailResponse.ArtistTrack(
                    "ems-track-001",
                    "Super Shy",
                    "NewJeans",
                    "tidal",
                    "Get Up",
                    "https://images.example/get-up.jpg",
                    "https://tidal.com/browse/track/ems-track-001",
                    null,
                    null,
                    "US1234567891",
                    154000,
                    "acquisition_pool"
                ))
            ));

        mockMvc.perform(get("/api/v1/artists/newjeans")
                .param("user_id", "user-001")
                .param("artist_name", "NewJeans"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("api"))
            .andExpect(jsonPath("$.artist.artist_slug").value("newjeans"))
            .andExpect(jsonPath("$.artist.display_name").value("NewJeans"))
            .andExpect(jsonPath("$.summary.pms_track_count").value(1))
            .andExpect(jsonPath("$.summary.ems_track_count").value(1))
            .andExpect(jsonPath("$.platform_candidates[0].provider").value("spotify"))
            .andExpect(jsonPath("$.pms_tracks[0].title").value("Ditto"))
            .andExpect(jsonPath("$.ems_tracks[0].collection_source").value("acquisition_pool"));
    }
}
