package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import io.myforevermusic.api.modules.publiccuration.application.PublicPlaybackSessionStore;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local")
public class JpaPublicPlaybackSessionStore implements PublicPlaybackSessionStore {

    private final PublicPlaybackSessionRepository repository;

    public JpaPublicPlaybackSessionStore(PublicPlaybackSessionRepository repository) {
        this.repository = repository;
    }

    @Override
    public StoredSession save(SessionDraft draft) {
        return repository.save(new PublicPlaybackSessionEntity(
            draft.sessionId(),
            draft.playlistId(),
            draft.tidalAccountLabel(),
            draft.accessToken(),
            draft.refreshToken(),
            draft.scopeSummary(),
            draft.expiresAt(),
            draft.createdAt()
        )).toState();
    }

    @Override
    public Optional<StoredSession> findActiveBySessionId(String sessionId, Instant now) {
        return repository.findById(sessionId)
            .filter(session -> session.getExpiresAt().isAfter(now))
            .map(PublicPlaybackSessionEntity::toState);
    }
}
