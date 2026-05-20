package io.myforevermusic.api.modules.gms.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.auth.application.AuthRegistrationService;
import io.myforevermusic.api.modules.auth.infrastructure.local.InMemoryAuthAccountStore;
import io.myforevermusic.api.modules.auth.presentation.AuthRegistrationRequest;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsTrackAudioFeatures;
import io.myforevermusic.api.modules.gms.infrastructure.ai.AiRecommendationPreviewClient;
import io.myforevermusic.api.modules.gms.infrastructure.ai.AiSasrecRankingClient;
import io.myforevermusic.api.modules.gms.presentation.GmsRecommendationPreviewRequest;
import io.myforevermusic.api.modules.gms.presentation.GmsRecommendationPreviewResponse;
import io.myforevermusic.api.modules.platform.application.LastFmProperties;
import io.myforevermusic.api.modules.platform.application.LastFmScrobbleStore;
import io.myforevermusic.api.modules.platform.infrastructure.lastfm.LastFmWebApiClient;
import io.myforevermusic.api.modules.platform.infrastructure.local.InMemoryLastFmScrobbleStore;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.infrastructure.local.InMemoryPmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsTrackAudioFeatures;
import io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileService;
import io.myforevermusic.api.modules.recommendation.application.AudioTasteScoringService;
import io.myforevermusic.api.modules.recommendation.application.ColdStartFallbackService;
import io.myforevermusic.api.modules.recommendation.application.EventSignalWeights;
import io.myforevermusic.api.modules.recommendation.application.PlaylistQualityEvaluator;
import io.myforevermusic.api.modules.recommendation.application.RecommendationReranker;
import io.myforevermusic.api.modules.recommendation.application.RecommendationSnapshotService;
import io.myforevermusic.api.modules.recommendation.application.UserMusicEventStore;
import io.myforevermusic.api.modules.recommendation.infrastructure.local.InMemoryRecommendationAuditLogStore;
import io.myforevermusic.api.modules.recommendation.infrastructure.local.InMemoryRecommendationSnapshotStore;
import io.myforevermusic.api.modules.recommendation.infrastructure.local.InMemoryTrackAudioFeatureEvidenceStore;
import io.myforevermusic.api.modules.recommendation.infrastructure.local.InMemoryUserMusicEventStore;
import io.myforevermusic.api.modules.recommendation.infrastructure.local.InMemoryUserPersonalizationProfileStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class GmsRecommendationPreviewServiceTest {

    @Test
    void shouldBlendSavedLastFmArtistsIntoGmsRequest() {
        InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
        AuthRegistrationService authRegistrationService = new AuthRegistrationService(
            authAccountStore,
            new BCryptPasswordEncoder()
        );
        String userId = authRegistrationService.register(new AuthRegistrationRequest(
            "Forever Listener",
            "gms-lastfm@example.com",
            "music2026",
            "spotify",
            false,
            true,
            true
        )).user().userId();
        authAccountStore.saveLastFmProfile(userId, "mibeen", Instant.parse("2026-05-04T00:00:00Z"));

        CapturingAiRecommendationPreviewClient aiClient = new CapturingAiRecommendationPreviewClient();
        GmsRecommendationPreviewService service = new GmsRecommendationPreviewService(
            aiClient,
            Optional.empty(),
            authAccountStore,
            new InMemoryLastFmScrobbleStore(),
            new InMemoryPmsUserLibraryStore(),
            Optional.of(new FakeLastFmWebApiClient()),
            new RecommendationSnapshotService(new InMemoryRecommendationSnapshotStore()),
            new InMemoryRecommendationAuditLogStore(),
            new PlaylistQualityEvaluator(),
            new InMemoryUserPersonalizationProfileStore(),
            new RecommendationReranker(),
            audioTasteProfileService(new InMemoryPmsUserLibraryStore()),
            new AudioTasteScoringService(),
            new ColdStartFallbackService(authAccountStore, new InMemoryPmsUserLibraryStore(), Optional.empty())
        );

        GmsRecommendationPreviewResponse response = service.previewRecommendations(
            new GmsRecommendationPreviewRequest(
                "preview-001",
                userId,
                "playlist-001",
                "gms",
                "upbeat",
                4,
                3,
                5,
                List.of("track-alpha"),
                List.of("Artist One"),
                List.of("synth-pop"),
                true
            )
        );

        assertThat(aiClient.capturedRequest.seedArtistNames()).contains("Artist One", "The Midnight", "M83");
        assertThat(response.warnings()).anyMatch(warning -> warning.contains("Saved Last.fm profile 'mibeen'"));
    }

    @Test
    void shouldPreferStoredLastFmScrobbleSnapshotForGmsRequest() {
        InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
        InMemoryLastFmScrobbleStore scrobbleStore = new InMemoryLastFmScrobbleStore();
        AuthRegistrationService authRegistrationService = new AuthRegistrationService(
            authAccountStore,
            new BCryptPasswordEncoder()
        );
        String userId = authRegistrationService.register(new AuthRegistrationRequest(
            "Forever Listener",
            "gms-stored-lastfm@example.com",
            "music2026",
            "spotify",
            false,
            true,
            true
        )).user().userId();
        authAccountStore.saveLastFmProfile(userId, "mibeen", Instant.parse("2026-05-04T00:00:00Z"));
        scrobbleStore.saveScrobbles(
            userId,
            "mibeen",
            Instant.parse("2026-05-04T08:00:00Z"),
            List.of(
                new LastFmScrobbleStore.StoredScrobble(
                    userId,
                    "mibeen",
                    "Genesis",
                    "Grimes",
                    "Visions",
                    null,
                    null,
                    Instant.parse("2026-05-04T06:00:00Z"),
                    true,
                    Instant.parse("2026-05-04T08:00:00Z")
                ),
                new LastFmScrobbleStore.StoredScrobble(
                    userId,
                    "mibeen",
                    "Oblivion",
                    "Grimes",
                    "Visions",
                    null,
                    null,
                    Instant.parse("2026-05-04T05:00:00Z"),
                    false,
                    Instant.parse("2026-05-04T08:00:00Z")
                ),
                new LastFmScrobbleStore.StoredScrobble(
                    userId,
                    "mibeen",
                    "Odessa",
                    "Caribou",
                    "Swim",
                    null,
                    null,
                    Instant.parse("2026-05-03T23:00:00Z"),
                    false,
                    Instant.parse("2026-05-04T08:00:00Z")
                )
            )
        );

        CapturingAiRecommendationPreviewClient aiClient = new CapturingAiRecommendationPreviewClient();
        GmsRecommendationPreviewService service = new GmsRecommendationPreviewService(
            aiClient,
            Optional.empty(),
            authAccountStore,
            scrobbleStore,
            new InMemoryPmsUserLibraryStore(),
            Optional.of(new FakeLastFmWebApiClient()),
            new RecommendationSnapshotService(new InMemoryRecommendationSnapshotStore()),
            new InMemoryRecommendationAuditLogStore(),
            new PlaylistQualityEvaluator(),
            new InMemoryUserPersonalizationProfileStore(),
            new RecommendationReranker(),
            audioTasteProfileService(new InMemoryPmsUserLibraryStore()),
            new AudioTasteScoringService(),
            new ColdStartFallbackService(authAccountStore, new InMemoryPmsUserLibraryStore(), Optional.empty())
        );

        GmsRecommendationPreviewResponse response = service.previewRecommendations(
            new GmsRecommendationPreviewRequest(
                "preview-002",
                userId,
                "playlist-001",
                "gms",
                "upbeat",
                4,
                3,
                5,
                List.of("track-alpha"),
                List.of("Artist One"),
                List.of("synth-pop"),
                true
            )
        );

        assertThat(aiClient.capturedRequest.seedArtistNames()).contains("Artist One", "Grimes", "Caribou");
        assertThat(aiClient.capturedRequest.seedArtistNames()).doesNotContain("The Midnight");
        assertThat(response.warnings()).anyMatch(warning -> warning.contains("Stored Last.fm scrobble snapshot"));
    }

    @Test
    void shouldStoreRecommendationSnapshotsForPlayableGmsPreviewItems() {
        InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
        InMemoryPmsUserLibraryStore pmsUserLibraryStore = new InMemoryPmsUserLibraryStore();
        InMemoryRecommendationSnapshotStore snapshotStore = new InMemoryRecommendationSnapshotStore();
        InMemoryRecommendationAuditLogStore auditLogStore = new InMemoryRecommendationAuditLogStore();
        pmsUserLibraryStore.savePlaylists("user-001", List.of(sampleLibraryPlaylist()));
        GmsRecommendationPreviewService service = new GmsRecommendationPreviewService(
            new SingleItemAiRecommendationPreviewClient(),
            Optional.empty(),
            authAccountStore,
            new InMemoryLastFmScrobbleStore(),
            pmsUserLibraryStore,
            Optional.empty(),
            new RecommendationSnapshotService(snapshotStore),
            auditLogStore,
            new PlaylistQualityEvaluator(),
            new InMemoryUserPersonalizationProfileStore(),
            new RecommendationReranker(),
            audioTasteProfileService(pmsUserLibraryStore),
            new AudioTasteScoringService(),
            new ColdStartFallbackService(authAccountStore, new InMemoryPmsUserLibraryStore(), Optional.empty())
        );

        GmsRecommendationPreviewResponse response = service.previewRecommendations(
            new GmsRecommendationPreviewRequest(
                "request-003",
                "user-001",
                "playlist-001",
                "gms",
                "upbeat",
                4,
                2,
                5,
                List.of("track-alpha"),
                List.of("Neon Bloom"),
                List.of("synth-pop"),
                true
            )
        );

        assertThat(response.items()).hasSize(1);
        assertThat(snapshotStore.findRecentByUserId("user-001", 10)).hasSize(1);
        assertThat(snapshotStore.findRecentByUserId("user-001", 1).getFirst().candidateTrackId())
            .isEqualTo("track-001");
        assertThat(snapshotStore.findRecentByUserId("user-001", 1).getFirst().modelVersion())
            .isEqualTo("gms-baseline-v1");
        assertThat(auditLogStore.findRecentByUserId("user-001", 1).getFirst().eventType())
            .isEqualTo("preview_generated");
        assertThat(auditLogStore.findRecentByUserId("user-001", 1).getFirst().itemCount())
            .isEqualTo(1);
    }

    @Test
    void shouldUseColdStartFallbackWhenPmsLibraryIsEmpty() {
        InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
        AuthRegistrationService authRegistrationService = new AuthRegistrationService(
            authAccountStore,
            new BCryptPasswordEncoder()
        );
        String userId = authRegistrationService.register(new AuthRegistrationRequest(
            "Forever Listener",
            "gms-cold-start@example.com",
            "music2026",
            "tidal",
            false,
            true,
            true
        )).user().userId();
        InMemoryPmsUserLibraryStore pmsUserLibraryStore = new InMemoryPmsUserLibraryStore();
        EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
        when(emsTrackRepository.findBySourcePlatformOrderByCollectedAtDesc("tidal"))
            .thenReturn(List.of(emsTrackWithId(88L, "TIDAL Fallback", "Fallback Artist", "tidal")));
        InMemoryRecommendationAuditLogStore auditLogStore = new InMemoryRecommendationAuditLogStore();
        GmsRecommendationPreviewService service = new GmsRecommendationPreviewService(
            new SingleItemAiRecommendationPreviewClient(),
            Optional.empty(),
            authAccountStore,
            new InMemoryLastFmScrobbleStore(),
            pmsUserLibraryStore,
            Optional.empty(),
            new RecommendationSnapshotService(new InMemoryRecommendationSnapshotStore()),
            auditLogStore,
            new PlaylistQualityEvaluator(),
            new InMemoryUserPersonalizationProfileStore(),
            new RecommendationReranker(),
            audioTasteProfileService(pmsUserLibraryStore),
            new AudioTasteScoringService(),
            new ColdStartFallbackService(authAccountStore, pmsUserLibraryStore, Optional.of(emsTrackRepository))
        );

        GmsRecommendationPreviewResponse response = service.previewRecommendations(
            new GmsRecommendationPreviewRequest(
                "request-cold-start",
                userId,
                null,
                "gms",
                "upbeat",
                4,
                2,
                5,
                List.of(),
                List.of(),
                List.of(),
                true
            )
        );

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.trackId()).isEqualTo("ems-88");
            assertThat(item.sourceSpace()).isEqualTo("cold_start");
            assertThat(item.title()).isEqualTo("TIDAL Fallback");
        });
        assertThat(response.warnings()).anyMatch(warning -> warning.startsWith("Cold-start fallback applied:"));
        assertThat(auditLogStore.findRecentByUserId(userId, 1).getFirst().fallbackReason())
            .isEqualTo("cold_start_pms_empty");
    }

    @Test
    void shouldRerankPlayableGmsPreviewItemsWithSasrecWhenModelIsConfigured() {
        InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
        InMemoryPmsUserLibraryStore pmsUserLibraryStore = new InMemoryPmsUserLibraryStore();
        pmsUserLibraryStore.savePlaylists("user-001", List.of(sampleLibraryPlaylistForSasrec()));
        FakeSasrecRankingClient sasrecRankingClient = new FakeSasrecRankingClient();
        GmsRecommendationPreviewService service = new GmsRecommendationPreviewService(
            new TwoItemAiRecommendationPreviewClient(),
            Optional.of(sasrecRankingClient),
            authAccountStore,
            new InMemoryLastFmScrobbleStore(),
            pmsUserLibraryStore,
            Optional.empty(),
            new RecommendationSnapshotService(new InMemoryRecommendationSnapshotStore()),
            new InMemoryRecommendationAuditLogStore(),
            new PlaylistQualityEvaluator(),
            new InMemoryUserPersonalizationProfileStore(),
            new RecommendationReranker(),
            audioTasteProfileService(pmsUserLibraryStore),
            new AudioTasteScoringService(),
            new ColdStartFallbackService(authAccountStore, new InMemoryPmsUserLibraryStore(), Optional.empty())
        );

        GmsRecommendationPreviewResponse response = service.previewRecommendations(
            new GmsRecommendationPreviewRequest(
                "request-004",
                "user-001",
                "playlist-001",
                "gms",
                "upbeat",
                4,
                2,
                5,
                List.of("track-001"),
                List.of("Neon Bloom"),
                List.of("synth-pop"),
                true
            )
        );

        assertThat(response.items()).extracting(GmsRecommendationPreviewResponse.RecommendationItem::trackId)
            .containsExactly("track-002", "track-001");
        assertThat(sasrecRankingClient.contextTrackIds).containsExactly("track-001");
        assertThat(sasrecRankingClient.candidateTrackIds).contains("track-001", "track-002");
        assertThat(response.context().engine()).contains("sasrec:sasrec-test-v1");
        assertThat(response.warnings()).anyMatch(warning -> warning.contains("SASRec model 'sasrec-test-v1' reranked"));
        assertThat(response.items().getFirst().reason()).contains("SASRec personalized ranking adjusted");
    }

    @Test
    void shouldBoostPlayableGmsPreviewItemsWithAudioTasteProfile() {
        InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
        InMemoryPmsUserLibraryStore pmsUserLibraryStore = new InMemoryPmsUserLibraryStore();
        InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
        pmsUserLibraryStore.savePlaylists("audio-user", List.of(sampleLibraryPlaylistForAudioTaste()));
        for (int index = 1; index <= 10; index++) {
            eventStore.save(audioTasteEvent("audio-user", "track-audio-match", index));
        }
        AudioTasteProfileService audioTasteProfileService = new AudioTasteProfileService(
            pmsUserLibraryStore,
            eventStore,
            new InMemoryTrackAudioFeatureEvidenceStore(),
            new EventSignalWeights()
        );
        GmsRecommendationPreviewService service = new GmsRecommendationPreviewService(
            new TwoItemAiRecommendationPreviewClient(),
            Optional.empty(),
            authAccountStore,
            new InMemoryLastFmScrobbleStore(),
            pmsUserLibraryStore,
            Optional.empty(),
            new RecommendationSnapshotService(new InMemoryRecommendationSnapshotStore()),
            new InMemoryRecommendationAuditLogStore(),
            new PlaylistQualityEvaluator(),
            new InMemoryUserPersonalizationProfileStore(),
            new RecommendationReranker(),
            audioTasteProfileService,
            new AudioTasteScoringService(),
            new ColdStartFallbackService(authAccountStore, pmsUserLibraryStore, Optional.empty())
        );

        GmsRecommendationPreviewResponse response = service.previewRecommendations(
            new GmsRecommendationPreviewRequest(
                "request-audio-taste",
                "audio-user",
                "playlist-audio",
                "gms",
                "upbeat",
                4,
                2,
                2,
                List.of(),
                List.of(),
                List.of(),
                true
            )
        );

        assertThat(response.items()).extracting(GmsRecommendationPreviewResponse.RecommendationItem::trackId)
            .containsExactly("track-audio-match", "track-audio-far");
        assertThat(response.warnings())
            .anyMatch(warning -> warning.contains("Audio taste ranking adjusted"));
        assertThat(response.context().engine()).contains("audio-taste:v1");
        assertThat(response.items().getFirst().reason()).contains("Audio taste matched");
    }

    private AudioTasteProfileService audioTasteProfileService(PmsUserLibraryStore pmsUserLibraryStore) {
        return new AudioTasteProfileService(
            pmsUserLibraryStore,
            new InMemoryUserMusicEventStore(),
            new InMemoryTrackAudioFeatureEvidenceStore(),
            new EventSignalWeights()
        );
    }

    private static final class CapturingAiRecommendationPreviewClient extends AiRecommendationPreviewClient {

        private GmsRecommendationPreviewRequest capturedRequest;

        private CapturingAiRecommendationPreviewClient() {
            super(new io.myforevermusic.api.modules.gms.infrastructure.ai.AiServiceProperties("http://localhost:8000", "/v1/recommendations/preview", "/v1/ems/overview", "/v1/ems/acquisition/signals", "/v1/audio-features/infer", "/v1/recommendations/datasets/sasrec/train", "/v1/recommendations/datasets/sasrec/rank", "/v1/recommendations/datasets/sasrec/models/latest", ""), new ObjectMapper());
        }

        @Override
        public GmsRecommendationPreviewResponse requestPreview(GmsRecommendationPreviewRequest request) {
            this.capturedRequest = request;
            return new GmsRecommendationPreviewResponse(
                "preview-001",
                Instant.parse("2026-05-04T01:00:00Z"),
                "ai",
                "ok",
                new GmsRecommendationPreviewResponse.RecommendationContext(
                    "gms-hybrid-blend",
                    "rule-based-preview-v1",
                    "gms",
                    "upbeat",
                    4,
                    List.of("track-alpha", "artist-one", "the-midnight", "m83")
                ),
                new GmsRecommendationPreviewResponse.RecommendationInputSummary(
                    request.userId(),
                    request.playlistId(),
                    request.seedTrackIds().size(),
                    request.seedArtistNames().size(),
                    request.seedGenres().size(),
                    request.familiarityBias(),
                    request.limit()
                ),
                List.of(),
                List.of()
            );
        }
    }

    private static final class SingleItemAiRecommendationPreviewClient extends AiRecommendationPreviewClient {

        private SingleItemAiRecommendationPreviewClient() {
            super(new io.myforevermusic.api.modules.gms.infrastructure.ai.AiServiceProperties("http://localhost:8000", "/v1/recommendations/preview", "/v1/ems/overview", "/v1/ems/acquisition/signals", "/v1/audio-features/infer", "/v1/recommendations/datasets/sasrec/train", "/v1/recommendations/datasets/sasrec/rank", "/v1/recommendations/datasets/sasrec/models/latest", ""), new ObjectMapper());
        }

        @Override
        public GmsRecommendationPreviewResponse requestPreview(GmsRecommendationPreviewRequest request) {
            return new GmsRecommendationPreviewResponse(
                "recommendation-003",
                Instant.parse("2026-05-04T01:00:00Z"),
                "ai",
                "ok",
                new GmsRecommendationPreviewResponse.RecommendationContext(
                    "gms-hybrid-blend",
                    "rule-based-preview-v1",
                    "gms",
                    "upbeat",
                    4,
                    List.of("track-alpha", "neon-bloom")
                ),
                new GmsRecommendationPreviewResponse.RecommendationInputSummary(
                    request.userId(),
                    request.playlistId(),
                    request.seedTrackIds().size(),
                    request.seedArtistNames().size(),
                    request.seedGenres().size(),
                    request.familiarityBias(),
                    request.limit()
                ),
                List.of(new GmsRecommendationPreviewResponse.RecommendationItem(
                    1,
                    "ai-track-placeholder",
                    "AI Placeholder",
                    "AI Artist",
                    "spotify",
                    "playlist-001",
                    "Night Drive Archive",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0.75,
                    "gms",
                    4,
                    "AI candidate."
                )),
                List.of()
            );
        }
    }

    private static final class TwoItemAiRecommendationPreviewClient extends AiRecommendationPreviewClient {

        private TwoItemAiRecommendationPreviewClient() {
            super(new io.myforevermusic.api.modules.gms.infrastructure.ai.AiServiceProperties("http://localhost:8000", "/v1/recommendations/preview", "/v1/ems/overview", "/v1/ems/acquisition/signals", "/v1/audio-features/infer", "/v1/recommendations/datasets/sasrec/train", "/v1/recommendations/datasets/sasrec/rank", "/v1/recommendations/datasets/sasrec/models/latest", ""), new ObjectMapper());
        }

        @Override
        public GmsRecommendationPreviewResponse requestPreview(GmsRecommendationPreviewRequest request) {
            return new GmsRecommendationPreviewResponse(
                "recommendation-004",
                Instant.parse("2026-05-04T01:00:00Z"),
                "ai",
                "ok",
                new GmsRecommendationPreviewResponse.RecommendationContext(
                    "gms-hybrid-blend",
                    "rule-based-preview-v1",
                    "gms",
                    "upbeat",
                    4,
                    List.of("track-001", "neon-bloom")
                ),
                new GmsRecommendationPreviewResponse.RecommendationInputSummary(
                    request.userId(),
                    request.playlistId(),
                    request.seedTrackIds().size(),
                    request.seedArtistNames().size(),
                    request.seedGenres().size(),
                    request.familiarityBias(),
                    request.limit()
                ),
                List.of(
                    new GmsRecommendationPreviewResponse.RecommendationItem(
                        1,
                        "ai-track-placeholder-001",
                        "AI Placeholder One",
                        "AI Artist",
                        "spotify",
                        "playlist-001",
                        "Night Drive Archive",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0.75,
                        "gms",
                        4,
                        "AI candidate one."
                    ),
                    new GmsRecommendationPreviewResponse.RecommendationItem(
                        2,
                        "ai-track-placeholder-002",
                        "AI Placeholder Two",
                        "AI Artist",
                        "spotify",
                        "playlist-001",
                        "Night Drive Archive",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0.71,
                        "gms",
                        4,
                        "AI candidate two."
                    )
                ),
                List.of()
            );
        }
    }

    private static final class FakeSasrecRankingClient extends AiSasrecRankingClient {

        private List<String> contextTrackIds = List.of();
        private List<String> candidateTrackIds = List.of();

        private FakeSasrecRankingClient() {
            super(new io.myforevermusic.api.modules.gms.infrastructure.ai.AiServiceProperties("http://localhost:8000", "/v1/recommendations/preview", "/v1/ems/overview", "/v1/ems/acquisition/signals", "/v1/audio-features/infer", "/v1/recommendations/datasets/sasrec/train", "/v1/recommendations/datasets/sasrec/rank", "/v1/recommendations/datasets/sasrec/models/latest", "sasrec-test-v1"), new ObjectMapper());
        }

        @Override
        public Optional<SasrecRankingResponse> rankCandidates(
            String userId,
            List<String> contextTrackIds,
            List<String> candidateTrackIds,
            int limit
        ) {
            this.contextTrackIds = contextTrackIds;
            this.candidateTrackIds = candidateTrackIds;
            return Optional.of(new SasrecRankingResponse(
                "sasrec-ranking",
                "ok",
                "sasrec-test-v1",
                List.of(
                    new SasrecRankedCandidate(1, "track-002", 2, 0.98d),
                    new SasrecRankedCandidate(2, "track-001", 1, 0.12d)
                ),
                List.of()
            ));
        }
    }

    private static final class FakeLastFmWebApiClient extends LastFmWebApiClient {

        private FakeLastFmWebApiClient() {
            super(new LastFmProperties(), new ObjectMapper());
        }

        @Override
        public List<LastFmTopArtist> getTopArtists(String username, String period, int limit) {
            return List.of(
                new LastFmTopArtist("The Midnight", 1, 88L, "https://www.last.fm/music/The+Midnight", null),
                new LastFmTopArtist("M83", 2, 56L, "https://www.last.fm/music/M83", null)
            );
        }
    }

    private PmsUserLibraryStore.LibraryPlaylistState sampleLibraryPlaylist() {
        return new PmsUserLibraryStore.LibraryPlaylistState(
            "user-001",
            "playlist-001",
            "spotify-playlist-001",
            "Night Drive Archive",
            "spotify",
            "Forever Listener",
            "Synced from imported playlists.",
            null,
            "https://open.spotify.com/playlist/spotify-playlist-001",
            "spotify:playlist:spotify-playlist-001",
            Instant.parse("2026-05-04T00:00:00Z"),
            List.of(
                new PmsUserLibraryStore.LibraryTrackState(
                    "track-001",
                    "spotify-track-001",
                    "Midnight Receiver",
                    "Neon Bloom",
                    "spotify",
                    "synth-pop",
                    "Signal Bloom",
                    null,
                    "https://open.spotify.com/track/spotify-track-001",
                    "spotify:track:spotify-track-001",
                    null,
                    1,
                    true,
                    PmsTrackAudioFeatures.unresolved()
                )
            )
        );
    }

    private PmsUserLibraryStore.LibraryPlaylistState sampleLibraryPlaylistForSasrec() {
        return new PmsUserLibraryStore.LibraryPlaylistState(
            "user-001",
            "playlist-001",
            "spotify-playlist-001",
            "Night Drive Archive",
            "spotify",
            "Forever Listener",
            "Synced from imported playlists.",
            null,
            "https://open.spotify.com/playlist/spotify-playlist-001",
            "spotify:playlist:spotify-playlist-001",
            Instant.parse("2026-05-04T00:00:00Z"),
            List.of(
                new PmsUserLibraryStore.LibraryTrackState(
                    "track-001",
                    "spotify-track-001",
                    "Midnight Receiver",
                    "Neon Bloom",
                    "spotify",
                    "synth-pop",
                    "Signal Bloom",
                    null,
                    "https://open.spotify.com/track/spotify-track-001",
                    "spotify:track:spotify-track-001",
                    null,
                    1,
                    true,
                    PmsTrackAudioFeatures.unresolved()
                ),
                new PmsUserLibraryStore.LibraryTrackState(
                    "track-002",
                    "spotify-track-002",
                    "Afterimage Drive",
                    "Signal Glass",
                    "spotify",
                    "synth-pop",
                    "Signal Bloom",
                    null,
                    "https://open.spotify.com/track/spotify-track-002",
                    "spotify:track:spotify-track-002",
                    null,
                    2,
                    false,
                    PmsTrackAudioFeatures.unresolved()
                )
            )
        );
    }

    private PmsUserLibraryStore.LibraryPlaylistState sampleLibraryPlaylistForAudioTaste() {
        return new PmsUserLibraryStore.LibraryPlaylistState(
            "audio-user",
            "playlist-audio",
            "spotify-playlist-audio",
            "Audio Taste Library",
            "spotify",
            "Forever Listener",
            "Synced from imported playlists.",
            null,
            "https://open.spotify.com/playlist/spotify-playlist-audio",
            "spotify:playlist:spotify-playlist-audio",
            Instant.parse("2026-05-21T00:00:00Z"),
            List.of(
                new PmsUserLibraryStore.LibraryTrackState(
                    "track-audio-far",
                    "spotify-track-audio-far",
                    "Static Skyline",
                    "Distance Field",
                    "spotify",
                    "synth-pop",
                    "Far Album",
                    null,
                    "https://open.spotify.com/track/spotify-track-audio-far",
                    "spotify:track:spotify-track-audio-far",
                    null,
                    1,
                    true,
                    audioTasteFeatures(
                        "spotify-track-audio-far",
                        0.94d,
                        0.08d,
                        0.48d,
                        0.92d,
                        0.88d,
                        0.75d,
                        64.0d,
                        0.24d
                    )
                ),
                new PmsUserLibraryStore.LibraryTrackState(
                    "track-audio-match",
                    "spotify-track-audio-match",
                    "Velvet Voltage",
                    "Near Field",
                    "spotify",
                    "synth-pop",
                    "Match Album",
                    null,
                    "https://open.spotify.com/track/spotify-track-audio-match",
                    "spotify:track:spotify-track-audio-match",
                    null,
                    2,
                    false,
                    audioTasteFeatures(
                        "spotify-track-audio-match",
                        0.20d,
                        0.82d,
                        0.66d,
                        0.01d,
                        0.12d,
                        0.05d,
                        124.0d,
                        0.78d
                    )
                )
            )
        );
    }

    private UserMusicEventStore.EventDraft audioTasteEvent(String userId, String trackId, int sequence) {
        return new UserMusicEventStore.EventDraft(
            userId,
            "track_saved",
            2.0d,
            "pms",
            "spotify",
            "spotify",
            trackId,
            "track",
            trackId,
            "playlist-audio",
            trackId,
            null,
            "Title",
            "Artist",
            null,
            null,
            180000,
            null,
            null,
            null,
            1.0d,
            Instant.parse("2026-05-21T00:00:00Z").plusSeconds(sequence)
        );
    }

    private PmsTrackAudioFeatures audioTasteFeatures(
        String audioFeatureTrackId,
        double acousticness,
        double danceability,
        double energy,
        double instrumentalness,
        double liveness,
        double speechiness,
        double tempo,
        double valence
    ) {
        return new PmsTrackAudioFeatures(
            audioFeatureTrackId,
            "reccobeats_lookup",
            true,
            null,
            null,
            "spotify:track:" + audioFeatureTrackId,
            "audio_features",
            180000,
            1,
            1,
            4,
            acousticness,
            danceability,
            energy,
            instrumentalness,
            liveness,
            -8.0d,
            speechiness,
            tempo,
            valence,
            Instant.parse("2026-05-21T00:00:00Z")
        );
    }

    private EmsCollectedTrackEntity emsTrackWithId(long id, String title, String artist, String platform) {
        EmsTrackAudioFeatures features = new EmsTrackAudioFeatures(
            "audio-" + id,
            "reccobeats",
            true,
            null, null, null, null,
            null, null, null, null,
            null, null, null, null,
            null, null, null, null, null,
            Instant.parse("2026-05-14T00:00:00Z")
        );
        EmsCollectedTrackEntity entity = new EmsCollectedTrackEntity(
            "external-" + id,
            title,
            artist,
            platform,
            null,
            "Fallback Album",
            null,
            null,
            null,
            null,
            210000,
            "acquisition_pool",
            Instant.parse("2026-05-14T00:00:00Z"),
            features
        );
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
