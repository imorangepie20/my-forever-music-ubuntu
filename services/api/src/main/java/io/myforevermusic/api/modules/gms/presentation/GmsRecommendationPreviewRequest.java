package io.myforevermusic.api.modules.gms.presentation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GmsRecommendationPreviewRequest(
    String requestId,
    String userId,
    String playlistId,
    @Pattern(regexp = "gms") String mode,
    @Pattern(regexp = "focus|calm|upbeat|melancholy|discovery") String mood,
    @Min(1) @Max(5) Integer energyLevel,
    @Min(1) @Max(5) Integer familiarityBias,
    @Min(1) @Max(50) Integer limit,
    List<String> seedTrackIds,
    List<String> seedArtistNames,
    List<String> seedGenres,
    Boolean includeExplanations
) {

    public GmsRecommendationPreviewRequest {
        mode = (mode == null || mode.isBlank()) ? "gms" : mode;
        familiarityBias = familiarityBias == null ? 3 : familiarityBias;
        limit = limit == null ? 10 : limit;
        seedTrackIds = seedTrackIds == null ? List.of() : List.copyOf(seedTrackIds);
        seedArtistNames = seedArtistNames == null ? List.of() : List.copyOf(seedArtistNames);
        seedGenres = seedGenres == null ? List.of() : List.copyOf(seedGenres);
        includeExplanations = includeExplanations == null ? Boolean.TRUE : includeExplanations;
    }
}
