package io.myforevermusic.api.modules.publiccuration.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.myforevermusic.api.modules.platform.application.PlatformAccountProfileResolverRegistry;
import io.myforevermusic.api.modules.platform.application.PlatformAuthorizationCodeExchangeClient;
import io.myforevermusic.api.modules.platform.application.PlatformAuthorizationCodeExchangeRegistry;
import io.myforevermusic.api.modules.platform.application.PlatformAuthorizationSession;
import io.myforevermusic.api.modules.platform.application.PlatformOAuthProperties;
import io.myforevermusic.api.modules.platform.application.PlatformTokenExchangeResult;
import io.myforevermusic.api.modules.platform.infrastructure.local.InMemoryPlatformAuthorizationSessionStore;
import io.myforevermusic.api.modules.platform.infrastructure.local.InMemoryPlatformCredentialStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PublicCurationTidalOAuthServiceTest {

    @Test
    void shouldCompletePublicTidalOAuthWithoutCreatingUserPlatformCredential() {
        PublicCurationPlaylistStore.StoredPlaylist playlist = publishedPlaylist(42L, "rainy-night");
        FakePublicCurationPlaylistStore playlistStore = new FakePublicCurationPlaylistStore(playlist);
        InMemoryPlatformAuthorizationSessionStore authorizationSessionStore = new InMemoryPlatformAuthorizationSessionStore();
        InMemoryPlatformCredentialStore credentialStore = new InMemoryPlatformCredentialStore();
        FakePublicPlaybackSessionStore publicSessionStore = new FakePublicPlaybackSessionStore();
        PlatformOAuthProperties properties = new PlatformOAuthProperties();
        properties.getTidal().setEnabled(true);
        properties.getTidal().setClientId("tidal-client-id");
        properties.getTidal().setRedirectUri("https://approid.team/platforms/oauth/callback");

        PublicCurationTidalOAuthService service = new PublicCurationTidalOAuthService(
            playlistStore,
            authorizationSessionStore,
            publicSessionStore,
            new PlatformAuthorizationCodeExchangeRegistry(List.of(fakeTidalExchangeClient())),
            new PlatformAccountProfileResolverRegistry(List.of()),
            properties
        );

        PublicCurationTidalOAuthService.PublicTidalOAuthStartResponse start = service.start("rainy-night");
        PublicCurationTidalOAuthService.PublicTidalOAuthCompleteResponse complete =
            service.complete("rainy-night", start.authorization().state(), "tidal-code");

        assertThat(start.authorization().state()).startsWith("public-curation-oauth-");
        assertThat(start.authorization().externalAuthorizationUrl())
            .contains("https://login.tidal.com/authorize")
            .contains("client_id=tidal-client-id")
            .contains("state=")
            .contains("scope=user.read%20collection.read%20playlists.read")
            .contains("code_challenge_method=S256");
        assertThat(complete.session().sessionId()).startsWith("public-curation-session-");
        assertThat(complete.returnPath()).isEqualTo("/share/playlists/rainy-night?playback=ready");
        assertThat(publicSessionStore.saved).isNotNull();
        assertThat(publicSessionStore.saved.playlistId()).isEqualTo(42L);
        assertThat(credentialStore.findByUserIdAndPlatformId("public-curation:42", "tidal")).isEmpty();
        assertThat(authorizationSessionStore.findByState(start.authorization().state()).orElseThrow().isCompleted()).isTrue();

        PublicCurationTidalOAuthService.PublicTidalPlaybackSessionResponse playbackSession =
            service.session("rainy-night", complete.session().sessionId());

        assertThat(playbackSession.status()).isEqualTo("ready");
        assertThat(playbackSession.session().playlistId()).isEqualTo(42L);
        assertThat(playbackSession.session().sessionId()).isEqualTo(complete.session().sessionId());
    }

    private PlatformAuthorizationCodeExchangeClient fakeTidalExchangeClient() {
        return new PlatformAuthorizationCodeExchangeClient() {
            @Override
            public boolean supports(PlatformAuthorizationSession session) {
                return "tidal-pkce-draft".equals(session.authorizationMode());
            }

            @Override
            public PlatformTokenExchangeResult exchangeAuthorizationCode(
                PlatformAuthorizationSession session,
                String authorizationCode
            ) {
                assertThat(session.userId()).isEqualTo("public-curation:42");
                assertThat(authorizationCode).isEqualTo("tidal-code");
                return new PlatformTokenExchangeResult(
                    "tidal-access-token",
                    "tidal-refresh-token",
                    "Bearer",
                    List.of("user.read", "collection.read", "playlists.read"),
                    Instant.parse("2026-05-30T01:00:00Z")
                );
            }
        };
    }

    private PublicCurationPlaylistStore.StoredPlaylist publishedPlaylist(Long playlistId, String slug) {
        Instant now = Instant.parse("2026-05-30T00:00:00Z");
        return new PublicCurationPlaylistStore.StoredPlaylist(
            playlistId,
            slug,
            "비 오는 밤의 큐레이션",
            "TIDAL-ready public playlist",
            "공개 공유용 플레이리스트입니다.",
            "rainy night",
            "{}",
            "published",
            "poster-dark",
            "public-curation-deterministic-v1",
            0,
            0L,
            now,
            "admin-001",
            now,
            now,
            List.of(),
            null
        );
    }

    private static final class FakePublicCurationPlaylistStore implements PublicCurationPlaylistStore {
        private final StoredPlaylist playlist;

        private FakePublicCurationPlaylistStore(StoredPlaylist playlist) {
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
        public Optional<StoredPlaylist> findPublishedBySlug(String slug) {
            return playlist.slug().equals(slug) ? Optional.of(playlist) : Optional.empty();
        }
    }

    private static final class FakePublicPlaybackSessionStore implements PublicPlaybackSessionStore {
        private SessionDraft saved;

        @Override
        public StoredSession save(SessionDraft draft) {
            this.saved = draft;
            return new StoredSession(
                draft.sessionId(),
                draft.playlistId(),
                draft.tidalAccountLabel(),
                draft.accessToken(),
                draft.refreshToken(),
                draft.scopeSummary(),
                draft.expiresAt(),
                draft.createdAt(),
                null
            );
        }

        @Override
        public Optional<StoredSession> findActiveBySessionId(String sessionId, Instant now) {
            if (saved == null || !saved.sessionId().equals(sessionId) || !saved.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            return Optional.of(new StoredSession(
                saved.sessionId(),
                saved.playlistId(),
                saved.tidalAccountLabel(),
                saved.accessToken(),
                saved.refreshToken(),
                saved.scopeSummary(),
                saved.expiresAt(),
                saved.createdAt(),
                null
            ));
        }
    }
}
