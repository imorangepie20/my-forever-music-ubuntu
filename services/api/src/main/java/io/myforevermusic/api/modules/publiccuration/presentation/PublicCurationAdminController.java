package io.myforevermusic.api.modules.publiccuration.presentation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.modules.publiccuration.application.PublicCurationCandidatePoolStore;
import io.myforevermusic.api.modules.publiccuration.application.PublicCurationGenerationService;
import io.myforevermusic.api.modules.publiccuration.application.PublicCurationPlaylistStore;
import io.myforevermusic.api.modules.publiccuration.infrastructure.ai.AiPublicCurationScoringClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/public-curations/admin")
public class PublicCurationAdminController {

    private final PublicCurationCandidatePoolStore candidatePoolStore;
    private final PublicCurationGenerationService generationService;
    private final PublicCurationPlaylistStore playlistStore;
    private final ObjectMapper objectMapper;

    public PublicCurationAdminController(
        PublicCurationCandidatePoolStore candidatePoolStore,
        PublicCurationGenerationService generationService,
        PublicCurationPlaylistStore playlistStore,
        ObjectMapper objectMapper
    ) {
        this.candidatePoolStore = candidatePoolStore;
        this.generationService = generationService;
        this.playlistStore = playlistStore;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/runs")
    public GenerateDraftResponse generateDraft(@RequestBody GenerateDraftRequest request) {
        int candidateLimit = request.candidateLimit() == null ? 200 : request.candidateLimit();
        int targetTrackCount = request.targetTrackCount() == null ? 30 : request.targetTrackCount();
        List<PublicCurationCandidatePoolStore.CandidateTrack> candidates = candidatePoolStore.findCandidates(
            new PublicCurationCandidatePoolStore.CandidateQuery(candidateLimit, true)
        );
        PublicCurationPlaylistStore.StoredPlaylist playlist = generationService.generateDraft(
            new PublicCurationGenerationService.GenerateDraftCommand(
                request.slug(),
                request.prompt(),
                writeFilterSnapshot(request),
                toAiFilters(request.filters()),
                targetTrackCount,
                request.coverStyle(),
                request.adminUserId(),
                Instant.now(),
                candidates.stream().map(this::toAiCandidate).toList()
            )
        );

        return new GenerateDraftResponse(
            "public-curation-admin",
            "draft_created",
            StoredPlaylistResponse.from(playlist)
        );
    }

    @PostMapping("/playlists/{playlistId}/publish")
    public GenerateDraftResponse publish(@PathVariable Long playlistId) {
        PublicCurationPlaylistStore.StoredPlaylist playlist = playlistStore.publish(playlistId, Instant.now());
        return new GenerateDraftResponse(
            "public-curation-admin",
            "published",
            StoredPlaylistResponse.from(playlist)
        );
    }

    private AiPublicCurationScoringClient.AiPublicCurationFilters toAiFilters(FilterRequest filters) {
        if (filters == null) {
            return new AiPublicCurationScoringClient.AiPublicCurationFilters(List.of(), List.of(), Map.of());
        }
        Map<String, AiPublicCurationScoringClient.AudioFeatureRange> ranges = filters.audioFeatureRanges() == null
            ? Map.of()
            : filters.audioFeatureRanges().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> new AiPublicCurationScoringClient.AudioFeatureRange(
                        entry.getValue().min(),
                        entry.getValue().max()
                    )
                ));
        return new AiPublicCurationScoringClient.AiPublicCurationFilters(
            filters.moodTags() == null ? List.of() : filters.moodTags(),
            filters.genreTags() == null ? List.of() : filters.genreTags(),
            ranges
        );
    }

    private AiPublicCurationScoringClient.AiPublicCurationCandidateTrack toAiCandidate(
        PublicCurationCandidatePoolStore.CandidateTrack candidate
    ) {
        return new AiPublicCurationScoringClient.AiPublicCurationCandidateTrack(
            candidate.sourceScope(),
            candidate.sourceId(),
            candidate.title(),
            candidate.artistName(),
            candidate.albumTitle(),
            candidate.durationMs(),
            candidate.isrc(),
            candidate.sourcePlatform(),
            candidate.tidalTrackId(),
            candidate.tidalUri(),
            candidate.tidalExternalUrl(),
            candidate.audioFeatures(),
            candidate.genres(),
            candidate.tags(),
            null,
            null
        );
    }

    private String writeFilterSnapshot(GenerateDraftRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Public curation filters could not be serialized.",
                exception
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GenerateDraftRequest(
        String adminUserId,
        String slug,
        String prompt,
        Integer targetTrackCount,
        Integer candidateLimit,
        String coverStyle,
        FilterRequest filters
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FilterRequest(
        List<String> moodTags,
        List<String> genreTags,
        Map<String, AudioFeatureRangeRequest> audioFeatureRanges
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AudioFeatureRangeRequest(
        Double min,
        Double max
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GenerateDraftResponse(
        String service,
        String status,
        StoredPlaylistResponse playlist
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StoredPlaylistResponse(
        Long playlistId,
        String slug,
        String title,
        String subtitle,
        String status,
        int trackCount,
        long durationMs,
        String modelVersion,
        List<StoredTrackResponse> tracks
    ) {
        static StoredPlaylistResponse from(PublicCurationPlaylistStore.StoredPlaylist playlist) {
            return new StoredPlaylistResponse(
                playlist.playlistId(),
                playlist.slug(),
                playlist.title(),
                playlist.subtitle(),
                playlist.status(),
                playlist.trackCount(),
                playlist.durationMs(),
                playlist.modelVersion(),
                playlist.tracks().stream()
                    .map(StoredTrackResponse::from)
                    .toList()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StoredTrackResponse(
        Long trackId,
        int trackOrder,
        String title,
        String artistName,
        String albumTitle,
        Integer durationMs,
        String tidalTrackId,
        String tidalUri,
        String tidalExternalUrl,
        double score,
        String reason
    ) {
        static StoredTrackResponse from(PublicCurationPlaylistStore.StoredTrack track) {
            return new StoredTrackResponse(
                track.trackId(),
                track.trackOrder(),
                track.title(),
                track.artistName(),
                track.albumTitle(),
                track.durationMs(),
                track.tidalTrackId(),
                track.tidalUri(),
                track.tidalExternalUrl(),
                track.score(),
                track.reason()
            );
        }
    }
}
