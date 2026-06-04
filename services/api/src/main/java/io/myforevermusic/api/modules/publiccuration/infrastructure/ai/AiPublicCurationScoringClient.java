package io.myforevermusic.api.modules.publiccuration.infrastructure.ai;

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
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

@Component
public class AiPublicCurationScoringClient {

    private final HttpClient httpClient;
    private final AiServiceProperties aiServiceProperties;
    private final ObjectMapper objectMapper;

    @Autowired
    public AiPublicCurationScoringClient(
        AiServiceProperties aiServiceProperties,
        ObjectMapper objectMapper
    ) {
        this(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(), aiServiceProperties, objectMapper);
    }

    AiPublicCurationScoringClient(
        HttpClient httpClient,
        AiServiceProperties aiServiceProperties,
        ObjectMapper objectMapper
    ) {
        this.httpClient = httpClient;
        this.aiServiceProperties = aiServiceProperties;
        this.objectMapper = objectMapper;
    }

    public AiPublicCurationScoreResponse score(AiPublicCurationScoreRequest request) {
        try {
            String payload = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(aiServiceProperties.baseUrl() + aiServiceProperties.publicCurationScorePath()))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() >= 400) {
                throw new ResponseStatusException(
                    BAD_GATEWAY,
                    "AI service responded with an error while scoring public curation candidates: " + httpResponse.statusCode()
                );
            }
            return objectMapper.readValue(httpResponse.body(), AiPublicCurationScoreResponse.class);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                BAD_GATEWAY,
                "Failed to serialize or deserialize the AI public curation payload.",
                exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(BAD_GATEWAY, "AI public curation scoring request was interrupted.", exception);
        } catch (ConnectException exception) {
            throw new ResponseStatusException(
                BAD_GATEWAY,
                "AI service is unreachable while scoring public curation candidates. Check AI_SERVICE_BASE_URL and the FastAPI process.",
                exception
            );
        } catch (IOException exception) {
            throw new ResponseStatusException(
                BAD_GATEWAY,
                "AI service returned an unreadable public curation response.",
                exception
            );
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                BAD_GATEWAY,
                "AI service is unreachable. Check AI_SERVICE_BASE_URL and the FastAPI process.",
                exception
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AiPublicCurationScoreRequest(
        String prompt,
        AiPublicCurationFilters filters,
        int targetTrackCount,
        List<AiPublicCurationCandidateTrack> candidateTracks
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AiPublicCurationFilters(
        List<String> moodTags,
        List<String> genreTags,
        Map<String, AudioFeatureRange> audioFeatureRanges
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AudioFeatureRange(
        Double min,
        Double max
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AiPublicCurationCandidateTrack(
        String sourceScope,
        String sourceId,
        String title,
        String artistName,
        String albumTitle,
        String imageUrl,
        Integer durationMs,
        String isrc,
        String sourcePlatform,
        String tidalTrackId,
        String tidalUri,
        String tidalExternalUrl,
        Map<String, Double> audioFeatures,
        String audioFeatureSource,
        Double audioFeatureConfidence,
        boolean audioFeaturesFilled,
        List<String> genres,
        List<String> tags,
        List<String> metadataTags,
        SourcePlaylistSignals sourcePlaylistSignals,
        AudienceResponse audienceResponse,
        Double popularity,
        Double freshness,
        String playbackResolutionStatus
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SourcePlaylistSignals(
        int playlistCount,
        Integer maxFollowersCount,
        List<String> titles,
        List<String> descriptions,
        List<String> curators,
        List<String> collectionSources,
        List<String> searchQueries
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AudienceResponse(
        int playStartedCount,
        int playCompletedCount,
        int skipCount
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AiPublicCurationScoreResponse(
        Instant generatedAt,
        String service,
        String status,
        String modelVersion,
        String title,
        String subtitle,
        String description,
        List<AiPublicCurationSelectedTrack> tracks,
        Map<String, Object> scoreSummary,
        String semanticProfileStatus,
        String semanticProfileModel,
        Map<String, Object> semanticProfile,
        List<String> warnings
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AiPublicCurationSelectedTrack(
        int order,
        String sourceScope,
        String sourceTrackId,
        String title,
        String artistName,
        String albumTitle,
        String imageUrl,
        Integer durationMs,
        String isrc,
        String tidalTrackId,
        String tidalUri,
        String tidalExternalUrl,
        double score,
        Map<String, Double> scoreBreakdown,
        String reason
    ) {
    }
}
