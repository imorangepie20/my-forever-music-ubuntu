package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import io.myforevermusic.api.modules.publiccuration.application.PublicPlaylistPlayEventStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!local")
public class JpaPublicPlaylistPlayEventStore implements PublicPlaylistPlayEventStore {

    private final PublicPlaylistPlayEventRepository repository;

    public JpaPublicPlaylistPlayEventStore(PublicPlaylistPlayEventRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public StoredEvent record(RecordEvent event) {
        return repository.save(new PublicPlaylistPlayEventEntity(
            event.playlistId(),
            event.publicSessionId(),
            event.trackId(),
            event.eventType(),
            event.positionMs(),
            event.durationMs(),
            event.occurredAt(),
            event.receivedAt()
        )).toState();
    }
}
