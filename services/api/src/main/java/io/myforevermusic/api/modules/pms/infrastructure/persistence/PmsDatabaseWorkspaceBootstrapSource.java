package io.myforevermusic.api.modules.pms.infrastructure.persistence;

import io.myforevermusic.api.modules.pms.application.PmsWorkspaceBootstrapSource;
import io.myforevermusic.api.modules.pms.presentation.PmsWorkspaceBootstrapResponse;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(0)
@ConditionalOnBean(DataSource.class)
public class PmsDatabaseWorkspaceBootstrapSource implements PmsWorkspaceBootstrapSource {

    private static final int TRACK_SUGGESTION_LIMIT = 8;
    private static final int SIGNAL_SUGGESTION_LIMIT = 6;

    private final PmsCatalogPlaylistRepository playlistRepository;
    private final PmsCatalogPlaylistTrackRepository playlistTrackRepository;

    public PmsDatabaseWorkspaceBootstrapSource(
        PmsCatalogPlaylistRepository playlistRepository,
        PmsCatalogPlaylistTrackRepository playlistTrackRepository
    ) {
        this.playlistRepository = playlistRepository;
        this.playlistTrackRepository = playlistTrackRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PmsWorkspaceBootstrapResponse> load(String userId, String playlistId) {
        if (!hasText(userId)) {
            return Optional.empty();
        }

        List<PmsCatalogPlaylistEntity> playlists = playlistRepository.findAllByOwnerUserIdOrderByDisplayOrderAscIdAsc(userId);
        if (playlists.isEmpty()) {
            return Optional.empty();
        }

        PmsCatalogPlaylistEntity defaultPlaylist = playlists.stream()
            .filter(playlist -> hasText(playlistId) && playlist.getId().equals(playlistId))
            .findFirst()
            .orElse(playlists.getFirst());
        List<PmsCatalogPlaylistTrackEntity> playlistTracks =
            playlistTrackRepository.findByPlaylist_IdOrderBySortOrderAscIdAsc(defaultPlaylist.getId());

        if (playlistTracks.isEmpty()) {
            return Optional.empty();
        }

        List<PmsCatalogPlaylistTrackEntity> seedTracks = playlistTracks.stream()
            .filter(PmsCatalogPlaylistTrackEntity::isSeed)
            .toList();

        if (seedTracks.isEmpty()) {
            seedTracks = playlistTracks.stream()
                .limit(Math.min(2, playlistTracks.size()))
                .toList();
        }

        return Optional.of(
            new PmsWorkspaceBootstrapResponse(
                "api",
                "ok",
                Instant.now(),
                new PmsWorkspaceBootstrapResponse.WorkspaceDefaults(
                    defaultPlaylist.getOwnerUserId(),
                    defaultPlaylist.getId(),
                    seedTracks.stream()
                        .map(track -> track.getTrack().getId())
                        .toList(),
                    distinctSeedArtists(seedTracks),
                    distinctSeedGenres(seedTracks)
                ),
                playlists.stream()
                    .map(this::toPlaylistOption)
                    .toList(),
                playlistTracks.stream()
                    .limit(TRACK_SUGGESTION_LIMIT)
                    .map(this::toTrackSuggestion)
                    .toList(),
                toArtistSuggestions(playlistTracks),
                toGenreSuggestions(playlistTracks)
            )
        );
    }

    private PmsWorkspaceBootstrapResponse.PlaylistOption toPlaylistOption(PmsCatalogPlaylistEntity playlist) {
        return new PmsWorkspaceBootstrapResponse.PlaylistOption(
            playlist.getId(),
            playlist.getTitle(),
            playlist.getSourcePlatform(),
            playlist.getTrackCount(),
            playlist.getCurator(),
            playlist.getHighlight(),
            null,
            null,
            null,
            "pms-catalog-playlist"
        );
    }

    private PmsWorkspaceBootstrapResponse.TrackSeedSuggestion toTrackSuggestion(PmsCatalogPlaylistTrackEntity playlistTrack) {
        PmsCatalogTrackEntity track = playlistTrack.getTrack();

        return new PmsWorkspaceBootstrapResponse.TrackSeedSuggestion(
            track.getId(),
            track.getTitle(),
            track.getArtistName(),
            track.getSourcePlatform(),
            null,
            null,
            null,
            track.getAudioFeatures().getSpotifyUri(),
            null,
            track.getAudioFeatures().getDurationMs(),
            playlistTrack.isSeed(),
            null,
            track.getAudioFeatureTrackId(),
            track.getAudioFeatures().getSpotifyUri(),
            null,
            null,
            "spotify",
            track.isAudioFeaturesFilled() ? "resolved" : "unresolved",
            track.getAudioFeatureTrackId(),
            track.isAudioFeaturesFilled(),
            track.isAudioFeaturesFilled(),
            track.getAudioFeatureSource(),
            track.getAudioFeatureSource()
        );
    }

    private List<PmsWorkspaceBootstrapResponse.ArtistSeedSuggestion> toArtistSuggestions(
        List<PmsCatalogPlaylistTrackEntity> playlistTracks
    ) {
        Map<String, Long> artistCounts = playlistTracks.stream()
            .map(track -> track.getTrack().getArtistName())
            .filter(this::hasText)
            .collect(Collectors.groupingBy(artist -> artist, Collectors.counting()));

        long maxCount = artistCounts.values().stream()
            .mapToLong(Long::longValue)
            .max()
            .orElse(1L);

        return artistCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
            .limit(SIGNAL_SUGGESTION_LIMIT)
            .map(entry -> new PmsWorkspaceBootstrapResponse.ArtistSeedSuggestion(
                entry.getKey(),
                roundScore(scaleSignal(entry.getValue(), maxCount)),
                artistReason(entry.getValue())
            ))
            .toList();
    }

    private List<PmsWorkspaceBootstrapResponse.GenreSeedSuggestion> toGenreSuggestions(
        List<PmsCatalogPlaylistTrackEntity> playlistTracks
    ) {
        Map<String, Long> genreCounts = playlistTracks.stream()
            .map(track -> track.getTrack().getPrimaryGenre())
            .filter(this::hasText)
            .collect(Collectors.groupingBy(genre -> genre, Collectors.counting()));

        long maxCount = genreCounts.values().stream()
            .mapToLong(Long::longValue)
            .max()
            .orElse(1L);

        return genreCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
            .limit(SIGNAL_SUGGESTION_LIMIT)
            .map(entry -> new PmsWorkspaceBootstrapResponse.GenreSeedSuggestion(
                entry.getKey(),
                roundScore(scaleSignal(entry.getValue(), maxCount)),
                genreReason(entry.getValue())
            ))
            .toList();
    }

    private List<String> distinctSeedArtists(List<PmsCatalogPlaylistTrackEntity> seedTracks) {
        return seedTracks.stream()
            .map(track -> track.getTrack().getArtistName())
            .filter(this::hasText)
            .collect(Collectors.collectingAndThen(
                Collectors.toCollection(LinkedHashSet::new),
                List::copyOf
            ));
    }

    private List<String> distinctSeedGenres(List<PmsCatalogPlaylistTrackEntity> seedTracks) {
        return seedTracks.stream()
            .map(track -> track.getTrack().getPrimaryGenre())
            .filter(this::hasText)
            .collect(Collectors.collectingAndThen(
                Collectors.toCollection(LinkedHashSet::new),
                List::copyOf
            ));
    }

    private double scaleSignal(long count, long maxCount) {
        if (maxCount <= 0) {
            return 0.55d;
        }

        return 0.55d + ((count / (double) maxCount) * 0.44d);
    }

    private double roundScore(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private String artistReason(long count) {
        if (count > 1) {
            return "Appears repeatedly in the selected playlist and can anchor the PMS seed set.";
        }

        return "Appears in the selected playlist and can broaden the current seed set.";
    }

    private String genreReason(long count) {
        if (count > 1) {
            return "Strong recurring genre signal across the selected playlist.";
        }

        return "Useful edge genre for expanding the current playlist mood.";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
