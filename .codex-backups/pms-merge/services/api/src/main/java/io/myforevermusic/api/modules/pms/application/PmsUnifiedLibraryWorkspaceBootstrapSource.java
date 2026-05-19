package io.myforevermusic.api.modules.pms.application;

import io.myforevermusic.api.modules.pms.presentation.PmsWorkspaceBootstrapResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(-30)
public class PmsUnifiedLibraryWorkspaceBootstrapSource implements PmsWorkspaceBootstrapSource {

    private static final int TRACK_SUGGESTION_LIMIT = 8;
    private static final int SIGNAL_SUGGESTION_LIMIT = 6;
    private static final String GMS_APPROVED_PLAYLIST_PREFIX = "gms-ems-";
    private static final String GMS_APPROVED_CURATOR = "gms approved";
    private static final String GMS_APPROVED_HIGHLIGHT =
        "Approved from GMS and added to the PMS library.";

    private final PmsUserLibraryStore pmsUserLibraryStore;
    private final PmsPlaylistImportStore pmsPlaylistImportStore;
    private final PmsPersonalPlaylistStore pmsPersonalPlaylistStore;

    public PmsUnifiedLibraryWorkspaceBootstrapSource(
        PmsUserLibraryStore pmsUserLibraryStore,
        PmsPlaylistImportStore pmsPlaylistImportStore,
        PmsPersonalPlaylistStore pmsPersonalPlaylistStore
    ) {
        this.pmsUserLibraryStore = pmsUserLibraryStore;
        this.pmsPlaylistImportStore = pmsPlaylistImportStore;
        this.pmsPersonalPlaylistStore = pmsPersonalPlaylistStore;
    }

    @Override
    public Optional<PmsWorkspaceBootstrapResponse> load(String userId, String playlistId) {
        if (!hasText(userId)) {
            return Optional.empty();
        }

        List<PlaylistCandidate> libraryPlaylists = resolvePlatformLibraryPlaylists(userId);
        List<PlaylistCandidate> gmsApprovedPlaylists = resolveGmsApprovedPlaylists(userId);
        if (libraryPlaylists.isEmpty() && gmsApprovedPlaylists.isEmpty()) {
            return Optional.empty();
        }

        List<PlaylistCandidate> mergedPlaylists = new ArrayList<>(libraryPlaylists.size() + gmsApprovedPlaylists.size());
        mergedPlaylists.addAll(libraryPlaylists);
        mergedPlaylists.addAll(gmsApprovedPlaylists);

        PlaylistCandidate defaultPlaylist = mergedPlaylists.stream()
            .filter(candidate -> hasText(playlistId) && candidate.playlistId().equals(playlistId))
            .findFirst()
            .orElse(mergedPlaylists.getFirst());
        List<TrackCandidate> defaultTracks = defaultPlaylist.tracks().stream()
            .sorted(Comparator.comparingInt(TrackCandidate::sortOrder)
                .thenComparing(TrackCandidate::trackId))
            .toList();

        Set<String> effectiveSeedTrackIds = resolveEffectiveSeedTrackIds(defaultTracks);

        return Optional.of(
            new PmsWorkspaceBootstrapResponse(
                "api",
                "ok",
                Instant.now(),
                new PmsWorkspaceBootstrapResponse.WorkspaceDefaults(
                    userId,
                    defaultPlaylist.playlistId(),
                    List.copyOf(effectiveSeedTrackIds),
                    distinctSeedArtists(defaultTracks, effectiveSeedTrackIds),
                    distinctSeedGenres(defaultTracks, effectiveSeedTrackIds)
                ),
                mergedPlaylists.stream()
                    .map(this::toPlaylistOption)
                    .toList(),
                defaultTracks.stream()
                    .limit(TRACK_SUGGESTION_LIMIT)
                    .map(track -> toTrackSuggestion(track, effectiveSeedTrackIds.contains(track.trackId())))
                    .toList(),
                toArtistSuggestions(defaultTracks),
                toGenreSuggestions(defaultTracks)
            )
        );
    }

    private List<PlaylistCandidate> resolvePlatformLibraryPlaylists(String userId) {
        List<PmsUserLibraryStore.LibraryPlaylistState> userLibraryPlaylists = pmsUserLibraryStore.findPlaylists(userId);
        if (!userLibraryPlaylists.isEmpty()) {
            return userLibraryPlaylists.stream()
                .map(this::toPlaylistCandidate)
                .toList();
        }

        return pmsPlaylistImportStore.findImportedPlaylists(userId).stream()
            .map(this::toPlaylistCandidate)
            .toList();
    }

    private List<PlaylistCandidate> resolveGmsApprovedPlaylists(String userId) {
        return pmsPersonalPlaylistStore.findPlaylists(userId).stream()
            .filter(this::isGmsApprovedPlaylist)
            .map(this::toPlaylistCandidate)
            .toList();
    }

    private boolean isGmsApprovedPlaylist(PmsPersonalPlaylistStore.PersonalPlaylistState playlist) {
        return hasText(playlist.playlistId())
            && playlist.playlistId().startsWith(GMS_APPROVED_PLAYLIST_PREFIX)
            && playlist.trackCount() > 0;
    }

