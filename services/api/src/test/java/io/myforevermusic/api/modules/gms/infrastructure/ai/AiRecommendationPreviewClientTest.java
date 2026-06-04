package io.myforevermusic.api.modules.gms.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.myforevermusic.api.modules.gms.presentation.GmsRecommendationPreviewRequest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AiRecommendationPreviewClientTest {

    @Test
    void shouldExposeAiValidationBodyWhenPreviewRequestIsRejected() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/recommendations/preview", exchange -> {
            byte[] response = """
                {"detail":[{"loc":["body","limit"],"msg":"Input should be less than or equal to 20"}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(422, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            AiRecommendationPreviewClient client = new AiRecommendationPreviewClient(
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

            assertThatThrownBy(() -> client.requestPreview(new GmsRecommendationPreviewRequest(
                "mood-test",
                "user-001",
                null,
                "gms",
                "calm",
                3,
                3,
                30,
                List.of(),
                List.of("Michael Jackson"),
                List.of(),
                false
            )))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("AI service responded with an error while generating a preview: 422")
                .hasMessageContaining("Input should be less than or equal to 20");
        } finally {
            server.stop(0);
        }
    }
}
