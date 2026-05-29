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
                  "model_version": "public-curation-deterministic-v1",
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
            assertThat(response.modelVersion()).isEqualTo("public-curation-deterministic-v1");
            assertThat(response.tracks()).hasSize(1);
            assertThat(response.tracks().getFirst().sourceTrackId()).isEqualTo("ems-track-1");
            assertThat(requestBody.get()).contains("\"target_track_count\":1");
            assertThat(requestBody.get()).contains("\"candidate_tracks\"");
            assertThat(requestBody.get()).contains("\"tidal_track_id\":\"10001\"");
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
}