    private PlaylistCandidate toPlaylistCandidate(PmsUserLibraryStore.LibraryPlaylistState playlist) {
        return new PlaylistCandidate(
            playlist.playlistId(),
            playlist.externalPlaylistId(),
            playlist.title(),
            playlist.sourcePlatform(),
            playlist.curator(),
            playlist.highlight(),
            playlist.coverImageUrl(),
            playlist.platformExternalUrl(),
            playlist.platformUri(),
            playlist.lastSyncedAt(),
            safeLibraryTracks(playlist.tracks()).stream()
                .map(this::toTrackCandidate)
                .toList()
        );
    }

    private PlaylistCandidate toPlaylistCandidate(PmsPlaylistImportStore.ImportedPlaylistState playlist) {
        return new PlaylistCandidate(
            playlist.playlistId(),
            playlist.externalPlaylistId(),
            playlist.title(),
            playlist.sourcePlatform(),
            playlist.curator(),
            playlist.highlight(),
            playlist.coverImageUrl(),
            playlist.platformExternalUrl(),
            playlist.platformUri(),
            playlist.importedAt(),
            safeImportedTracks(playlist.tracks()).stream()
                .map(this::toTrackCandidate)
                .toList()
        );
    }

    private PlaylistCandidate toPlaylistCandidate(PmsPersonalPlaylistStore.PersonalPlaylistState playlist) {
        List<TrackCandidate> tracks = safePersonalTracks(playlist.tracks()).stream()
            .map(this::toTrackCandidate)
            .toList();
        TrackCandidate firstTrack = tracks.isEmpty() ? null : tracks.getFirst();

        return new PlaylistCandidate(
            playlist.playlistId(),
            null,
            playlist.title(),
            firstTrack == null ? "pms" : firstTrack.sourcePlatform(),
            GMS_APPROVED_CURATOR,
            hasText(playlist.description()) ? playlist.description() : GMS_APPROVED_HIGHLIGHT,
            firstTrack == null ? null : firstTrack.albumImageUrl(),
            null,
            null,
            playlist.updatedAt(),
            tracks
        );
    }

    private TrackCandidate toTrackCandidate(PmsUserLibraryStore.LibraryTrackState track) {
        return new TrackCandidate(
            track.trackId(),
            track.title(),
            track.artistName(),
            track.sourcePlatform(),
            track.primaryGenre(),
            track.albumTitle(),
            track.albumImageUrl(),
            track.platformExternalUrl(),
            track.platformUri(),
            track.previewUrl(),
            track.isrc(),
            track.spotifyTrackId(),
            track.spotifyUri(),
            track.tidalTrackId(),
            track.tidalUri(),
            track.preferredPlaybackPlatform(),
            track.playbackTargetStatus(),
            track.sortOrder(),
            track.seed(),
            track.audioFeatures() == null ? null : track.audioFeatures().getDurationMs(),
            track.audioFeatures() == null ? null : track.audioFeatures().getAudioFeatureTrackId(),
            track.audioFeatures() != null && track.audioFeatures().isComplete(),
            track.audioFeatures() == null ? "unresolved" : track.audioFeatures().getAudioFeatureSource()
        );
    }

    private TrackCandidate toTrackCandidate(PmsPlaylistImportStore.ImportedTrackState track) {
        return new TrackCandidate(
            track.trackId(),
            track.title(),
            track.artistName(),
            track.sourcePlatform(),
            track.primaryGenre(),
            track.albumTitle(),
            track.albumImageUrl(),
            track.platformExternalUrl(),
            track.platformUri(),
            track.previewUrl(),
            track.isrc(),
            track.spotifyTrackId(),
            track.spotifyUri(),
            track.tidalTrackId(),
            track.tidalUri(),
            track.preferredPlaybackPlatform(),
            track.playbackTargetStatus(),
            track.sortOrder(),
            track.seed(),
            track.audioFeatures() == null ? null : track.audioFeatures().getDurationMs(),
            track.audioFeatures() == null ? null : track.audioFeatures().getAudioFeatureTrackId(),
            track.audioFeatures() != null && track.audioFeatures().isComplete(),
            track.audioFeatures() == null ? "unresolved" : track.audioFeatures().getAudioFeatureSource()
        );
    }

    private TrackCandidate toTrackCandidate(PmsPersonalPlaylistStore.PersonalTrackState track) {
        return new TrackCandidate(
            track.trackId(),
            track.title(),
            track.artistName(),
            track.sourcePlatform(),
            null,
            track.albumTitle(),
            track.albumImageUrl(),
            track.platformExternalUrl(),
            track.platformUri(),
            track.previewUrl(),
            track.isrc(),
            track.spotifyTrackId(),
            track.spotifyUri(),
            track.tidalTrackId(),
            track.tidalUri(),
            track.preferredPlaybackPlatform(),
            track.playbackTargetStatus(),
            track.sortOrder() == null ? 0 : track.sortOrder(),
            false,
            track.durationMs(),
            null,
            false,
            "unresolved"
        );
    }

