package io.myforevermusic.api.modules.mainpage.application;

import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistRepository;
import io.myforevermusic.api.modules.platform.infrastructure.spotify.SpotifyPublicCatalogClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refreshes the follower-count signal on already-collected Spotify playlists.
 * The EMS acquisition pipeline does not currently capture follower data, so
 * this admin-triggered service does the backfill via SpotifyPublicCatalogClient
 * (Client Credentials).
 */
@org.springframework.context.annotation.Profile("!local")
@Service
public class PlaylistPopularityRefreshService {

    private static final Logger log = LoggerFactory.getLogger(PlaylistPopularityRefreshService.class);
    private static final Duration STALE_AFTER = Duration.ofDays(7);
    private static final int MAX_BATCH = 200;

    private final EmsCollectedPlaylistRepository playlistRepository;
    private final SpotifyPublicCatalogClient spotifyPublicCatalogClient;
    private final Clock clock;

    @Autowired
    public PlaylistPopularityRefreshService(
        EmsCollectedPlaylistRepository playlistRepository,
        SpotifyPublicCatalogClient spotifyPublicCatalogClient
    ) {
        this(playlistRepository, spotifyPublicCatalogClient, Clock.systemUTC());
    }

    PlaylistPopularityRefreshService(
        EmsCollectedPlaylistRepository playlistRepository,
        SpotifyPublicCatalogClient spotifyPublicCatalogClient,
        Clock clock
    ) {
        this.playlistRepository = playlistRepository;
        this.spotifyPublicCatalogClient = spotifyPublicCatalogClient;
        this.clock = clock;
    }

    public record RefreshResult(int considered, int refreshed, int unchanged, int skipped) {
    }

    @Transactional
    public RefreshResult refreshSpotify(int limit) {
        int effectiveLimit = Math.min(MAX_BATCH, Math.max(1, limit));
        Instant now = clock.instant();
        Instant staleBefore = now.minus(STALE_AFTER);
        List<EmsCollectedPlaylistEntity> stale = playlistRepository
            .findStalePopularityCandidates("spotify", staleBefore, PageRequest.of(0, effectiveLimit));

        int refreshed = 0;
        int unchanged = 0;
        int skipped = 0;
        for (EmsCollectedPlaylistEntity playlist : stale) {
            String spotifyId = extractSpotifyPlaylistId(playlist);
            if (spotifyId == null) {
                skipped += 1;
                continue;
            }
            Optional<Integer> followers = spotifyPublicCatalogClient.getPlaylistFollowers(spotifyId);
            if (followers.isEmpty()) {
                skipped += 1;
                continue;
            }
            Integer current = playlist.getFollowersCount();
            playlist.applyPopularity(followers.get(), now);
            if (current != null && current.equals(followers.get())) {
                unchanged += 1;
            } else {
                refreshed += 1;
            }
        }
        log.info(
            "Playlist popularity refresh: considered={} refreshed={} unchanged={} skipped={}",
            stale.size(), refreshed, unchanged, skipped
        );
        return new RefreshResult(stale.size(), refreshed, unchanged, skipped);
    }

    private String extractSpotifyPlaylistId(EmsCollectedPlaylistEntity playlist) {
        if ("spotify".equalsIgnoreCase(playlist.getSourcePlatform())
            && playlist.getExternalPlaylistId() != null
            && !playlist.getExternalPlaylistId().isBlank()) {
            return playlist.getExternalPlaylistId();
        }
        String uri = playlist.getSpotifyUri();
        if (uri == null || !uri.startsWith("spotify:playlist:")) {
            return null;
        }
        String id = uri.substring("spotify:playlist:".length()).trim();
        return id.isEmpty() ? null : id;
    }
}
