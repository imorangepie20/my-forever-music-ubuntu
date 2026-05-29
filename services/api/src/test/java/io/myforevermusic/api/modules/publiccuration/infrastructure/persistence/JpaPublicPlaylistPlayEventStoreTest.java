package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.modules.publiccuration.application.PublicPlaylistPlayEventStore;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JpaPublicPlaylistPlayEventStoreTest {

    @Test
    void shouldSavePublicPlaybackEvent() {
        PublicPlaylistPlayEventRepository repository = mock(PublicPlaylistPlayEventRepository.class);
        JpaPublicPlaylistPlayEventStore store = new JpaPublicPlaylistPlayEventStore(repository);
        when(repository.save(any(PublicPlaylistPlayEventEntity.class))).thenAnswer(invocation -> {
            PublicPlaylistPlayEventEntity entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "eventId", 501L);
            return entity;
        });

        PublicPlaylistPlayEventStore.StoredEvent stored = store.record(new PublicPlaylistPlayEventStore.RecordEvent(
            10L,
            "public-session-001",
            100L,
            "play_started",
            0,
            181000,
            Instant.parse("2026-05-30T02:10:00Z"),
            Instant.parse("2026-05-30T02:10:01Z")
        ));

        assertThat(stored.eventId()).isEqualTo(501L);
        assertThat(stored.playlistId()).isEqualTo(10L);
        assertThat(stored.publicSessionId()).isEqualTo("public-session-001");
        assertThat(stored.trackId()).isEqualTo(100L);
        assertThat(stored.eventType()).isEqualTo("play_started");
    }
}