    private PmsWorkspaceBootstrapResponse.PlaylistOption toPlaylistOption(PlaylistCandidate playlist) {
        return new PmsWorkspaceBootstrapResponse.PlaylistOption(
            playlist.playlistId(),
            playlist.title(),
            playlist.sourcePlatform(),
            playlist.trackCount(),
            playlist.curator(),
            playlist.highlight(),
            playlist.coverImageUrl(),
            playlist.platformExternalUrl(),
            playlist.platformUri()
        );
    }

    private PmsWorkspaceBootstrapResponse.TrackSeedSuggestion toTrackSuggestion(TrackCandidate track, boolean seed) {
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
            track.durationMs(),
            seed,
            track.isrc(),
            track.spotifyTrackId(),
            track.spotifyUri(),
            track.tidalTrackId(),
            track.tidalUri(),
            track.preferredPlaybackPlatform(),
            track.playbackTargetStatus(),
            track.audioFeatureTrackId(),
            track.audioFeaturesFilled(),
            track.audioFeaturesFilled(),
            track.audioFeatureSource(),
            track.audioFeatureSource()
        );
    }

    private Set<String> resolveEffectiveSeedTrackIds(List<TrackCandidate> tracks) {
        LinkedHashSet<String> explicitSeedTrackIds = tracks.stream()
            .filter(TrackCandidate::seed)
            .map(TrackCandidate::trackId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!explicitSeedTrackIds.isEmpty()) {
            return explicitSeedTrackIds;
        }

        return tracks.stream()
            .limit(Math.min(2, tracks.size()))
            .map(TrackCandidate::trackId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<String> distinctSeedArtists(List<TrackCandidate> tracks, Set<String> seedTrackIds) {
        return tracks.stream()
            .filter(track -> seedTrackIds.contains(track.trackId()))
            .map(TrackCandidate::artistName)
            .filter(this::hasText)
            .collect(Collectors.collectingAndThen(
                Collectors.toCollection(LinkedHashSet::new),
                List::copyOf
            ));
    }

    private List<String> distinctSeedGenres(List<TrackCandidate> tracks, Set<String> seedTrackIds) {
        return tracks.stream()
            .filter(track -> seedTrackIds.contains(track.trackId()))
            .map(TrackCandidate::primaryGenre)
            .filter(this::hasText)
            .collect(Collectors.collectingAndThen(
                Collectors.toCollection(LinkedHashSet::new),
                List::copyOf
            ));
    }

    private List<PmsWorkspaceBootstrapResponse.ArtistSeedSuggestion> toArtistSuggestions(List<TrackCandidate> tracks) {
        Map<String, Long> artistCounts = tracks.stream()
            .map(TrackCandidate::artistName)
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

    private List<PmsWorkspaceBootstrapResponse.GenreSeedSuggestion> toGenreSuggestions(List<TrackCandidate> tracks) {
        Map<String, Long> genreCounts = tracks.stream()
            .map(TrackCandidate::primaryGenre)
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
            return "Recurring artist signal inside the PMS library.";
        }

        return "Useful artist expansion signal from the PMS library.";
    }

    private String genreReason(long count) {
        if (count > 1) {
            return "Recurring genre signal inside the PMS library.";
        }

        return "Useful edge genre from the PMS library.";
    }

    private List<PmsUserLibraryStore.LibraryTrackState> safeLibraryTracks(List<PmsUserLibraryStore.LibraryTrackState> tracks) {
        return tracks == null ? List.of() : tracks;
    }

    private List<PmsPlaylistImportStore.ImportedTrackState> safeImportedTracks(
        List<PmsPlaylistImportStore.ImportedTrackState> tracks
    ) {
        return tracks == null ? List.of() : tracks;
    }

    private List<PmsPersonalPlaylistStore.PersonalTrackState> safePersonalTracks(
        List<PmsPersonalPlaylistStore.PersonalTrackState> tracks
    ) {
        return tracks == null ? List.of() : tracks;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record PlaylistCandidate(
        String playlistId,
        String externalPlaylistId,
        String title,
        String sourcePlatform,
        String curator,
        String highlight,
        String coverImageUrl,
        String platformExternalUrl,
        String platformUri,
        Instant updatedAt,
        List<TrackCandidate> tracks
    ) {
        int trackCount() {
            return tracks == null ? 0 : tracks.size();
        }
    }

    private record TrackCandidate(
        String trackId,
        String title,
        String artistName,
        String sourcePlatform,
        String primaryGenre,
        String albumTitle,
        String albumImageUrl,
        String platformExternalUrl,
        String platformUri,
        String previewUrl,
        String isrc,
        String spotifyTrackId,
        String spotifyUri,
        String tidalTrackId,
        String tidalUri,
        String preferredPlaybackPlatform,
        String playbackTargetStatus,
        int sortOrder,
        boolean seed,
        Integer durationMs,
        String audioFeatureTrackId,
        boolean audioFeaturesFilled,
        String audioFeatureSource
    ) {}
}
