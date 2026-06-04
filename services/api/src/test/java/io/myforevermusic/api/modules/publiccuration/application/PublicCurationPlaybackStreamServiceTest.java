package io.myforevermusic.api.modules.publiccuration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.modules.platform.application.PlatformAccountCredential;
import io.myforevermusic.api.modules.platform.application.TidalPlaybackStreamService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PublicCurationPlaybackStreamServiceTest {

    @Test
    void shouldResolveStreamOnlyWhenSessionAndTrackBelongToPublishedPlaylist() {
        Instant now = Instant.now();
        PublicCurationPlaylistStore.StoredTrack track = publishedTrack(now);
        PublicCurationPlaylistStore.StoredPlaylist playlist = publishedPlaylist(track, now);
        FakePlaylistStore playlistStore = new FakePlaylistStore(playlist);
        FakeSessionStore sessionStore = new FakeSessionStore(new PublicPlaybackSessionStore.StoredSession(
            "public-curation-session-1",
            42L,
            "TIDAL Listener",
            "public-access-token",
            "public-refresh-token",
            "r_usr w_usr w_sub r_stream",
            now.plusSeconds(3600),
            now,
            null
        ));
        TidalPlaybackStreamService tidalStreamService = mock(TidalPlaybackStreamService.class);
        when(tidalStreamService.resolve(
            org.mockito.ArgumentMatchers.any(PlatformAccountCredential.class),
            org.mockito.ArgumentMatchers.eq("10001"),
            org.mockito.ArgumentMatchers.eq("HIGH")
        )).thenReturn(new TidalPlaybackStreamService.TidalPlaybackStream(
            "10001",
            "KR",
            "HIGH",
            "HIGH",
            "aac",
            320,
            44100,
            16,
            "FULL",
            "application/vnd.apple.mpegurl",
            null,
            null,
            181.0,
            "https://media.example/10001.m3u8"
        ));
        PublicCurationPlaybackStreamService service = new PublicCurationPlaybackStreamService(
            playlistStore,
            sessionStore,
            tidalStreamService
        );

        PublicCurationPlaybackStreamService.PublicStreamResponse response =
            service.stream("rainy-night", "public-curation-session-1", 100L, "HIGH");

        assertThat(response.service()).isEqualTo("public-curation-playback-stream");
        assertThat(response.playlistId()).isEqualTo(42L);
        assertThat(response.publicSessionId()).isEqualTo("public-curation-session-1");
        assertThat(response.trackId()).isEqualTo(100L);
        assertThat(response.tidalTrackId()).isEqualTo("10001");
        assertThat(response.streamUrl()).isEqualTo("https://media.example/10001.m3u8");

        ArgumentCaptor<PlatformAccountCredential> credentialCaptor =
            ArgumentCaptor.forClass(PlatformAccountCredential.class);
        verify(tidalStreamService).resolve(credentialCaptor.capture(), org.mockito.ArgumentMatchers.eq("10001"), org.mockito.ArgumentMatchers.eq("HIGH"));
        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("public-access-token");
    }

    @Test
    void shouldFetchAnalysisAudioUsingThePublicPlaybackSessionCredential() {
        Instant now = Instant.now();
        FakePlaylistStore playlistStore = new FakePlaylistStore(publishedPlaylist(publishedTrack(now), now));
        FakeSessionStore sessionStore = new FakeSessionStore(publicSession(now));
        TidalPlaybackStreamService tidalStreamService = mock(TidalPlaybackStreamService.class);
        TidalPlaybackStreamService.TidalPlaybackStream stream = publicStream();
        when(tidalStreamService.resolve(
            org.mockito.ArgumentMatchers.any(PlatformAccountCredential.class),
            org.mockito.ArgumentMatchers.eq("10001"),
            org.mockito.ArgumentMatchers.eq("HIGH")
        )).thenReturn(stream);
        when(tidalStreamService.fetchAnalysisAudio(stream))
            .thenReturn(new TidalPlaybackStreamService.TidalAnalysisAudio(
                "HIGH",
                "audio/mp4",
                new byte[] { 1, 2, 3 }
            ));
        PublicCurationPlaybackStreamService service = new PublicCurationPlaybackStreamService(
            playlistStore,
            sessionStore,
            tidalStreamService
        );

        TidalPlaybackStreamService.TidalAnalysisAudio audio =
            service.analysisAudio("rainy-night", "public-curation-session-1", 100L, "HIGH");

        assertThat(audio.bytes()).containsExactly(1, 2, 3);
        ArgumentCaptor<PlatformAccountCredential> credentialCaptor =
            ArgumentCaptor.forClass(PlatformAccountCredential.class);
        verify(tidalStreamService).resolve(
            credentialCaptor.capture(),
            org.mockito.ArgumentMatchers.eq("10001"),
            org.mockito.ArgumentMatchers.eq("HIGH")
        );
        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("public-access-token");
        verify(tidalStreamService).fetchAnalysisAudio(stream);
    }

    private PublicPlaybackSessionStore.StoredSession publicSession(Instant now) {
        return new PublicPlaybackSessionStore.StoredSession(
            "public-curation-session-1",
            42L,
            "TIDAL Listener",
            "public-access-token",
            "public-refresh-token",
            "r_usr w_usr w_sub r_stream",
            now.plusSeconds(3600),
            now,
            null
        );
    }

    private TidalPlaybackStreamService.TidalPlaybackStream publicStream() {
        return new TidalPlaybackStreamService.TidalPlaybackStream(
            "10001",
            "KR",
            "HIGH",
            "HIGH",
            "aac",
            320,
            44100,
            16,
            "FULL",
            "audio/mp4",
            null,
            null,
            181.0,
            "https://media.example/10001.mp4"
        );
    }

    private PublicCurationPlaylistStore.StoredTrack publishedTrack(Instant now) {
        return new PublicCurationPlaylistStore.StoredTrack(
            100L,
            42L,
            1,
            "pms_user_track",
            "track-100",
            "Rain Street",
            "Blue Trio",
            "Night Walk",
            null,
            181000,
            "KRA000000001",
            "10001",
            "tidal:track:10001",
            "https://tidal.com/browse/track/10001",
            0.94,
            "{}",
            "비 오는 밤의 첫 분위기를 만든다.",
            now
        );
    }

    private PublicCurationPlaylistStore.StoredPlaylist publishedPlaylist(
        PublicCurationPlaylistStore.StoredTrack track,
        Instant now
    ) {
        return new PublicCurationPlaylistStore.StoredPlaylist(
            42L,
            "rainy-night",
            "비 오는 밤",
            null,
            null,
            "prompt",
            "{}",
            "published",
            "poster-dark",
            "public-curation-deterministic-v1",
            1,
            181000L,
            now,
            "admin-001",
            now,
            now,
            List.of(track),
            null
        );
    }

    private static final class FakePlaylistStore implements PublicCurationPlaylistStore {
        private final StoredPlaylist playlist;

        private FakePlaylistStore(StoredPlaylist playlist) {
            this.playlist = playlist;
        }

        @Override
        public StoredPlaylist createDraft(CreateDraft draft) {
            throw new UnsupportedOperationException("createDraft is not used in this test.");
        }

        @Override
        public StoredPlaylist publish(Long playlistId, Instant publishedAt) {
            throw new UnsupportedOperationException("publish is not used in this test.");
        }

        @Override
        public void delete(Long playlistId) {
            throw new UnsupportedOperationException("delete is not used in this test.");
        }

        @Override
        public List<StoredPlaylistSummary> findRecentForAdmin(int limit) {
            throw new UnsupportedOperationException("findRecentForAdmin is not used in this test.");
        }

        @Override
        public Optional<StoredPlaylist> findPublishedBySlug(String slug) {
            return playlist.slug().equals(slug) ? Optional.of(playlist) : Optional.empty();
        }
    }

    private static final class FakeSessionStore implements PublicPlaybackSessionStore {
        private final StoredSession session;

        private FakeSessionStore(StoredSession session) {
            this.session = session;
        }

        @Override
        public StoredSession save(SessionDraft draft) {
            throw new UnsupportedOperationException("save is not used in this test.");
        }

        @Override
        public Optional<StoredSession> findActiveBySessionId(String sessionId, Instant now) {
            return session.sessionId().equals(sessionId) && session.expiresAt().isAfter(now)
                ? Optional.of(session)
                : Optional.empty();
        }
    }
}
