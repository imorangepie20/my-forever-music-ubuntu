package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import io.myforevermusic.api.modules.publiccuration.application.PublicCurationPlaylistStore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "public_curation_playlist_track")
public class PublicCurationPlaylistTrackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "public_curation_playlist_track_id")
    private Long trackId;

    @Column(name = "playlist_id", nullable = false)
    private Long playlistId;

    @Column(name = "track_order", nullable = false)
    private int trackOrder;

    @Column(name = "source_track_scope", nullable = false, length = 80)
    private String sourceTrackScope;

    @Column(name = "source_track_id", nullable = false, length = 200)
    private String sourceTrackId;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "artist_name", nullable = false, length = 300)
    private String artistName;

    @Column(name = "album_title", length = 300)
    private String albumTitle;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "isrc", length = 32)
    private String isrc;

    @Column(name = "tidal_track_id", nullable = false, length = 120)
    private String tidalTrackId;

    @Column(name = "tidal_uri", nullable = false, length = 240)
    private String tidalUri;

    @Column(name = "tidal_external_url", length = 500)
    private String tidalExternalUrl;

    @Column(name = "score", nullable = false)
    private double score;

    @Column(name = "score_breakdown_json", columnDefinition = "TEXT")
    private String scoreBreakdownJson;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PublicCurationPlaylistTrackEntity() {
    }

    public PublicCurationPlaylistTrackEntity(
        Long playlistId,
        PublicCurationPlaylistStore.TrackDraft draft,
        Instant createdAt
    ) {
        this.playlistId = playlistId;
        this.trackOrder = draft.trackOrder();
        this.sourceTrackScope = draft.sourceTrackScope();
        this.sourceTrackId = draft.sourceTrackId();
        this.title = draft.title();
        this.artistName = draft.artistName();
        this.albumTitle = draft.albumTitle();
        this.imageUrl = draft.imageUrl();
        this.durationMs = draft.durationMs();
        this.isrc = draft.isrc();
        this.tidalTrackId = draft.tidalTrackId();
        this.tidalUri = draft.tidalUri();
        this.tidalExternalUrl = draft.tidalExternalUrl();
        this.score = draft.score();
        this.scoreBreakdownJson = draft.scoreBreakdownJson();
        this.reason = draft.reason();
        this.createdAt = createdAt;
    }

    public PublicCurationPlaylistStore.StoredTrack toState() {
        return new PublicCurationPlaylistStore.StoredTrack(
            trackId,
            playlistId,
            trackOrder,
            sourceTrackScope,
            sourceTrackId,
            title,
            artistName,
            albumTitle,
            imageUrl,
            durationMs,
            isrc,
            tidalTrackId,
            tidalUri,
            tidalExternalUrl,
            score,
            scoreBreakdownJson,
            reason,
            createdAt
        );
    }
}
