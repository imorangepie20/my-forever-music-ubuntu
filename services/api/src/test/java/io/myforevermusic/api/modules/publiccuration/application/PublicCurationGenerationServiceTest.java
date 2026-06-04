package io.myforevermusic.api.modules.publiccuration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.gms.infrastructure.ai.AiServiceProperties;
import io.myforevermusic.api.modules.publiccuration.infrastructure.ai.AiPublicCurationScoringClient;
import io.myforevermusic.api.modules.publiccuration.infrastructure.ai.AiPublicCurationScoringClient.AiPublicCurationSelectedTrack;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PublicCurationGenerationServiceTest {

    @Test
    void shouldScoreCandidatesAndStoreGeneratedDraft() {
        CapturingScoringClient scoringClient = new CapturingScoringClient();
        CapturingPlaylistStore playlistStore = new CapturingPlaylistStore();
        PublicCurationGenerationService service = new PublicCurationGenerationService(
            scoringClient,
            playlistStore,
            new ObjectMapper()
        );

        PublicCurationPlaylistStore.StoredPlaylist stored = service.generateDraft(command());

        assertThat(scoringClient.capturedRequest.targetTrackCount()).isEqualTo(1);
        assertThat(scoringClient.capturedRequest.candidateTracks()).hasSize(1);
        assertThat(playlistStore.capturedDraft.title()).isEqualTo("비 오는 밤의 Public Curation");
        assertThat(playlistStore.capturedDraft.slug()).isEqualTo("rainy-night-public-curation");
        assertThat(playlistStore.capturedDraft.trackCount()).isEqualTo(1);
        assertThat(playlistStore.capturedDraft.durationMs()).isEqualTo(181000L);
        assertThat(playlistStore.capturedDraft.tracks().getFirst().sourceTrackId()).isEqualTo("ems-track-1");
        assertThat(playlistStore.capturedDraft.tracks().getFirst().trackOrder()).isEqualTo(1);
        assertThat(playlistStore.capturedDraft.tracks().getFirst().imageUrl()).isEqualTo("https://images.example/rain-street.jpg");
        assertThat(playlistStore.capturedDraft.run().candidateCount()).isEqualTo(3);
        assertThat(playlistStore.capturedDraft.run().selectedCount()).isEqualTo(1);
        assertThat(playlistStore.capturedDraft.run().scoreSummaryJson())
            .contains("\"candidate_preparation\"")
            .contains("\"raw_count\":75")
            .contains("\"resolved_count\":1");
        assertThat(stored.title()).isEqualTo("비 오는 밤의 Public Curation");
    }

    @Test
    void shouldRejectDuplicateTracksReturnedByAiBeforeSavingDraft() {
        CapturingScoringClient scoringClient = new CapturingScoringClient(List.of(
            selectedTrack(1, "ems-track-1", "Rain Street", "Blue Trio", "KRA000000001", "10001"),
            selectedTrack(2, "pms-track-2", "Different Metadata", "Different Artist", " kra-000000001 ", "20002")
        ));
        CapturingPlaylistStore playlistStore = new CapturingPlaylistStore();
        PublicCurationGenerationService service = new PublicCurationGenerationService(
            scoringClient,
            playlistStore,
            new ObjectMapper()
        );

        assertThatThrownBy(() -> service.generateDraft(command()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("duplicate");
        assertThat(playlistStore.capturedDraft).isNull();
    }

    private PublicCurationGenerationService.GenerateDraftCommand command() {
        return new PublicCurationGenerationService.GenerateDraftCommand(
            "rainy-night-public-curation",
            "비 오는 밤에 듣기 좋은 한국 인디와 재즈 감성",
            "{\"targetTrackCount\":1}",
            new AiPublicCurationScoringClient.AiPublicCurationFilters(
                List.of("rainy", "jazz"),
                List.of("jazz"),
                Map.of("energy", new AiPublicCurationScoringClient.AudioFeatureRange(0.2, 0.6))
            ),
            1,
            "poster-dark",
            "admin-001",
            Instant.parse("2026-05-30T01:00:00Z"),
            Map.of(
                "raw_count", 75,
                "native_tidal_count", 1,
                "resolved_count", 1,
                "playable_count", 2
            ),
            List.of(new AiPublicCurationScoringClient.AiPublicCurationCandidateTrack(
                "ems_collected_track",
                "ems-track-1",
                "Rain Street",
                "Blue Trio",
                "Night Walk",
                "https://images.example/rain-street.jpg",
                181000,
                "KRA000000001",
                "tidal",
                "10001",
                "tidal:track:10001",
                null,
                Map.of("energy", 0.42, "valence", 0.38),
                "reccobeats",
                0.88,
                true,
                List.of("jazz"),
                List.of("rainy", "night"),
                List.of("rainy jazz"),
                new AiPublicCurationScoringClient.SourcePlaylistSignals(
                    2,
                    1800,
                    List.of("Rain Cafe"),
                    List.of("비 오는 밤"),
                    List.of("editor-a"),
                    List.of("search_pool"),
                    List.of("rainy jazz")
                ),
                new AiPublicCurationScoringClient.AudienceResponse(5, 4, 1),
                0.62,
                0.72,
                "native_tidal"
            ))
        );
    }

    private static final class CapturingScoringClient extends AiPublicCurationScoringClient {
        private AiPublicCurationScoreRequest capturedRequest;
        private final List<AiPublicCurationSelectedTrack> selectedTracks;

        private CapturingScoringClient() {
            this(List.of(selectedTrack(
                1,
                "ems-track-1",
                "Rain Street",
                "Blue Trio",
                "KRA000000001",
                "10001"
            )));
        }

        private CapturingScoringClient(List<AiPublicCurationSelectedTrack> selectedTracks) {
            super(
                new AiServiceProperties(
                    "http://127.0.0.1:8000",
                    "/v1/recommendations/preview",
                    "/v1/ems/overview",
                    "/v1/ems/acquisition/signals",
                    "/v1/audio-features/infer",
                    "/v1/recommendations/datasets/sasrec/train",
                    "/v1/recommendations/datasets/sasrec/rank",
                    "/v1/recommendations/datasets/sasrec/models/latest",
                    "/v1/public-curations/score",
                    ""
                ),
                new ObjectMapper()
            );
            this.selectedTracks = selectedTracks;
        }

        @Override
        public AiPublicCurationScoreResponse score(AiPublicCurationScoreRequest request) {
            this.capturedRequest = request;
            return new AiPublicCurationScoreResponse(
                Instant.parse("2026-05-30T01:00:01Z"),
                "public-curation",
                "ok",
                "public-curation-deterministic-v1",
                "비 오는 밤의 Public Curation",
                "모델이 고른 외부 공유용 플레이리스트",
                "TIDAL-ready 후보 1곡을 선별했습니다.",
                selectedTracks,
                Map.of("candidate_count", 3, "tidal_ready_count", 1),
                "semantic_profile",
                "gpt-test",
                Map.of("mood_tags", List.of("rainy")),
                List.of()
            );
        }
    }

    private static AiPublicCurationSelectedTrack selectedTrack(
        int order,
        String sourceTrackId,
        String title,
        String artistName,
        String isrc,
        String tidalTrackId
    ) {
        return new AiPublicCurationSelectedTrack(
            order,
            "ems_collected_track",
            sourceTrackId,
            title,
            artistName,
            "Night Walk",
            "https://images.example/rain-street.jpg",
            181000,
            isrc,
            tidalTrackId,
            "tidal:track:" + tidalTrackId.trim(),
            null,
            0.94,
            Map.of("tidal_readiness", 1.0, "theme_fit", 0.9),
            "TIDAL-ready 후보입니다."
        );
    }

    private static final class CapturingPlaylistStore implements PublicCurationPlaylistStore {
        private CreateDraft capturedDraft;

        @Override
        public StoredPlaylist createDraft(CreateDraft draft) {
            this.capturedDraft = draft;
            return new StoredPlaylist(
                10L,
                draft.slug(),
                draft.title(),
                draft.subtitle(),
                draft.description(),
                draft.prompt(),
                draft.filterSnapshotJson(),
                "draft",
                draft.coverStyle(),
                draft.modelVersion(),
                draft.trackCount(),
                draft.durationMs(),
                null,
                draft.createdByAdminUserId(),
                draft.createdAt(),
                draft.createdAt(),
                List.of(new StoredTrack(
                    100L,
                    10L,
                    draft.tracks().getFirst().trackOrder(),
                    draft.tracks().getFirst().sourceTrackScope(),
                    draft.tracks().getFirst().sourceTrackId(),
                    draft.tracks().getFirst().title(),
                    draft.tracks().getFirst().artistName(),
                    draft.tracks().getFirst().albumTitle(),
                    draft.tracks().getFirst().imageUrl(),
                    draft.tracks().getFirst().durationMs(),
                    draft.tracks().getFirst().isrc(),
                    draft.tracks().getFirst().tidalTrackId(),
                    draft.tracks().getFirst().tidalUri(),
                    draft.tracks().getFirst().tidalExternalUrl(),
                    draft.tracks().getFirst().score(),
                    draft.tracks().getFirst().scoreBreakdownJson(),
                    draft.tracks().getFirst().reason(),
                    draft.createdAt()
                )),
                new StoredRun(
                    20L,
                    10L,
                    draft.run().prompt(),
                    draft.run().filterSnapshotJson(),
                    draft.run().candidateCount(),
                    draft.run().selectedCount(),
                    draft.run().modelVersion(),
                    draft.run().status(),
                    draft.run().scoreSummaryJson(),
                    draft.run().errorMessage(),
                    draft.run().startedAt(),
                    draft.run().completedAt()
                )
            );
        }

        @Override
        public StoredPlaylist publish(Long playlistId, Instant publishedAt) {
            throw new UnsupportedOperationException("publish is not used in this test.");
        }

        @Override
        public void delete(Long playlistId) {
            throw new UnsupportedOperationException("delete is not used in this test.");
        }

        @Override
        public List<StoredPlaylistSummary> findRecentForAdmin(int limit) {
            throw new UnsupportedOperationException("findRecentForAdmin is not used in this test.");
        }

        @Override
        public Optional<StoredPlaylist> findPublishedBySlug(String slug) {
            throw new UnsupportedOperationException("findPublishedBySlug is not used in this test.");
        }
    }
}
