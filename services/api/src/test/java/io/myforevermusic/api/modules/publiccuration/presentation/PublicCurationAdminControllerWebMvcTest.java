package io.myforevermusic.api.modules.publiccuration.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.myforevermusic.api.modules.publiccuration.application.PublicCurationCandidatePoolStore;
import io.myforevermusic.api.modules.publiccuration.application.PublicCurationGenerationService;
import io.myforevermusic.api.modules.publiccuration.application.PublicCurationPlaylistStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PublicCurationAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class PublicCurationAdminControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PublicCurationCandidatePoolStore candidatePoolStore;

    @MockBean
    private PublicCurationGenerationService generationService;

    @Test
    void shouldGenerateDraftFromCandidatePool() throws Exception {
        when(candidatePoolStore.findCandidates(any(PublicCurationCandidatePoolStore.CandidateQuery.class)))
            .thenReturn(List.of(candidateTrack()));
        when(generationService.generateDraft(any(PublicCurationGenerationService.GenerateDraftCommand.class)))
            .thenReturn(storedPlaylist());

        mockMvc.perform(post("/api/v1/public-curations/admin/runs")
                .contentType("application/json")
                .content("""
                    {
                      "admin_user_id": "admin-001",
                      "slug": "rainy-night-public-curation",
                      "prompt": "비 오는 밤에 듣기 좋은 한국 인디와 재즈 감성",
                      "target_track_count": 1,
                      "candidate_limit": 25,
                      "cover_style": "poster-dark",
                      "filters": {
                        "mood_tags": ["rainy"],
                        "genre_tags": ["jazz"],
                        "audio_feature_ranges": {
                          "energy": {"min": 0.2, "max": 0.6}
                        }
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("public-curation-admin"))
            .andExpect(jsonPath("$.status").value("draft_created"))
            .andExpect(jsonPath("$.playlist.playlist_id").value(10))
            .andExpect(jsonPath("$.playlist.slug").value("rainy-night-public-curation"))
            .andExpect(jsonPath("$.playlist.title").value("비 오는 밤의 Public Curation"))
            .andExpect(jsonPath("$.playlist.track_count").value(1));

        ArgumentCaptor<PublicCurationCandidatePoolStore.CandidateQuery> queryCaptor =
            ArgumentCaptor.forClass(PublicCurationCandidatePoolStore.CandidateQuery.class);
        verify(candidatePoolStore).findCandidates(queryCaptor.capture());
        assertThat(queryCaptor.getValue().limit()).isEqualTo(25);
        assertThat(queryCaptor.getValue().tidalReadyRequired()).isTrue();

        ArgumentCaptor<PublicCurationGenerationService.GenerateDraftCommand> commandCaptor =
            ArgumentCaptor.forClass(PublicCurationGenerationService.GenerateDraftCommand.class);
        verify(generationService).generateDraft(commandCaptor.capture());
        assertThat(commandCaptor.getValue().candidateTracks()).hasSize(1);
        assertThat(commandCaptor.getValue().candidateTracks().getFirst().tidalTrackId()).isEqualTo("10001");
        assertThat(commandCaptor.getValue().filters().moodTags()).containsExactly("rainy");
    }

    private PublicCurationCandidatePoolStore.CandidateTrack candidateTrack() {
        return new PublicCurationCandidatePoolStore.CandidateTrack(
            "pms_user_track",
            "track-001",
            "Rain Street",
            "Blue Trio",
            "Night Walk",
            181000,
            "KRA000000001",
            "tidal",
            "10001",
            "tidal:track:10001",
            "https://tidal.com/browse/track/10001",
            Map.of("energy", 0.42d, "valence", 0.38d),
            List.of("jazz"),
            List.of("tidal")
        );
    }

    private PublicCurationPlaylistStore.StoredPlaylist storedPlaylist() {
        Instant now = Instant.parse("2026-05-30T01:00:00Z");
        return new PublicCurationPlaylistStore.StoredPlaylist(
            10L,
            "rainy-night-public-curation",
            "비 오는 밤의 Public Curation",
            "모델이 고른 외부 공유용 플레이리스트",
            "TIDAL-ready 후보 1곡을 선별했습니다.",
            "비 오는 밤에 듣기 좋은 한국 인디와 재즈 감성",
            "{\"targetTrackCount\":1}",
            "draft",
            "poster-dark",
            "public-curation-deterministic-v1",
            1,
            181000L,
            null,
            "admin-001",
            now,
            now,
            List.of(),
            null
        );
    }
}
