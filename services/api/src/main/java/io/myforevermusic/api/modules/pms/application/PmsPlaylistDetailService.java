package io.myforevermusic.api.modules.pms.application;

import io.myforevermusic.api.common.error.ApiResourceNotFoundException;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsTrackAudioFeatures;
import io.myforevermusic.api.modules.pms.presentation.PmsPlaylistDetailResponse;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PmsPlaylistDetailService {

    private static final String GMS_APPROVED_PLAYLIST_PREFIX = "gms-ems-";

    private final PmsUserLibraryStore pmsUserLibraryStore;
    private final PmsPlaylistImportStore pmsPlaylistImportStore;
    private final PmsPersonalPlaylistStore pmsPersonalPlaylistStore;

    public PmsPlaylistDetailService(
        PmsUserLibraryStore pmsUserLibraryStore,
        PmsPlaylistImportStore pmsPlaylistImportStore,
        PmsPersonalPlaylistStore pmsPersonalPlaylistStore
    ) {
        this.pmsUserLibraryStore = pmsUserLibraryStore;
        this.pmsPlaylistImportStore = pmsPlaylistImportStore;
        this.pmsPersonalPlaylistStore = pmsPersonalPlaylistStore;
    }

    public PmsPlaylistDetailResponse getPlaylistDetail(String userId, String playlistId) {
        if (playlistId == null || playlistId.isBlank()) {
            throw new IllegalArgumentException("playlist_id is required.");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id is required for PMS playlist detail.");
        }

        return pmsUserLibraryStore.findPlaylists(userId)
            .stream()
            .filter(playlist -> playlist.playlistId().equals(playlistId))
            .findFirst()
            .map(this::toUserLibraryResponse)
            .orElseGet(() -> pmsPlaylistImportStore.findImportedPlaylists(userId)
                .stream()
                .filter(playlist -> playlist.playlistId().equals(playlistId))
                .findFirst()
                .map(this::toImportedResponse)
                .orElseGet(() -> pmsPersonalPlaylistStore.findPlaylist(userId, playlistId)
                    .map(this::toPersonalResponse)
                    .orElseThrow(() -> new ApiResourceNotFoundException(
                        "PMS playlist was not found for user %s: %s".formatted(userId, playlistId)
                    ))));
    }

    private PmsPlaylistDetailResponse toUserLibraryResponse(PmsUserLibraryStore.LibraryPlaylistState playlist) {
        List<PmsPlaylistDetailResponse.TrackDetail> tracks = safeLibraryTracks(playlist.tracks())
            .stream()
            .sorted(Comparator.comparingInt(PmsUserLibraryStore.LibraryTrackState::sortOrder)
                .thenComparing(PmsUserLibraryStore.LibraryTrackState::trackId))
            .map(this::toTrackDetail)
            .toList();

        return new PmsPlaylistDetailResponse(
            "api",
            "ok",
            Instant.now(),
            "pms-user-library",
            new PmsPlaylistDetailResponse.PlaylistDetail(
                playlist.playlistId(),
                playlist.externalPlaylistId(),
                playlist.title(),
                playlist.sourcePlatform(),
                tracks.size(),
                playlist.curator(),
                playlist.highlight(),
                playlist.coverImageUrl(),
                playlist.platformExternalUrl(),
                playlist.platformUri(),
                null,
                playlist.lastSyncedAt()
            ),
            tracks
        );
    }

    private PmsPlaylistDetailResponse toImportedResponse(PmsPlaylistImportStore.ImportedPlaylistState playlist) {
        List<PmsPlaylistDetailResponse.TrackDetail> tracks = safeImportedTracks(playlist.tracks())
            .stream()
            .sorted(Comparator.comparingInt(PmsPlaylistImportStore.ImportedTrackState::sortOrder)
                .thenComparing(PmsPlaylistImportStore.ImportedTrackState::trackId))
            .map(this::toTrackDetail)
            .toList();

        return new PmsPlaylistDetailResponse(
            "api",
            "ok",
            Instant.now(),
            "pms-imported-playlist",
            new PmsPlaylistDetailResponse.PlaylistDetail(
                playlist.playlistId(),
                playlist.externalPlaylistId(),
                playlist.title(),
                playlist.sourcePlatform(),
                tracks.size(),
                playlist.curator(),
                playlist.highlight(),
                playlist.coverImageUrl(),
                playlist.platformExternalUrl(),
                playlist.platformUri(),
                playlist.importedAt(),
                null
            ),
            tracks
        );
    }

    private PmsPlaylistDetailResponse toPersonalResponse(PmsPersonalPlaylistStore.PersonalPlaylistState playlist) {
        if (isGmsApprovedPlaylist(playlist)) {
            return toGmsApprovedResponse(playlist);
        }

        return toManualPersonalResponse(playlist);
    }

    private PmsPlaylistDetailResponse toGmsApprovedResponse(PmsPersonalPlaylistStore.PersonalPlaylistState playlist) {
        List<PmsPlaylistDetailResponse.TrackDetail> tracks = safePersonalTracks(playlist.tracks())
            .stream()
            .sorted(Comparator.comparingInt(PmsPersonalPlaylistStore.PersonalTrackState::sortOrder)
                .thenComparing(PmsPersonalPlaylistStore.PersonalTrackState::trackId))
            .map(this::toTrackDetail)
            .toList();
        String sourcePlatform = tracks.stream()
            .map(PmsPlaylistDetailResponse.TrackDetail::sourcePlatform)
            .filter(this::hasText)
            .findFirst()
            .orElse("pms");

        return new PmsPlaylistDetailResponse(
            "api",
            "ok",
            Instant.now(),
            "pms-gms-approved-playlist",
            new PmsPlaylistDetailResponse.PlaylistDetail(
                playlist.playlistId(),
                null,
                playlist.title(),
                sourcePlatform,
                tracks.size(),
                "gms approved",
                playlist.description(),
                tracks.isEmpty() ? null : tracks.get(0).albumImageUrl(),
                null,
                null,
                playlist.createdAt(),
                playlist.updatedAt()
            ),
            tracks
        );
    }

    private PmsPlaylistDetailResponse toManualPersonalResponse(PmsPersonalPlaylistStore.PersonalPlaylistState playlist) {
        List<PmsPlaylistDetailResponse.TrackDetail> tracks = safePersonalTracks(playlist.tracks())
            .stream()
            .sorted(Comparator.comparingInt(PmsPersonalPlaylistStore.PersonalTrackState::sortOrder)
                .thenComparing(PmsPersonalPlaylistStore.PersonalTrackState::trackId))
            .map(this::toTrackDetail)
            .toList();

        return new PmsPlaylistDetailResponse(
            "api",
            "ok",
            Instant.now(),
            "pms-personal-playlist",
            new PmsPlaylistDetailResponse.PlaylistDetail(
                playlist.playlistId(),
                null,
                playlist.title(),
                "pms",
                tracks.size(),
                "personal playlist",
                playlist.description(),
                tracks.isEmpty() ? null : tracks.get(0).albumImageUrl(),
                null,
                null,
                playlist.createdAt(),
                playlist.updatedAt()
            ),
            tracks
        );
    }

    private PmsPlaylistDetailResponse.TrackDetail toTrackDetail(PmsUserLibraryStore.LibraryTrackState track) {
        return new PmsPlaylistDetailResponse.TrackDetail(
            track.trackId(),
            track.externalTrackId(),
            track.title(),
            track.artistName(),
            track.sourcePlatform(),
            track.primaryGenre(),
            track.albumTitle(),
            track.albumImageUrl(),
            track.platformExternalUrl(),
            track.platformUri(),
            track.previewUrl(),
            durationMs(track.audioFeatures()),
            track.sortOrder(),
            track.seed(),
            track.isrc(),
            spotifyTrackId(track.spotifyTrackId(), track.audioFeatures()),
            track.spotifyUri(),
            track.tidalTrackId(),
            track.tidalUri(),
            track.preferredPlaybackPlatform(),
            track.playbackTargetStatus(),
            audioFeatureTrackId(track.audioFeatures()),
            audioFeaturesFilled(track.audioFeatures()),
            audioFeaturesFilled(track.audioFeatures()),
            audioFeatureSource(track.audioFeatures()),
            audioFeatureSource(track.audioFeatures())
        );
    }

    private PmsPlaylistDetailResponse.TrackDetail toTrackDetail(PmsPlaylistImportStore.ImportedTrackState track) {
        return new PmsPlaylistDetailResponse.TrackDetail(
            track.trackId(),
            track.externalTrackId(),
            track.title(),
            track.artistName(),
            track.sourcePlatform(),
            track.primaryGenre(),
            track.albumTitle(),
            track.albumImageUrl(),
            track.platformExternalUrl(),
            track.platformUri(),
            track.previewUrl(),
            durationMs(track.audioFeatures()),
            track.sortOrder(),
            track.seed(),
            track.isrc(),
            spotifyTrackId(track.spotifyTrackId(), track.audioFeatures()),
            track.spotifyUri(),
            track.tidalTrackId(),
            track.tidalUri(),
            track.preferredPlaybackPlatform(),
            track.playbackTargetStatus(),
            audioFeatureTrackId(track.audioFeatures()),
            audioFeaturesFilled(track.audioFeatures()),
            audioFeaturesFilled(track.audioFeatures()),
            audioFeatureSource(track.audioFeatures()),
            audioFeatureSource(track.audioFeatures())
        );
    }

    private PmsPlaylistDetailResponse.TrackDetail toTrackDetail(PmsPersonalPlaylistStore.PersonalTrackState track) {
        return new PmsPlaylistDetailResponse.TrackDetail(
            track.trackId(),
            track.externalTrackId(),
            track.title(),
            track.artistName(),
            track.sourcePlatform(),
            null,
            track.albumTitle(),
            track.albumImageUrl(),
            track.platformExternalUrl(),
            track.platformUri(),
            track.previewUrl(),
            track.durationMs(),
            track.sortOrder(),
            false,
            track.isrc(),
            track.spotifyTrackId(),
            track.spotifyUri(),
            track.tidalTrackId(),
            track.tidalUri(),
            track.preferredPlaybackPlatform(),
            track.playbackTargetStatus(),
            track.audioFeatureTrackId(),
            track.audioFeatureTrackId() != null,
            track.audioFeatureTrackId() != null,
            "pms-personal-playlist",
            "pms-personal-playlist"
        );
    }

    private String spotifyTrackId(String spotifyTrackId, PmsTrackAudioFeatures audioFeatures) {
        if (spotifyTrackId != null && !spotifyTrackId.isBlank()) {
            return spotifyTrackId;
        }

        return audioFeatureTrackId(audioFeatures);
    }

    private String audioFeatureTrackId(PmsTrackAudioFeatures audioFeatures) {
        return audioFeatures == null ? null : audioFeatures.getAudioFeatureTrackId();
    }

    private Integer durationMs(PmsTrackAudioFeatures audioFeatures) {
        return audioFeatures == null ? null : audioFeatures.getDurationMs();
    }

    private boolean audioFeaturesFilled(PmsTrackAudioFeatures audioFeatures) {
        return audioFeatures != null && audioFeatures.isComplete();
    }

    private String audioFeatureSource(PmsTrackAudioFeatures audioFeatures) {
        return audioFeatures == null ? "unresolved" : audioFeatures.getAudioFeatureSource();
    }

    private List<PmsUserLibraryStore.LibraryTrackState> safeLibraryTracks(
        List<PmsUserLibraryStore.LibraryTrackState> tracks
    ) {
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

    private boolean isGmsApprovedPlaylist(PmsPersonalPlaylistStore.PersonalPlaylistState playlist) {
        return hasText(playlist.playlistId()) && playlist.playlistId().startsWith(GMS_APPROVED_PLAYLIST_PREFIX);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
