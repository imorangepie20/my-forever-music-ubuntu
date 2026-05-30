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
@Table(name = "public_curation_playlist")
public class PublicCurationPlaylistEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "public_curation_playlist_id")
    private Long playlistId;

    @Column(name = "slug", nullable = false, length = 180)
    private String slug;

    @Column(name = "title", nullable = false, length = 240)
    private String title;

    @Column(name = "subtitle", length = 500)
    private String subtitle;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "prompt", columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "filter_snapshot_json", columnDefinition = "TEXT")
    private String filterSnapshotJson;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "cover_style", length = 120)
    private String coverStyle;

    @Column(name = "model_version", length = 160)
    private String modelVersion;

    @Column(name = "track_count", nullable = false)
    private int trackCount;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_by_admin_user_id", nullable = false, length = 100)
    private String createdByAdminUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PublicCurationPlaylistEntity() {
    }

    public PublicCurationPlaylistEntity(PublicCurationPlaylistStore.CreateDraft draft) {
        this.slug = draft.slug();
        this.title = draft.title();
        this.subtitle = draft.subtitle();
        this.description = draft.description();
        this.prompt = draft.prompt();
        this.filterSnapshotJson = draft.filterSnapshotJson();
        this.status = "draft";
        this.coverStyle = draft.coverStyle();
        this.modelVersion = draft.modelVersion();
        this.trackCount = draft.trackCount();
        this.durationMs = draft.durationMs();
        this.createdByAdminUserId = draft.createdByAdminUserId();
        this.createdAt = draft.createdAt();
        this.updatedAt = draft.createdAt();
    }

    public Long getPlaylistId() {
        return playlistId;
    }

    public void publish(Instant publishedAt) {
        this.status = "published";
        this.publishedAt = publishedAt;
        this.updatedAt = publishedAt;
    }

    public PublicCurationPlaylistStore.StoredPlaylistSummary toSummary() {
        return new PublicCurationPlaylistStore.StoredPlaylistSummary(
            playlistId,
            slug,
            title,
            subtitle,
            status,
            coverStyle,
            modelVersion,
            trackCount,
            durationMs,
            publishedAt,
            createdByAdminUserId,
            createdAt,
            updatedAt
        );
    }

    public PublicCurationPlaylistStore.StoredPlaylist toState(
        java.util.List<PublicCurationPlaylistStore.StoredTrack> tracks,
        PublicCurationPlaylistStore.StoredRun run
    ) {
        return new PublicCurationPlaylistStore.StoredPlaylist(
            playlistId,
            slug,
            title,
            subtitle,
            description,
            prompt,
            filterSnapshotJson,
            status,
            coverStyle,
            modelVersion,
            trackCount,
            durationMs,
            publishedAt,
            createdByAdminUserId,
            createdAt,
            updatedAt,
            tracks,
            run
        );
    }
}
