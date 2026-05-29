package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.modules.publiccuration.application.PublicPlaybackSessionStore;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JpaPublicPlaybackSessionStoreTest {

    @Test
    void shouldSaveAndFindActivePublicPlaybackSession() {
        PublicPlaybackSessionRepository repository = mock(PublicPlaybackSessionRepository.class);
        JpaPublicPlaybackSessionStore store = new JpaPublicPlaybackSessionStore(repository);
        Instant now = Instant.parse("2026-05-30T00:00:00Z");
        PublicPlaybackSessionEntity entity = new PublicPlaybackSessionEntity(
            "public-session-1",
            42L,
            "TIDAL Listener",
            "access-token",
            "refresh-token",
            "user.read, collection.read",
            now.plusSeconds(3600),
            now
        );

        when(repository.save(any(PublicPlaybackSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findById("public-session-1")).thenReturn(Optional.of(entity));

        PublicPlaybackSessionStore.StoredSession saved = store.save(new PublicPlaybackSessionStore.SessionDraft(
            "public-session-1",
            42L,
            "TIDAL Listener",
            "access-token",
            "refresh-token",
            "user.read, collection.read",
            now.plusSeconds(3600),
            now
        ));
        Optional<PublicPlaybackSessionStore.StoredSession> found =
            store.findActiveBySessionId("public-session-1", now.plusSeconds(60));

        assertThat(saved.sessionId()).isEqualTo("public-session-1");
        assertThat(saved.playlistId()).isEqualTo(42L);
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().playlistId()).isEqualTo(42L);
        assertThat(found.orElseThrow().tidalAccountLabel()).isEqualTo("TIDAL Listener");
        assertThat(found.orElseThrow().accessToken()).isEqualTo("access-token");
        assertThat(found.orElseThrow().refreshToken()).isEqualTo("refresh-token");
        assertThat(found.orElseThrow().scopeSummary()).isEqualTo("user.read, collection.read");
    }

    @Test
    void shouldIgnoreExpiredPublicPlaybackSession() {
        PublicPlaybackSessionRepository repository = mock(PublicPlaybackSessionRepository.class);
        JpaPublicPlaybackSessionStore store = new JpaPublicPlaybackSessionStore(repository);
        Instant now = Instant.parse("2026-05-30T00:00:00Z");
        PublicPlaybackSessionEntity entity = new PublicPlaybackSessionEntity(
            "public-session-1",
            42L,
            "TIDAL Listener",
            "access-token",
            "refresh-token",
            "user.read, collection.read",
            now.minusSeconds(5),
            now.minusSeconds(3600)
        );

        when(repository.findById("public-session-1")).thenReturn(Optional.of(entity));

        Optional<PublicPlaybackSessionStore.StoredSession> found =
            store.findActiveBySessionId("public-session-1", now);

        assertThat(found).isEmpty();
    }
}
