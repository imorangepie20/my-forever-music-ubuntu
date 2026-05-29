package io.myforevermusic.api.modules.publiccuration.application;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.common.error.ApiResourceNotFoundException;
import io.myforevermusic.api.modules.platform.application.PlatformAccountCredential;
import io.myforevermusic.api.modules.platform.application.TidalPlaybackStreamService;
import io.myforevermusic.api.modules.platform.application.TidalPlaybackStreamService.TidalPlaybackStream;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class PublicCurationPlaybackStreamService {

    private final PublicCurationPlaylistStore playlistStore;
    private final PublicPlaybackSessionStore sessionStore;
    private final TidalPlaybackStreamService tidalPlaybackStreamService;

    public PublicCurationPlaybackStreamService(
        PublicCurationPlaylistStore playlistStore,
        PublicPlaybackSessionStore sessionStore,
        TidalPlaybackStreamService tidalPlaybackStreamService
    ) {
        this.playlistStore = playlistStore;
        this.sessionStore = sessionStore;
        this.tidalPlaybackStreamService = tidalPlaybackStreamService;
    }

    public PublicStreamResponse stream(
        String slug,
        String publicSessionId,
        Long publicTrackId,
        String quality
    ) {
        PublicCurationPlaylistStore.StoredPlaylist playlist = playlistStore.findPublishedBySlug(slug)
            .orElseThrow(() -> new ApiResourceNotFoundException("Published public curation playlist was not found."));
        PublicPlaybackSessionStore.StoredSession session = sessionStore.findActiveBySessionId(publicSessionId, Instant.now())
            .orElseThrow(() -> new ApiResourceNotFoundException("Active public playback session was not found."));
        if (!playlist.playlistId().equals(session.playlistId())) {
            throw new IllegalArgumentException("Public playback session does not belong to the requested playlist.");
        }

        PublicCurationPlaylistStore.StoredTrack track = playlist.tracks().stream()
            .filter(candidate -> candidate.trackId().equals(publicTrackId))
            .findFirst()
            .orElseThrow(() -> new ApiResourceNotFoundException("Public curation track was not found in this playlist."));
        if (track.tidalTrackId() == null || track.tidalTrackId().isBlank()) {
            throw new IllegalArgumentException("Public curation track does not have a TIDAL playback target.");
        }

        TidalPlaybackStream stream = tidalPlaybackStreamService.resolve(
            toCredential(session),
            track.tidalTrackId(),
            quality
        );

        return new PublicStreamResponse(
            "public-curation-playback-stream",
            "ok",
            Instant.now(),
            playlist.playlistId(),
            session.sessionId(),
            track.trackId(),
            track.tidalTrackId(),
            stream.countryCode(),
            stream.requestedQuality(),
            stream.audioQuality(),
            stream.codec(),
            stream.bitRate(),
            stream.sampleRate(),
            stream.bitDepth(),
            stream.assetPresentation(),
            stream.manifestMimeType(),
            stream.manifestCodecs(),
            stream.encryptionType(),
            stream.durationSeconds(),
            stream.streamUrl()
        );
    }

    private PlatformAccountCredential toCredential(PublicPlaybackSessionStore.StoredSession session) {
        return new PlatformAccountCredential(
            "public-curation:%s".formatted(session.playlistId()),
            "tidal",
            "public-curation-tidal-oauth",
            "public-curation-tidal",
            session.tidalAccountLabel(),
            session.accessToken(),
            session.refreshToken(),
            "Bearer",
            session.scopeSummary(),
            session.expiresAt(),
            session.createdAt(),
            session.lastUsedAt() == null ? session.createdAt() : session.lastUsedAt()
        );
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PublicStreamResponse(
        String service,
        String status,
        Instant generatedAt,
        Long playlistId,
        String publicSessionId,
        Long trackId,
        String tidalTrackId,
        String countryCode,
        String requestedQuality,
        String audioQuality,
        String codec,
        Integer bitRate,
        Integer sampleRate,
        Integer bitDepth,
        String assetPresentation,
        String manifestMimeType,
        String manifestCodecs,
        String encryptionType,
        Double durationSeconds,
        String streamUrl
    ) {
    }
}
