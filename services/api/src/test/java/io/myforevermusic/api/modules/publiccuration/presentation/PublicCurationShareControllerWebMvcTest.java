package io.myforevermusic.api.modules.publiccuration.presentation;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.myforevermusic.api.modules.publiccuration.application.PublicCurationPlaylistStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PublicCurationShareController.class)
@AutoConfigureMockMvc(addFilters = false)
class PublicCurationShareControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PublicCurationPlaylistStore playlistStore;

    @Test
    void shouldReturnPublishedPlaylistBySlug() throws Exception {
        when(playlistStore.findPublishedBySlug("rainy-night-public-curation"))
            .thenReturn(Optional.of(publishedPlaylist()));

        mockMvc.perform(get("/api/v1/public-curations/share/rainy-night-public-curation"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("public-curation-share"))
            .andExpect(jsonPath("$.status").value("published"))
            .andExpect(jsonPath("$.playlist.slug").value("rainy-night-public-curation"))
            .andExpect(jsonPath("$.playlist.title").value("비 오는 밤의 Public Curation"))
            .andExpect(jsonPath("$.playlist.track_count").value(1))
            .andExpect(jsonPath("$.playlist.tracks[0].title").value("Rain Street"))
            .andExpect(jsonPath("$.playlist.tracks[0].image_url").value("https://images.example/rain-street.jpg"))
            .andExpect(jsonPath("$.playlist.tracks[0].tidal_track_id").value("10001"))
            .andExpect(jsonPath("$.playlist.tracks[0].score").doesNotExist())
            .andExpect(jsonPath("$.playlist.tracks[0].score_breakdown_json").doesNotExist())
            .andExpect(jsonPath("$.playlist.tracks[0].reason").doesNotExist());
    }

    @Test
    void shouldReturnNotFoundWhenPlaylistIsNotPublished() throws Exception {
        when(playlistStore.findPublishedBySlug("draft-only"))
            .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/public-curations/share/draft-only"))
            .andExpect(status().isNotFound());
    }

    private PublicCurationPlaylistStore.StoredPlaylist publishedPlaylist() {
        Instant now = Instant.parse("2026-05-30T02:00:00Z");
        return new PublicCurationPlaylistStore.StoredPlaylist(
            10L,
            "rainy-night-public-curation",
            "비 오는 밤의 Public Curation",
            "모델이 고른 외부 공유용 플레이리스트",
            "TIDAL-ready 후보를 선별했습니다.",
            "비 오는 밤에 듣기 좋은 한국 인디와 재즈 감성",
            "{\"targetTrackCount\":1}",
            "published",
            "poster-dark",
            "public-curation-deterministic-v1",
            1,
            181000L,
            now,
            "admin-001",
            now,
            now,
            List.of(new PublicCurationPlaylistStore.StoredTrack(
                100L,
                10L,
                1,
                "pms_user_track",
                "track-001",
                "Rain Street",
                "Blue Trio",
                "Night Walk",
                "https://images.example/rain-street.jpg",
                181000,
                "KRA000000001",
                "10001",
                "tidal:track:10001",
                "https://tidal.com/browse/track/10001",
                0.94,
                "{\"theme_fit\":0.9}",
                "비 오는 밤의 첫 분위기를 부드럽게 잡아준다.",
                now
            )),
            null
        );
    }
}
