package io.myforevermusic.api.modules.publiccuration.application;

import java.time.Instant;
import java.util.Optional;

public interface PublicPlaybackSessionStore {

    StoredSession save(SessionDraft draft);

    Optional<StoredSession> findActiveBySessionId(String sessionId, Instant now);

    record SessionDraft(
        String sessionId,
        Long playlistId,
        String tidalAccountLabel,
        String accessToken,
        String refreshToken,
        String scopeSummary,
        Instant expiresAt,
        Instant createdAt
    ) {
    }

    record StoredSession(
        String sessionId,
        Long playlistId,
        String tidalAccountLabel,
        String scopeSummary,
        Instant expiresAt,
        Instant createdAt,
        Instant lastUsedAt
    ) {
    }
}
