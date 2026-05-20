package io.myforevermusic.api.modules.artist.presentation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ArtistDetailResponse(
    String service,
    String status,
    Instant generatedAt,
    ArtistProfile artist,
    ArtistSummary summary,
    List<PlatformCandidate> platformCandidates,
    List<ArtistTrack> pmsTracks,
    List<ArtistTrack> emsTracks
) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ArtistProfile(
        String artistSlug,
        String displayName,
        String imageUrl,
        String matchReason
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ArtistSummary(
        int pmsTrackCount,
        int emsTrackCount,
        int totalTrackCount,
        int platformCandidateCount
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PlatformCandidate(
        String provider,
        String externalArtistId,
        String name,
        String imageUrl,
        String externalUrl,
        String uri,
        Long followersOrSubscribers,
        Integer popularity,
        String matchSource
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ArtistTrack(
        String trackId,
        String title,
        String artistName,
        String sourcePlatform,
        String albumTitle,
        String albumImageUrl,
        String platformExternalUrl,
        String platformUri,
        String previewUrl,
        String isrc,
        Integer durationMs,
        String collectionSource
    ) {}
}
