package io.myforevermusic.api.modules.publiccuration.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.myforevermusic.api.modules.gms.infrastructure.ai.AiServiceProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AiPublicCurationScoringClientTest {

    @Test
    void shouldPostCandidateTracksToPublicCurationScoreEndpoint() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/public-curations/score", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {
                  "service": "public-curation",
                  "status": "ok",
                  "model_version": "public-curation-hybrid-v2",
                  "title": "비 오는 밤의 Public Curation",
                  "subtitle": "모델이 고른 외부 공유용 플레이리스트",
                  "description": "TIDAL-ready 후보 1곡을 선별했습니다.",
                  "tracks": [{
                    "order": 1,
                    "source_scope": "ems_collected_track",
                    "source_track_id": "ems-track-1",
                    "title": "Rain Street",
                    "artist_name": "Blue Trio",
                    "album_title": "Night Walk",
                    "image_url": "https://images.example/rain-street.jpg",
                    "duration_ms": 181000,
                    "isrc": "KRA000000001",
                    "tidal_track_id": "10001",
                    "tidal_uri": "tidal:track:10001",
                    "tidal_external_url": null,
                    "score": 0.94,
                    "score_breakdown": {"tidal_readiness": 1.0},
                    "reason": "TIDAL-ready 후보입니다."
                  }],
                  "score_summary": {"candidate_count": 1, "tidal_ready_count": 1},
                  "semantic_profile_status": "semantic_profile",
                  "semantic_profile_model": "gpt-test",
                  "semantic_profile": {
                    "mood_tags": ["rainy"],
                    "genre_tags": ["jazz"],
                    "metadata_tags": ["night"],
                    "target_audio_features": {"energy": 0.42},
                    "energy_curve": [0.3, 0.5]
                  },
                  "warnings": [],
                  "generated_at": "2026-05-30T00:00:00Z"
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            AiPublicCurationScoringClient client = new AiPublicCurationScoringClient(
                new AiServiceProperties(
                    "http://127.0.0.1:%d".formatted(server.getAddress().getPort()),
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
                new ObjectMapper().findAndRegisterModules()
            );

            AiPublicCurationScoringClient.AiPublicCurationScoreResponse response =
                client.score(sampleRequest());

            assertThat(response.status()).isEqualTo("ok");
            assertThat(response.modelVersion()).isEqualTo("public-curation-hybrid-v2");
            assertThat(response.semanticProfileStatus()).isEqualTo("semantic_profile");
            assertThat(response.tracks()).hasSize(1);
            assertThat(response.tracks().getFirst().sourceTrackId()).isEqualTo("ems-track-1");
            assertThat(response.tracks().getFirst().imageUrl()).isEqualTo("https://images.example/rain-street.jpg");
            assertThat(requestBody.get()).contains("\"target_track_count\":1");
            assertThat(requestBody.get()).contains("\"candidate_tracks\"");
            assertThat(requestBody.get()).contains("\"tidal_track_id\":\"10001\"");
            assertThat(requestBody.get()).contains("\"image_url\":\"https://images.example/rain-street.jpg\"");
            assertThat(requestBody.get()).contains("\"audio_feature_source\":\"reccobeats\"");
            assertThat(requestBody.get()).contains("\"audio_feature_confidence\":0.88");
            assertThat(requestBody.get()).contains("\"play_completed_count\":4");
            assertThat(requestBody.get()).contains("\"playback_resolution_status\":\"native_tidal\"");
        } finally {
            server.stop(0);
        }
    }

    private AiPublicCurationScoringClient.AiPublicCurationScoreRequest sampleRequest() {
        return new AiPublicCurationScoringClient.AiPublicCurationScoreRequest(
            "비 오는 밤에 듣기 좋은 한국 인디와 재즈 감성",
            new AiPublicCurationScoringClient.AiPublicCurationFilters(
                List.of("rainy", "jazz"),
                List.of("jazz"),
                Map.of("energy", new AiPublicCurationScoringClient.AudioFeatureRange(0.2, 0.6))
            ),
            1,
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
                    List.of("Rain Cafe", "Night Walk"),
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
}
