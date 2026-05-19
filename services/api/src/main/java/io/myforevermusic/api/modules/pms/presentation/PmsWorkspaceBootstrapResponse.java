package io.myforevermusic.api.modules.pms.presentation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PmsWorkspaceBootstrapResponse(
    String service,
    String status,
    Instant generatedAt,
    WorkspaceDefaults workspaceDefaults,
    List<PlaylistOption> playlists,
    List<TrackSeedSuggestion> suggestedTracks,
    List<ArtistSeedSuggestion> suggestedArtists,
    List<GenreSeedSuggestion> suggestedGenres
) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WorkspaceDefaults(
        String userId,
        String playlistId,
        List<String> seedTrackIds,
        List<String> seedArtistNames,
        List<String> seedGenres
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PlaylistOption(
        String playlistId,
        String title,
        String sourcePlatform,
        Integer trackCount,
        String curator,
        String highlight,
        String coverImageUrl,
        String platformExternalUrl,
        String platformUri,
        String sourceCollection
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TrackSeedSuggestion(
        String trackId,
        String title,
        String artistName,
        String sourcePlatform,
        String albumTitle,
        String albumImageUrl,
        String platformExternalUrl,
        String platformUri,
        String previewUrl,
        Integer durationMs,
        boolean seed,
        String isrc,
        String spotifyTrackId,
        String spotifyUri,
        String tidalTrackId,
        String tidalUri,
        String preferredPlaybackPlatform,
        String playbackTargetStatus,
        String audioFeatureTrackId,
        boolean spotifyAudioFeaturesFilled,
        boolean audioFeaturesFilled,
        String spotifyAudioFeatureSource,
        String audioFeatureSource
    ) {
        public TrackSeedSuggestion(
            String trackId,
            String title,
            String artistName,
            String sourcePlatform,
            String albumTitle,
            String albumImageUrl,
            String platformExternalUrl,
            String platformUri,
            String previewUrl,
            Integer durationMs,
            boolean seed,
            String spotifyTrackId,
            String audioFeatureTrackId,
            boolean spotifyAudioFeaturesFilled,
            boolean audioFeaturesFilled,
            String spotifyAudioFeatureSource,
            String audioFeatureSource
        ) {
            this(
                trackId,
                title,
                artistName,
                sourcePlatform,
                albumTitle,
                albumImageUrl,
                platformExternalUrl,
                platformUri,
                previewUrl,
                durationMs,
                seed,
                null,
                spotifyTrackId,
                spotifyTrackId == null ? null : "spotify:track:%s".formatted(spotifyTrackId),
                null,
                null,
                "spotify",
                spotifyTrackId == null || spotifyTrackId.isBlank() ? "unresolved" : "resolved",
                audioFeatureTrackId,
                spotifyAudioFeaturesFilled,
                audioFeaturesFilled,
                spotifyAudioFeatureSource,
                audioFeatureSource
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ArtistSeedSuggestion(
        String artistName,
        Double affinityScore,
        String reason
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GenreSeedSuggestion(
        String genre,
        Double weight,
        String reason
    ) {
    }
}
