package io.myforevermusic.api.modules.recommendation.infrastructure.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.modules.gms.infrastructure.ai.AiServiceProperties;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

@Component
public class AiAudioFeatureInferenceClient {

    private final HttpClient httpClient;
    private final AiServiceProperties aiServiceProperties;
    private final ObjectMapper objectMapper;

    @Autowired
    public AiAudioFeatureInferenceClient(
        AiServiceProperties aiServiceProperties,
        ObjectMapper objectMapper
    ) {
        this(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(), aiServiceProperties, objectMapper);
    }

    AiAudioFeatureInferenceClient(
        HttpClient httpClient,
        AiServiceProperties aiServiceProperties,
        ObjectMapper objectMapper
    ) {
        this.httpClient = httpClient;
        this.aiServiceProperties = aiServiceProperties;
        this.objectMapper = objectMapper;
    }

    public AiAudioFeatureInferenceResponse infer(AiAudioFeatureInferenceRequest request) {
        try {
            String payload = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(aiServiceProperties.baseUrl() + aiServiceProperties.audioFeatureInferencePath()))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() >= 400) {
                throw new ResponseStatusException(
                    BAD_GATEWAY,
                    "AI service responded with an error while inferring audio features: " + httpResponse.statusCode()
                );
            }
            return objectMapper.readValue(httpResponse.body(), AiAudioFeatureInferenceResponse.class);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(BAD_GATEWAY, "Failed to serialize or deserialize the AI audio feature payload.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(BAD_GATEWAY, "AI audio feature inference request was interrupted.", exception);
        } catch (ConnectException exception) {
            throw new ResponseStatusException(
                BAD_GATEWAY,
                "AI service is unreachable while inferring audio features. Check AI_SERVICE_BASE_URL and the FastAPI process.",
                exception
            );
        } catch (IOException exception) {
            throw new ResponseStatusException(BAD_GATEWAY, "AI service returned an unreadable audio feature inference response.", exception);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                BAD_GATEWAY,
                "AI service is unreachable. Check AI_SERVICE_BASE_URL and the FastAPI process.",
                exception
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AiAudioFeatureInferenceRequest(
        String trackScope,
        String trackId,
        String title,
        String artistName,
        Integer durationMs,
        String sourceUrl
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AiAudioFeatureInferenceEvidence(
        String sourceName,
        String sourceUrl,
        String evidenceKind,
        String evidenceText,
        double confidence
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AiAudioFeatureInferenceResponse(
        String requestId,
        Instant generatedAt,
        String service,
        String status,
        String model,
        String source,
        String sourceClass,
        double confidence,
        String modelVersion,
        boolean audioFeaturesFilled,
        Integer durationMs,
        Integer musicalKey,
        Integer mode,
        Double acousticness,
        Double danceability,
        Double energy,
        Double instrumentalness,
        Double liveness,
        Double loudness,
        Double speechiness,
        Double tempo,
        Double valence,
        String rationale,
        List<AiAudioFeatureInferenceEvidence> evidence,
        List<String> warnings
    ) {}
}
