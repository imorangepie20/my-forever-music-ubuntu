package io.myforevermusic.api.modules.publiccuration.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.gms.infrastructure.ai.AiServiceProperties;
import io.myforevermusic.api.modules.publiccuration.infrastructure.ai.AiPublicCurationScoringClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
        assertThat(playlistStore.capturedDraft.run().candidateCount()).isEqualTo(3);
        assertThat(playlistStore.capturedDraft.run().selectedCount()).isEqualTo(1);
        assertThat(stored.title()).isEqualTo("비 오는 밤의 Public Curation");
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
            List.of(new AiPublicCurationScoringClient.AiPublicCurationCandidateTrack(
                "ems_collected_track",
                "ems-track-1",
                "Rain Street",
                "Blue Trio",
                "Night Walk",
                181000,
                "KRA000000001",
                "tidal",
                "10001",
                "tidal:track:10001",
                null,
                Map.of("energy", 0.42, "valence", 0.38),
                List.of("jazz"),
                List.of("rainy", "night"),
                0.62,
                0.72
            ))
        );
    }

    private static final class CapturingScoringClient extends AiPublicCurationScoringClient {
        private AiPublicCurationScoreRequest capturedRequest;

        private CapturingScoringClient() {
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
                List.of(new AiPublicCurationSelectedTrack(
                    1,
                    "ems_collected_track",
                    "ems-track-1",
                    "Rain Street",
                    "Blue Trio",
                    "Night Walk",
                    181000,
                    "KRA000000001",
                    "10001",
                    "tidal:track:10001",
                    null,
                    0.94,
                    Map.of("tidal_readiness", 1.0, "theme_fit", 0.9),
                    "TIDAL-ready 후보입니다."
                )),
                Map.of("candidate_count", 3, "tidal_ready_count", 1),
                List.of()
            );
        }
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
    }
}
