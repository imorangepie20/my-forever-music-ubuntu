package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "public_playlist_play_event")
public class PublicPlaylistPlayEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "public_playlist_play_event_id")
    private Long eventId;

    @Column(name = "playlist_id", nullable = false)
    private Long playlistId;

    @Column(name = "public_session_id", length = 120)
    private String publicSessionId;

    @Column(name = "track_id")
    private Long trackId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "position_ms")
    private Integer positionMs;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected PublicPlaylistPlayEventEntity() {
    }

    public PublicPlaylistPlayEventEntity(
        Long playlistId,
        String publicSessionId,
        Long trackId,
        String eventType,
        Integer positionMs,
        Integer durationMs,
        Instant occurredAt,
        Instant receivedAt
    ) {
        this.playlistId = playlistId;
        this.publicSessionId = publicSessionId;
        this.trackId = trackId;
        this.eventType = eventType;
        this.positionMs = positionMs;
        this.durationMs = durationMs;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
    }

    public Long getEventId() {
        return eventId;
    }

    public Long getPlaylistId() {
        return playlistId;
    }

    public String getEventType() {
        return eventType;
    }
}
