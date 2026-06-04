package io.myforevermusic.api.modules.publiccuration.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.myforevermusic.api.modules.publiccuration.application.PublicCurationCandidatePoolStore;
import io.myforevermusic.api.modules.publiccuration.application.PublicCurationGenerationService;
import io.myforevermusic.api.modules.publiccuration.application.PublicCurationPlayableCandidateService;
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
    private PublicCurationPlayableCandidateService playableCandidateService;

    @MockBean
    private PublicCurationGenerationService generationService;

    @MockBean
    private PublicCurationPlaylistStore playlistStore;

    @Test
    void shouldGenerateDraftFromCandidatePool() throws Exception {
        when(playableCandidateService.prepare(any(PublicCurationPlayableCandidateService.PrepareCommand.class)))
            .thenReturn(new PublicCurationPlayableCandidateService.PreparedCandidateBatch(
                List.of(candidateTrack()),
                new PublicCurationPlayableCandidateService.CandidatePreparationSummary(
                    75,
                    1,
                    3,
                    1,
                    2,
                    0,
                    2,
                    73,
                    0.3333d
                )
            ));
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
            .andExpect(jsonPath("$.playlist.track_count").value(1))
            .andExpect(jsonPath("$.playlist.tracks[0].title").value("Rain Street"))
            .andExpect(jsonPath("$.playlist.tracks[0].tidal_track_id").value("10001"))
            .andExpect(jsonPath("$.playlist.tracks[0].score_breakdown.theme_fit").value(0.9))
            .andExpect(jsonPath("$.playlist.score_summary.semantic_profile_status").value("semantic_profile"))
            .andExpect(jsonPath("$.playlist.score_summary.candidate_preparation.raw_count").value(75))
            .andExpect(jsonPath("$.playlist.score_summary.candidate_preparation.resolved_count").value(1))
            .andExpect(jsonPath("$.playlist.tracks[0].reason").value("비 오는 밤의 첫 분위기를 부드럽게 잡아준다."));

        ArgumentCaptor<PublicCurationPlayableCandidateService.PrepareCommand> prepareCaptor =
            ArgumentCaptor.forClass(PublicCurationPlayableCandidateService.PrepareCommand.class);
        verify(playableCandidateService).prepare(prepareCaptor.capture());
        assertThat(prepareCaptor.getValue().adminUserId()).isEqualTo("admin-001");
        assertThat(prepareCaptor.getValue().playableLimit()).isEqualTo(25);
        assertThat(prepareCaptor.getValue().targetTrackCount()).isEqualTo(1);

        ArgumentCaptor<PublicCurationGenerationService.GenerateDraftCommand> commandCaptor =
            ArgumentCaptor.forClass(PublicCurationGenerationService.GenerateDraftCommand.class);
        verify(generationService).generateDraft(commandCaptor.capture());
        assertThat(commandCaptor.getValue().candidateTracks()).hasSize(1);
        assertThat(commandCaptor.getValue().candidateTracks().getFirst().tidalTrackId()).isEqualTo("10001");
        assertThat(commandCaptor.getValue().candidateTracks().getFirst().imageUrl()).isEqualTo("https://images.example/rain-street.jpg");
        assertThat(commandCaptor.getValue().filters().moodTags()).containsExactly("rainy");
    }

    @Test
    void shouldClampLargeCandidateLimitForStableModelRuns() throws Exception {
        when(playableCandidateService.prepare(any(PublicCurationPlayableCandidateService.PrepareCommand.class)))
            .thenReturn(new PublicCurationPlayableCandidateService.PreparedCandidateBatch(
                List.of(candidateTrack()),
                new PublicCurationPlayableCandidateService.CandidatePreparationSummary(
                    720,
                    180,
                    60,
                    40,
                    20,
                    0,
                    220,
                    500,
                    0.6667d
                )
            ));
        when(generationService.generateDraft(any(PublicCurationGenerationService.GenerateDraftCommand.class)))
            .thenReturn(storedPlaylist());

        mockMvc.perform(post("/api/v1/public-curations/admin/runs")
                .contentType("application/json")
                .content("""
                    {
                      "admin_user_id": "admin-001",
                      "slug": "large-pool",
                      "prompt": "넓은 후보풀에서 다양한 곡을 골라줘",
                      "target_track_count": 30,
                      "candidate_limit": 500,
                      "cover_style": "poster-dark",
                      "filters": {
                        "mood_tags": ["night"],
                        "genre_tags": ["jazz"],
                        "audio_feature_ranges": {}
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("draft_created"));

        ArgumentCaptor<PublicCurationPlayableCandidateService.PrepareCommand> prepareCaptor =
            ArgumentCaptor.forClass(PublicCurationPlayableCandidateService.PrepareCommand.class);
        verify(playableCandidateService).prepare(prepareCaptor.capture());
        assertThat(prepareCaptor.getValue().playableLimit()).isEqualTo(240);
        assertThat(prepareCaptor.getValue().targetTrackCount()).isEqualTo(30);
    }

    @Test
    void shouldPublishDraftPlaylist() throws Exception {
        Instant publishedAt = Instant.parse("2026-05-30T02:00:00Z");
        when(playlistStore.publish(eq(10L), any(Instant.class)))
            .thenReturn(storedPlaylist("published", publishedAt));

        mockMvc.perform(post("/api/v1/public-curations/admin/playlists/10/publish"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("public-curation-admin"))
            .andExpect(jsonPath("$.status").value("published"))
            .andExpect(jsonPath("$.playlist.playlist_id").value(10))
            .andExpect(jsonPath("$.playlist.slug").value("rainy-night-public-curation"))
            .andExpect(jsonPath("$.playlist.status").value("published"));

        ArgumentCaptor<Instant> publishedAtCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(playlistStore).publish(eq(10L), publishedAtCaptor.capture());
        assertThat(publishedAtCaptor.getValue()).isNotNull();
    }

    @Test
    void shouldListSavedPublicCurationPlaylistsForAdmin() throws Exception {
        Instant publishedAt = Instant.parse("2026-05-30T02:00:00Z");
        when(playlistStore.findRecentForAdmin(25))
            .thenReturn(List.of(storedPlaylist("published", publishedAt).toSummary()));

        mockMvc.perform(get("/api/v1/public-curations/admin/playlists?limit=25"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("public-curation-admin"))
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.playlists[0].playlist_id").value(10))
            .andExpect(jsonPath("$.playlists[0].slug").value("rainy-night-public-curation"))
            .andExpect(jsonPath("$.playlists[0].title").value("비 오는 밤의 Public Curation"))
            .andExpect(jsonPath("$.playlists[0].status").value("published"))
            .andExpect(jsonPath("$.playlists[0].track_count").value(1))
            .andExpect(jsonPath("$.playlists[0].published_at").value("2026-05-30T02:00:00Z"));

        verify(playlistStore).findRecentForAdmin(25);
    }

    @Test
    void shouldDeleteSavedPublicCurationPlaylist() throws Exception {
        mockMvc.perform(delete("/api/v1/public-curations/admin/playlists/10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("public-curation-admin"))
            .andExpect(jsonPath("$.status").value("deleted"))
            .andExpect(jsonPath("$.playlist_id").value(10));

        verify(playlistStore).delete(10L);
    }

    private PublicCurationCandidatePoolStore.CandidateTrack candidateTrack() {
        return new PublicCurationCandidatePoolStore.CandidateTrack(
            "pms_user_track",
            "track-001",
            "Rain Street",
            "Blue Trio",
            "Night Walk",
            "https://images.example/rain-street.jpg",
            181000,
            "KRA000000001",
            "tidal",
            "10001",
            "tidal:track:10001",
            "https://tidal.com/browse/track/10001",
            Map.of("energy", 0.42d, "valence", 0.38d),
            "reccobeats",
            0.88d,
            true,
            List.of("jazz"),
            List.of("tidal"),
            List.of("rainy jazz"),
            new PublicCurationCandidatePoolStore.SourcePlaylistSignals(
                2,
                1800,
                List.of("Rain Cafe"),
                List.of("비 오는 밤"),
                List.of("editor-a"),
                List.of("search_pool"),
                List.of("rainy jazz")
            ),
            new PublicCurationCandidatePoolStore.AudienceResponse(5, 4, 1),
            0.92d,
            PublicCurationCandidatePoolStore.PLAYBACK_RESOLUTION_NATIVE_TIDAL
        );
    }

    private PublicCurationPlaylistStore.StoredPlaylist storedPlaylist() {
        return storedPlaylist("draft", null);
    }

    private PublicCurationPlaylistStore.StoredPlaylist storedPlaylist(String status, Instant publishedAt) {
        Instant now = Instant.parse("2026-05-30T01:00:00Z");
        return new PublicCurationPlaylistStore.StoredPlaylist(
            10L,
            "rainy-night-public-curation",
            "비 오는 밤의 Public Curation",
            "모델이 고른 외부 공유용 플레이리스트",
            "TIDAL-ready 후보 1곡을 선별했습니다.",
            "비 오는 밤에 듣기 좋은 한국 인디와 재즈 감성",
            "{\"targetTrackCount\":1}",
            status,
            "poster-dark",
            "public-curation-deterministic-v1",
            1,
            181000L,
            publishedAt,
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
                null,
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
            new PublicCurationPlaylistStore.StoredRun(
                200L,
                10L,
                "비 오는 밤에 듣기 좋은 한국 인디와 재즈 감성",
                "{\"targetTrackCount\":1}",
                3,
                1,
                "public-curation-hybrid-v2",
                "completed",
                "{\"semantic_profile_status\":\"semantic_profile\",\"average_score\":0.94,\"candidate_preparation\":{\"raw_count\":75,\"resolved_count\":1}}",
                null,
                now,
                now
            )
        );
    }
}
