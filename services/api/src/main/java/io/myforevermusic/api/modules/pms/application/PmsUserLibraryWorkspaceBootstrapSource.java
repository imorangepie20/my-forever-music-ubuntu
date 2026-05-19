package io.myforevermusic.api.modules.pms.application;

import io.myforevermusic.api.modules.pms.presentation.PmsWorkspaceBootstrapResponse;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(-20)
public class PmsUserLibraryWorkspaceBootstrapSource implements PmsWorkspaceBootstrapSource {

    private static final int TRACK_SUGGESTION_LIMIT = 8;
    private static final int SIGNAL_SUGGESTION_LIMIT = 6;

    private final PmsUserLibraryStore pmsUserLibraryStore;

    public PmsUserLibraryWorkspaceBootstrapSource(PmsUserLibraryStore pmsUserLibraryStore) {
        this.pmsUserLibraryStore = pmsUserLibraryStore;
    }

    @Override
    public Optional<PmsWorkspaceBootstrapResponse> load(String userId, String playlistId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }

        List<PmsUserLibraryStore.LibraryPlaylistState> playlists = pmsUserLibraryStore.findPlaylists(userId);
        if (playlists.isEmpty()) {
            return Optional.empty();
        }

        PmsUserLibraryStore.LibraryPlaylistState defaultPlaylist = playlists.stream()
            .filter(playlist -> playlistId != null && !playlistId.isBlank() && playlist.playlistId().equals(playlistId))
            .findFirst()
            .orElse(playlists.getFirst());
        List<PmsUserLibraryStore.LibraryTrackState> defaultTracks = defaultPlaylist.tracks().stream()
            .sorted(Comparator.comparingInt(PmsUserLibraryStore.LibraryTrackState::sortOrder)
                .thenComparing(PmsUserLibraryStore.LibraryTrackState::trackId))
            .toList();

        List<PmsUserLibraryStore.LibraryTrackState> seedTracks = defaultTracks.stream()
            .filter(PmsUserLibraryStore.LibraryTrackState::seed)
            .toList();

        if (seedTracks.isEmpty()) {
            seedTracks = defaultTracks.stream()
                .limit(Math.min(2, defaultTracks.size()))
                .toList();
        }

        return Optional.of(
            new PmsWorkspaceBootstrapResponse(
                "api",
                "ok",
                Instant.now(),
                new PmsWorkspaceBootstrapResponse.WorkspaceDefaults(
                    userId,
                    defaultPlaylist.playlistId(),
                    seedTracks.stream()
                        .map(PmsUserLibraryStore.LibraryTrackState::trackId)
                        .toList(),
                    distinctSeedArtists(seedTracks),
                    distinctSeedGenres(seedTracks)
                ),
                playlists.stream()
                    .map(this::toPlaylistOption)
                    .toList(),
                defaultTracks.stream()
                    .limit(TRACK_SUGGESTION_LIMIT)
                    .map(this::toTrackSuggestion)
                    .toList(),
                toArtistSuggestions(defaultTracks),
                toGenreSuggestions(defaultTracks)
            )
        );
    }

    private PmsWorkspaceBootstrapResponse.PlaylistOption toPlaylistOption(
        PmsUserLibraryStore.LibraryPlaylistState playlist
    ) {
        return new PmsWorkspaceBootstrapResponse.PlaylistOption(
            playlist.playlistId(),
            playlist.title(),
            playlist.sourcePlatform(),
            playlist.trackCount(),
            playlist.curator(),
            playlist.highlight(),
            playlist.coverImageUrl(),
            playlist.platformExternalUrl(),
            playlist.platformUri(),
            "pms-user-library"
        );
    }

    private PmsWorkspaceBootstrapResponse.TrackSeedSuggestion toTrackSuggestion(
        PmsUserLibraryStore.LibraryTrackState track
    ) {
        return new PmsWorkspaceBootstrapResponse.TrackSeedSuggestion(
            track.trackId(),
            track.title(),
            track.artistName(),
            track.sourcePlatform(),
            track.albumTitle(),
            track.albumImageUrl(),
            track.platformExternalUrl(),
            track.platformUri(),
            track.previewUrl(),
            track.audioFeatures() == null ? null : track.audioFeatures().getDurationMs(),
            track.seed(),
            track.isrc(),
            track.spotifyTrackId(),
            track.spotifyUri(),
            track.tidalTrackId(),
            track.tidalUri(),
            track.preferredPlaybackPlatform(),
            track.playbackTargetStatus(),
            track.audioFeatures() == null ? null : track.audioFeatures().getAudioFeatureTrackId(),
            track.audioFeatures() != null && track.audioFeatures().isComplete(),
            track.audioFeatures() != null && track.audioFeatures().isComplete(),
            track.audioFeatures() == null ? "unresolved" : track.audioFeatures().getAudioFeatureSource(),
            track.audioFeatures() == null ? "unresolved" : track.audioFeatures().getAudioFeatureSource()
        );
    }

    private List<PmsWorkspaceBootstrapResponse.ArtistSeedSuggestion> toArtistSuggestions(
        List<PmsUserLibraryStore.LibraryTrackState> tracks
    ) {
        Map<String, Long> artistCounts = tracks.stream()
            .map(PmsUserLibraryStore.LibraryTrackState::artistName)
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
        List<PmsUserLibraryStore.LibraryTrackState> tracks
    ) {
        Map<String, Long> genreCounts = tracks.stream()
            .map(PmsUserLibraryStore.LibraryTrackState::primaryGenre)
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

    private List<String> distinctSeedArtists(List<PmsUserLibraryStore.LibraryTrackState> seedTracks) {
        return seedTracks.stream()
            .map(PmsUserLibraryStore.LibraryTrackState::artistName)
            .filter(this::hasText)
            .collect(Collectors.collectingAndThen(
                Collectors.toCollection(LinkedHashSet::new),
                List::copyOf
            ));
    }

    private List<String> distinctSeedGenres(List<PmsUserLibraryStore.LibraryTrackState> seedTracks) {
        return seedTracks.stream()
            .map(PmsUserLibraryStore.LibraryTrackState::primaryGenre)
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
            return "Repeated artist signal from the synced PMS user library.";
        }

        return "Useful artist expansion signal discovered during PMS library sync.";
    }

    private String genreReason(long count) {
        if (count > 1) {
            return "Recurring genre signal inside the synced PMS user library.";
        }

        return "Edge genre captured during PMS library sync.";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
