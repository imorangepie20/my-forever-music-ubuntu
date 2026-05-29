package io.myforevermusic.api.modules.publiccuration.presentation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.modules.publiccuration.application.PublicCurationPlaylistStore;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/public-curations/share")
public class PublicCurationShareController {

    private final PublicCurationPlaylistStore playlistStore;

    public PublicCurationShareController(PublicCurationPlaylistStore playlistStore) {
        this.playlistStore = playlistStore;
    }

    @GetMapping("/{slug}")
    public SharePlaylistResponse findPublishedPlaylist(@PathVariable String slug) {
        PublicCurationPlaylistStore.StoredPlaylist playlist = playlistStore.findPublishedBySlug(slug)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Published public curation playlist was not found."
            ));
        return new SharePlaylistResponse(
            "public-curation-share",
            playlist.status(),
            SharePlaylist.from(playlist)
        );
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SharePlaylistResponse(
        String service,
        String status,
        SharePlaylist playlist
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SharePlaylist(
        Long playlistId,
        String slug,
        String title,
        String subtitle,
        String description,
        String coverStyle,
        String modelVersion,
        int trackCount,
        long durationMs,
        Instant publishedAt,
        List<ShareTrack> tracks
    ) {
        static SharePlaylist from(PublicCurationPlaylistStore.StoredPlaylist playlist) {
            return new SharePlaylist(
                playlist.playlistId(),
                playlist.slug(),
                playlist.title(),
                playlist.subtitle(),
                playlist.description(),
                playlist.coverStyle(),
                playlist.modelVersion(),
                playlist.trackCount(),
                playlist.durationMs(),
                playlist.publishedAt(),
                playlist.tracks().stream()
                    .map(ShareTrack::from)
                    .toList()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ShareTrack(
        Long trackId,
        int trackOrder,
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
        String scoreBreakdownJson,
        String reason
    ) {
        static ShareTrack from(PublicCurationPlaylistStore.StoredTrack track) {
            return new ShareTrack(
                track.trackId(),
                track.trackOrder(),
                track.title(),
                track.artistName(),
                track.albumTitle(),
                track.imageUrl(),
                track.durationMs(),
                track.isrc(),
                track.tidalTrackId(),
                track.tidalUri(),
                track.tidalExternalUrl(),
                track.score(),
                track.scoreBreakdownJson(),
                track.reason()
            );
        }
    }
}
