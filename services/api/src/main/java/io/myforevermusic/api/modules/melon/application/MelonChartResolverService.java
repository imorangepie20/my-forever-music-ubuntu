package io.myforevermusic.api.modules.melon.application;

import io.myforevermusic.api.modules.melon.infrastructure.persistence.MelonChartTrackEntity;
import io.myforevermusic.api.modules.melon.infrastructure.persistence.MelonChartTrackRepository;
import io.myforevermusic.api.modules.melon.presentation.MelonResolveResponse;
import io.myforevermusic.api.modules.platform.application.PlatformCredentialService;
import io.myforevermusic.api.modules.platform.application.TidalPlaybackTargetResolverService;
import io.myforevermusic.api.modules.platform.application.TidalPlaybackTargetResolverService.TidalPlaybackTarget;
import io.myforevermusic.api.modules.platform.application.TidalPlaybackTargetResolverService.TrackQuery;
import io.myforevermusic.api.modules.platform.infrastructure.spotify.SpotifyPublicCatalogClient;
import io.myforevermusic.api.modules.platform.infrastructure.spotify.SpotifyPublicCatalogClient.PublicTrack;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves a Melon chart row into a playable track. TIDAL is preferred when
 * the caller is signed in and has a stored TIDAL credential — Spotify Client
 * Credentials are the universal fallback so anonymous visitors still get a
 * Spotify match.
 */
@org.springframework.context.annotation.Profile("!local")
@Service
public class MelonChartResolverService {

    private static final Logger log = LoggerFactory.getLogger(MelonChartResolverService.class);
    private static final int SEARCH_LIMIT = 5;

    private final MelonChartTrackRepository repository;
    private final SpotifyPublicCatalogClient spotifyPublicCatalogClient;
    private final TidalPlaybackTargetResolverService tidalResolver;
    private final PlatformCredentialService platformCredentialService;

    public MelonChartResolverService(
        MelonChartTrackRepository repository,
        SpotifyPublicCatalogClient spotifyPublicCatalogClient,
        TidalPlaybackTargetResolverService tidalResolver,
        PlatformCredentialService platformCredentialService
    ) {
        this.repository = repository;
        this.spotifyPublicCatalogClient = spotifyPublicCatalogClient;
        this.tidalResolver = tidalResolver;
        this.platformCredentialService = platformCredentialService;
    }

    public Optional<MelonResolveResponse> resolveByRank(int rank, String userId) {
        return repository.findAll().stream()
            .filter(track -> track.getRank() == rank)
            .findFirst()
            .map(track -> resolve(track, userId));
    }

    private MelonResolveResponse resolve(MelonChartTrackEntity entity, String userId) {
        boolean hasUser = userId != null && !userId.isBlank();
        boolean hasTidal = hasUser && platformCredentialService.findUsableCredential(userId, "tidal").isPresent();
        boolean hasSpotify = hasUser && platformCredentialService.findUsableCredential(userId, "spotify").isPresent();

        if (hasTidal) {
            Optional<MelonResolveResponse> tidalMatch = tryTidal(entity, userId);
            if (tidalMatch.isPresent()) {
                return tidalMatch.get();
            }
            // TIDAL connected but no match — only fall back to Spotify if user has Spotify too.
            if (!hasSpotify) {
                return unresolved(entity);
            }
        } else if (hasUser && !hasSpotify) {
            // Signed-in user without any streaming credentials — playback would fail anyway.
            return unresolved(entity);
        }
        return resolveViaSpotify(entity);
    }

    private MelonResolveResponse unresolved(MelonChartTrackEntity entity) {
        return new MelonResolveResponse(
            entity.getRank(),
            entity.getMelonSongId(),
            entity.getTitle(),
            entity.getArtistName(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            entity.getImageUrl(),
            null,
            false
        );
    }

    private Optional<MelonResolveResponse> tryTidal(MelonChartTrackEntity entity, String userId) {
        try {
            TidalPlaybackTarget target = tidalResolver.resolve(userId, new TrackQuery(
                entity.getTitle(),
                entity.getArtistName(),
                "melon",
                null,
                null,
                null,
                null,
                null
            ));
            return Optional.of(new MelonResolveResponse(
                entity.getRank(),
                entity.getMelonSongId(),
                entity.getTitle(),
                entity.getArtistName(),
                "tidal",
                null,
                target.tidalTrackId(),
                target.tidalUri(),
                target.title(),
                target.artistName(),
                target.albumTitle(),
                target.albumImageUrl() != null ? target.albumImageUrl() : entity.getImageUrl(),
                target.platformExternalUrl(),
                true
            ));
        } catch (RuntimeException ex) {
            log.debug(
                "Melon TIDAL resolve fell back to Spotify (user={}, rank={}): {}",
                userId, entity.getRank(), ex.getMessage()
            );
            return Optional.empty();
        }
    }

    private MelonResolveResponse resolveViaSpotify(MelonChartTrackEntity entity) {
        String primaryQuery = "track:\"%s\" artist:\"%s\"".formatted(
            sanitize(entity.getTitle()),
            sanitize(entity.getArtistName())
        );
        List<PublicTrack> hits = spotifyPublicCatalogClient.searchTracks(primaryQuery, SEARCH_LIMIT);
        if (hits.isEmpty()) {
            String fallback = "%s %s".formatted(sanitize(entity.getArtistName()), sanitize(entity.getTitle()));
            hits = spotifyPublicCatalogClient.searchTracks(fallback, SEARCH_LIMIT);
        }

        if (hits.isEmpty()) {
            log.debug("Melon Spotify resolve missed rank={} title='{}'", entity.getRank(), entity.getTitle());
            return new MelonResolveResponse(
                entity.getRank(),
                entity.getMelonSongId(),
                entity.getTitle(),
                entity.getArtistName(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                entity.getImageUrl(),
                null,
                false
            );
        }

        PublicTrack best = hits.get(0);
        return new MelonResolveResponse(
            entity.getRank(),
            entity.getMelonSongId(),
            entity.getTitle(),
            entity.getArtistName(),
            "spotify",
            best.spotifyTrackId(),
            null,
            null,
            best.title(),
            best.artistName(),
            best.albumTitle(),
            best.imageUrl() != null ? best.imageUrl() : entity.getImageUrl(),
            best.externalUrl(),
            true
        );
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\"", "").trim();
    }
}
