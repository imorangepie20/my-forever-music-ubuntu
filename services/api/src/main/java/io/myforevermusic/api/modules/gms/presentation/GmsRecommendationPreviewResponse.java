package io.myforevermusic.api.modules.gms.presentation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.modules.recommendation.application.AudioTasteModeAffinityService;
import io.myforevermusic.api.modules.recommendation.application.AxisEvidence;
import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GmsRecommendationPreviewResponse(
    String requestId,
    Instant generatedAt,
    String service,
    String status,
    RecommendationContext context,
    RecommendationInputSummary inputSummary,
    List<RecommendationItem> items,
    List<String> warnings
) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RecommendationContext(
        String strategy,
        String engine,
        String mode,
        String mood,
        Integer energyLevel,
        List<String> seedBasis
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RecommendationInputSummary(
        String userId,
        String playlistId,
        Integer trackSeedCount,
        Integer artistSeedCount,
        Integer genreSeedCount,
        Integer familiarityBias,
        Integer limit
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RecommendationItem(
        Integer rank,
        String trackId,
        String title,
        String artistName,
        String sourcePlatform,
        String sourcePlaylistId,
        String sourcePlaylistTitle,
        String albumTitle,
        String albumImageUrl,
        String platformExternalUrl,
        String platformUri,
        String previewUrl,
        String spotifyTrackId,
        String audioFeatureTrackId,
        Integer durationMs,
        Double score,
        String sourceSpace,
        Integer energyLevel,
        String reason,
        List<AxisEvidence> axisEvidence,
        TasteModeAffinityItem tasteModeAffinity
    ) {
        public RecommendationItem {
            if (
                (audioFeatureTrackId == null || audioFeatureTrackId.isBlank())
                    && spotifyTrackId != null
                    && !spotifyTrackId.isBlank()
            ) {
                audioFeatureTrackId = spotifyTrackId;
            }
            if (
                (spotifyTrackId == null || spotifyTrackId.isBlank())
                    && audioFeatureTrackId != null
                    && !audioFeatureTrackId.isBlank()
            ) {
                spotifyTrackId = audioFeatureTrackId;
            }
            if (axisEvidence == null) {
                axisEvidence = List.of();
            }
        }

        public RecommendationItem(
            Integer rank,
            String trackId,
            String title,
            String artistName,
            String sourcePlatform,
            String sourcePlaylistId,
            String sourcePlaylistTitle,
            String albumTitle,
            String albumImageUrl,
            String platformExternalUrl,
            String platformUri,
            String previewUrl,
            String spotifyTrackId,
            String audioFeatureTrackId,
            Integer durationMs,
            Double score,
            String sourceSpace,
            Integer energyLevel,
            String reason,
            List<AxisEvidence> axisEvidence
        ) {
            this(
                rank,
                trackId,
                title,
                artistName,
                sourcePlatform,
                sourcePlaylistId,
                sourcePlaylistTitle,
                albumTitle,
                albumImageUrl,
                platformExternalUrl,
                platformUri,
                previewUrl,
                spotifyTrackId,
                audioFeatureTrackId,
                durationMs,
                score,
                sourceSpace,
                energyLevel,
                reason,
                axisEvidence,
                null
            );
        }

        public RecommendationItem(
            Integer rank,
            String trackId,
            String title,
            String artistName,
            String sourcePlatform,
            String sourcePlaylistId,
            String sourcePlaylistTitle,
            String albumTitle,
            String albumImageUrl,
            String platformExternalUrl,
            String platformUri,
            String previewUrl,
            String spotifyTrackId,
            Integer durationMs,
            Double score,
            String sourceSpace,
            Integer energyLevel,
            String reason
        ) {
            this(
                rank,
                trackId,
                title,
                artistName,
                sourcePlatform,
                sourcePlaylistId,
                sourcePlaylistTitle,
                albumTitle,
                albumImageUrl,
                platformExternalUrl,
                platformUri,
                previewUrl,
                spotifyTrackId,
                spotifyTrackId,
                durationMs,
                score,
                sourceSpace,
                energyLevel,
                reason,
                List.of(),
                null
            );
        }

        public RecommendationItem(
            Integer rank,
            String trackId,
            String title,
            String artistName,
            String sourcePlatform,
            String sourcePlaylistId,
            String sourcePlaylistTitle,
            String albumTitle,
            String albumImageUrl,
            String platformExternalUrl,
            String platformUri,
            String previewUrl,
            String spotifyTrackId,
            String audioFeatureTrackId,
            Integer durationMs,
            Double score,
            String sourceSpace,
            Integer energyLevel,
            String reason
        ) {
            this(
                rank,
                trackId,
                title,
                artistName,
                sourcePlatform,
                sourcePlaylistId,
                sourcePlaylistTitle,
                albumTitle,
                albumImageUrl,
                platformExternalUrl,
                platformUri,
                previewUrl,
                spotifyTrackId,
                audioFeatureTrackId,
                durationMs,
                score,
                sourceSpace,
                energyLevel,
                reason,
                List.of(),
                null
            );
        }

        public RecommendationItem withAxisEvidence(List<AxisEvidence> evidence) {
            return new RecommendationItem(
                rank,
                trackId,
                title,
                artistName,
                sourcePlatform,
                sourcePlaylistId,
                sourcePlaylistTitle,
                albumTitle,
                albumImageUrl,
                platformExternalUrl,
                platformUri,
                previewUrl,
                spotifyTrackId,
                audioFeatureTrackId,
                durationMs,
                score,
                sourceSpace,
                energyLevel,
                reason,
                evidence == null ? List.of() : evidence,
                tasteModeAffinity
            );
        }

        public RecommendationItem withTasteModeAffinity(TasteModeAffinityItem affinity) {
            return new RecommendationItem(
                rank,
                trackId,
                title,
                artistName,
                sourcePlatform,
                sourcePlaylistId,
                sourcePlaylistTitle,
                albumTitle,
                albumImageUrl,
                platformExternalUrl,
                platformUri,
                previewUrl,
                spotifyTrackId,
                audioFeatureTrackId,
                durationMs,
                score,
                sourceSpace,
                energyLevel,
                reason,
                axisEvidence,
                affinity
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TasteModeAffinityItem(
        Boolean applied,
        String modeId,
        String label,
        Double similarity,
        Double distance,
        List<String> tokens
    ) {
        public TasteModeAffinityItem {
            tokens = tokens == null ? List.of() : List.copyOf(tokens);
        }

        public static TasteModeAffinityItem from(AudioTasteModeAffinityService.TasteModeAffinity affinity) {
            if (affinity == null) {
                return null;
            }
            return new TasteModeAffinityItem(
                affinity.applied(),
                affinity.modeId(),
                affinity.label(),
                affinity.similarity(),
                affinity.distance(),
                affinity.tokens()
            );
        }
    }
}
