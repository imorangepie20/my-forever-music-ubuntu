package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import io.myforevermusic.api.modules.publiccuration.application.PublicPlaybackSessionStore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "public_playback_session")
public class PublicPlaybackSessionEntity {

    @Id
    @Column(name = "session_id", nullable = false, length = 120)
    private String sessionId;

    @Column(name = "playlist_id", nullable = false)
    private Long playlistId;

    @Column(name = "tidal_account_label", length = 200)
    private String tidalAccountLabel;

    @Column(name = "access_token_encrypted", nullable = false, columnDefinition = "TEXT")
    private String accessTokenEncrypted;

    @Column(name = "refresh_token_encrypted", columnDefinition = "TEXT")
    private String refreshTokenEncrypted;

    @Column(name = "scope_summary", length = 500)
    private String scopeSummary;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected PublicPlaybackSessionEntity() {
    }

    public PublicPlaybackSessionEntity(
        String sessionId,
        Long playlistId,
        String tidalAccountLabel,
        String accessTokenEncrypted,
        String refreshTokenEncrypted,
        String scopeSummary,
        Instant expiresAt,
        Instant createdAt
    ) {
        this.sessionId = sessionId;
        this.playlistId = playlistId;
        this.tidalAccountLabel = tidalAccountLabel;
        this.accessTokenEncrypted = accessTokenEncrypted;
        this.refreshTokenEncrypted = refreshTokenEncrypted;
        this.scopeSummary = scopeSummary;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public void markUsed(Instant usedAt) {
        this.lastUsedAt = usedAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Long getPlaylistId() {
        return playlistId;
    }

    public String getTidalAccountLabel() {
        return tidalAccountLabel;
    }

    public String getAccessTokenEncrypted() {
        return accessTokenEncrypted;
    }

    public String getRefreshTokenEncrypted() {
        return refreshTokenEncrypted;
    }

    public String getScopeSummary() {
        return scopeSummary;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public PublicPlaybackSessionStore.StoredSession toState() {
        return new PublicPlaybackSessionStore.StoredSession(
            sessionId,
            playlistId,
            tidalAccountLabel,
            accessTokenEncrypted,
            refreshTokenEncrypted,
            scopeSummary,
            expiresAt,
            createdAt,
            lastUsedAt
        );
    }
}
