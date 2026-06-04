package io.myforevermusic.api.modules.gms.infrastructure.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.gms.presentation.GmsRecommendationPreviewRequest;
import io.myforevermusic.api.modules.gms.presentation.GmsRecommendationPreviewResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

@Component
public class AiRecommendationPreviewClient {

    private final HttpClient httpClient;
    private final AiServiceProperties aiServiceProperties;
    private final ObjectMapper objectMapper;

    public AiRecommendationPreviewClient(
        AiServiceProperties aiServiceProperties,
        ObjectMapper objectMapper
    ) {
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        this.aiServiceProperties = aiServiceProperties;
        this.objectMapper = objectMapper;
    }

    public GmsRecommendationPreviewResponse requestPreview(GmsRecommendationPreviewRequest request) {
        try {
            String payload = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(aiServiceProperties.baseUrl() + aiServiceProperties.recommendationPreviewPath()))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() >= 400) {
                throw new ResponseStatusException(
                    BAD_GATEWAY,
                    "AI service responded with an error while generating a preview: "
                        + httpResponse.statusCode()
                        + responseBodyDetail(httpResponse.body())
                );
            }

            GmsRecommendationPreviewResponse response = objectMapper.readValue(
                httpResponse.body(),
                GmsRecommendationPreviewResponse.class
            );

            if (response == null) {
                throw new ResponseStatusException(BAD_GATEWAY, "AI service returned an empty preview response.");
            }

            return response;
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(
                BAD_GATEWAY,
                "Failed to serialize or deserialize the AI preview payload.",
                ex
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(
                BAD_GATEWAY,
                "AI preview request was interrupted.",
                ex
            );
        } catch (IOException ex) {
            throw new ResponseStatusException(
                BAD_GATEWAY,
                "AI service returned an unreadable preview response.",
                ex
            );
        } catch (IllegalArgumentException | RestClientException ex) {
            throw new ResponseStatusException(
                BAD_GATEWAY,
                "AI service is unreachable. Check AI_SERVICE_BASE_URL and the FastAPI process.",
                ex
            );
        }
    }

    private String responseBodyDetail(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }

        String normalized = responseBody.replaceAll("\\s+", " ").trim();
        String truncated = normalized.length() > 800
            ? normalized.substring(0, 800) + "..."
            : normalized;
        return ". Body: " + truncated;
    }
}
