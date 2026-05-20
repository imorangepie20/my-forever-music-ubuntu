package io.myforevermusic.api.modules.pms.infrastructure.persistence;

import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pms_user_track")
public class PmsUserTrackEntity {

    @Id
    @Column(name = "track_id", nullable = false, length = 160)
    private String trackId;

    @Column(name = "external_track_id", nullable = false, length = 160)
    private String externalTrackId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "artist_name", nullable = false, length = 200)
    private String artistName;

    @Column(name = "source_platform", nullable = false, length = 50)
    private String sourcePlatform;

    @Column(name = "primary_genre", length = 100)
    private String primaryGenre;

    @Column(name = "album_title", length = 200)
    private String albumTitle;

    @Column(name = "album_image_url", length = 500)
    private String albumImageUrl;

    @Column(name = "platform_external_url", length = 500)
    private String platformExternalUrl;

    @Column(name = "platform_uri", length = 200)
    private String platformUri;

    @Column(name = "preview_url", length = 500)
    private String previewUrl;

    @Column(name = "isrc", length = 32)
    private String isrc;

    @Column(name = "spotify_track_id", length = 100)
    private String spotifyTrackId;

    @Column(name = "spotify_uri", length = 200)
    private String spotifyUri;

    @Column(name = "tidal_track_id", length = 100)
    private String tidalTrackId;

    @Column(name = "tidal_uri", length = 200)
    private String tidalUri;

    @Column(name = "preferred_playback_platform", length = 50)
    private String preferredPlaybackPlatform;

    @Column(name = "playback_target_status", nullable = false, length = 50)
    private String playbackTargetStatus;

    @Column(name = "canonical_track_id")
    private Long canonicalTrackId;

    @Embedded
    private PmsTrackAudioFeatures audioFeatures;

    protected PmsUserTrackEntity() {
    }

    public PmsUserTrackEntity(PmsUserLibraryStore.LibraryTrackState state) {
        apply(state);
    }

    public void apply(PmsUserLibraryStore.LibraryTrackState state) {
        this.trackId = state.trackId();
        this.externalTrackId = state.externalTrackId();
        this.title = state.title();
        this.artistName = state.artistName();
        this.sourcePlatform = state.sourcePlatform();
        this.primaryGenre = state.primaryGenre();
        this.albumTitle = state.albumTitle();
        this.albumImageUrl = state.albumImageUrl();
        this.platformExternalUrl = state.platformExternalUrl();
        this.platformUri = state.platformUri();
        this.previewUrl = state.previewUrl();
        this.isrc = state.isrc();
        this.spotifyTrackId = state.spotifyTrackId();
        this.spotifyUri = state.spotifyUri();
        this.tidalTrackId = state.tidalTrackId();
        this.tidalUri = state.tidalUri();
        this.preferredPlaybackPlatform = state.preferredPlaybackPlatform();
        this.playbackTargetStatus = state.playbackTargetStatus();
        this.audioFeatures = state.audioFeatures() == null
            ? PmsTrackAudioFeatures.unresolved()
            : state.audioFeatures();
    }

    public String getTrackId() {
        return trackId;
    }

    public String getExternalTrackId() {
        return externalTrackId;
    }

    public String getTitle() {
        return title;
    }

    public String getArtistName() {
        return artistName;
    }

    public String getSourcePlatform() {
        return sourcePlatform;
    }

    public String getPrimaryGenre() {
        return primaryGenre;
    }

    public String getAlbumTitle() {
        return albumTitle;
    }

    public String getAlbumImageUrl() {
        return albumImageUrl;
    }

    public String getPlatformExternalUrl() {
        return platformExternalUrl;
    }

    public String getPlatformUri() {
        return platformUri;
    }

    public String getPreviewUrl() {
        return previewUrl;
    }

    public String getIsrc() {
        return isrc;
    }

    public String getSpotifyTrackId() {
        return spotifyTrackId;
    }

    public String getSpotifyUri() {
        return spotifyUri;
    }

    public String getTidalTrackId() {
        return tidalTrackId;
    }

    public String getTidalUri() {
        return tidalUri;
    }

    public String getPreferredPlaybackPlatform() {
        return preferredPlaybackPlatform;
    }

    public String getPlaybackTargetStatus() {
        return playbackTargetStatus;
    }

    public Long getCanonicalTrackId() {
        return canonicalTrackId;
    }

    public PmsTrackAudioFeatures getAudioFeatures() {
        return audioFeatures;
    }

    public void applyAudioFeatures(PmsTrackAudioFeatures audioFeatures) {
        this.audioFeatures = audioFeatures;
    }
}
