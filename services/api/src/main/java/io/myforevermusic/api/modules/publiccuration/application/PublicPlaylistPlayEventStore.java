package io.myforevermusic.api.modules.publiccuration.application;

import java.time.Instant;

public interface PublicPlaylistPlayEventStore {

    StoredEvent record(RecordEvent event);

    record RecordEvent(
        Long playlistId,
        String publicSessionId,
        Long trackId,
        String eventType,
        Integer positionMs,
        Integer durationMs,
        Instant occurredAt,
        Instant receivedAt
    ) {
    }

    record StoredEvent(
        Long eventId,
        Long playlistId,
        String publicSessionId,
        Long trackId,
        String eventType,
        Integer positionMs,
        Integer durationMs,
        Instant occurredAt,
        Instant receivedAt
    ) {
    }
}
