package io.myforevermusic.api.modules.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.myforevermusic.api.modules.auth.application.AuthRegistrationService;
import io.myforevermusic.api.modules.auth.infrastructure.local.InMemoryAuthAccountStore;
import io.myforevermusic.api.modules.auth.presentation.AuthRegistrationRequest;
import io.myforevermusic.api.modules.platform.infrastructure.local.InMemoryPlatformAuthorizationSessionStore;
import io.myforevermusic.api.modules.platform.infrastructure.local.InMemoryPlatformCredentialStore;
import io.myforevermusic.api.modules.platform.infrastructure.local.InMemoryPlatformConnectionStore;
import io.myforevermusic.api.modules.platform.infrastructure.sandbox.SandboxAuthorizationCodeExchangeClient;
import io.myforevermusic.api.modules.platform.presentation.PlatformAuthorizationCompleteRequest;
import io.myforevermusic.api.modules.platform.presentation.PlatformAuthorizationStartRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PlatformAuthorizationServiceTest {

    @Test
    void shouldRejectSpotifyAuthorizationWhenOAuthIsNotConfigured() {
        InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
        InMemoryPlatformCredentialStore platformCredentialStore = new InMemoryPlatformCredentialStore();
        AuthRegistrationService authRegistrationService = new AuthRegistrationService(
            authAccountStore,
            new BCryptPasswordEncoder()
        );
        String userId = authRegistrationService.register(new AuthRegistrationRequest(
            "Forever Listener",
            "listener@example.com",
            "music2026",
            "spotify",
            false,
            true,
            true
        )).user().userId();

        PlatformAuthorizationService service = new PlatformAuthorizationService(
            authAccountStore,
            new PlatformCatalogService(),
            new InMemoryPlatformAuthorizationSessionStore(),
            new InMemoryPlatformConnectionStore(),
            platformCredentialStore,
            new PlatformAuthorizationCodeExchangeRegistry(List.of(new SandboxAuthorizationCodeExchangeClient())),
            new PlatformAccountProfileResolverRegistry(List.of()),
            new PlatformOAuthProperties()
        );

        assertThatThrownBy(() -> service.startAuthorization(new PlatformAuthorizationStartRequest(userId, "spotify")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Spotify OAuth is not configured");
    }

    @Test
    void shouldStartTidalPkceDraftWhenOAuthIsConfigured() {
        InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
        AuthRegistrationService authRegistrationService = new AuthRegistrationService(
            authAccountStore,
            new BCryptPasswordEncoder()
        );
        String userId = authRegistrationService.register(new AuthRegistrationRequest(
            "Forever Listener",
            "tidal-next@example.com",
            "music2026",
            "spotify",
            false,
            true,
            true
        )).user().userId();

        PlatformOAuthProperties properties = new PlatformOAuthProperties();
        properties.getTidal().setEnabled(true);
        properties.getTidal().setClientId("tidal-client-id");
        properties.getTidal().setRedirectUri("http://localhost:5173/platforms/oauth/callback");
        properties.getTidal().setScopes(List.of(
            "r_usr",
            "w_usr",
            "w_sub",
            "r_stream",
            "playback",
            "entitlements.read"
        ));

        PlatformAuthorizationService service = new PlatformAuthorizationService(
            authAccountStore,
            new PlatformCatalogService(),
            new InMemoryPlatformAuthorizationSessionStore(),
            new InMemoryPlatformConnectionStore(),
            new InMemoryPlatformCredentialStore(),
            new PlatformAuthorizationCodeExchangeRegistry(List.of(new SandboxAuthorizationCodeExchangeClient())),
            new PlatformAccountProfileResolverRegistry(List.of()),
            properties
        );

        var start = service.startAuthorization(new PlatformAuthorizationStartRequest(userId, "tidal"));

        assertThat(start.authorization().platformId()).isEqualTo("tidal");
        assertThat(start.authorization().authorizationMode()).isEqualTo("tidal-pkce-draft");
        assertThat(start.authorization().externalAuthorizationUrl())
            .contains("https://login.tidal.com/authorize")
            .contains("client_id=tidal-client-id")
            .contains("user.read")
            .contains("collection.read")
            .contains("playlists.read")
            .contains("code_challenge_method=S256");
        assertThat(start.authorization().requestedScopes())
            .containsExactly("user.read", "collection.read", "playlists.read");
        assertThat(start.authorization().externalAuthorizationUrl())
            .doesNotContain("r_usr", "w_usr", "w_sub", "r_stream", "playback", "entitlements.read");
    }

    @Test
    void shouldStartSpotifyPkceDraftAndStoreExchangedToken() {
        InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
        InMemoryPlatformCredentialStore platformCredentialStore = new InMemoryPlatformCredentialStore();
        AuthRegistrationService authRegistrationService = new AuthRegistrationService(
            authAccountStore,
            new BCryptPasswordEncoder()
        );
        String userId = authRegistrationService.register(new AuthRegistrationRequest(
            "Forever Listener",
            "listener@example.com",
            "music2026",
            "spotify",
            false,
            true,
            true
        )).user().userId();

        PlatformOAuthProperties properties = new PlatformOAuthProperties();
        properties.getSpotify().setEnabled(true);
        properties.getSpotify().setClientId("spotify-client-id");
        properties.getSpotify().setRedirectUri("http://localhost:5173/platforms/oauth/callback");

        PlatformAuthorizationCodeExchangeClient fakeSpotifyClient = new PlatformAuthorizationCodeExchangeClient() {
            @Override
            public boolean supports(PlatformAuthorizationSession session) {
                return "spotify-pkce-draft".equals(session.authorizationMode());
            }

            @Override
            public PlatformTokenExchangeResult exchangeAuthorizationCode(
                PlatformAuthorizationSession session,
                String authorizationCode
            ) {
                return new PlatformTokenExchangeResult(
                    "spotify-access-token",
                    "spotify-refresh-token",
                    "Bearer",
                    List.of("user-read-email", "playlist-read-private"),
                    Instant.parse("2026-05-04T00:00:00Z")
                );
            }
        };

        PlatformAuthorizationService service = new PlatformAuthorizationService(
            authAccountStore,
            new PlatformCatalogService(),
            new InMemoryPlatformAuthorizationSessionStore(),
            new InMemoryPlatformConnectionStore(),
            platformCredentialStore,
            new PlatformAuthorizationCodeExchangeRegistry(
                List.of(new SandboxAuthorizationCodeExchangeClient(), fakeSpotifyClient)
            ),
            new PlatformAccountProfileResolverRegistry(List.of()),
            properties
        );

        var start = service.startAuthorization(new PlatformAuthorizationStartRequest(userId, "spotify"));
        var complete = service.completeAuthorization(
            new PlatformAuthorizationCompleteRequest(
                userId,
                "spotify",
                start.authorization().state(),
                null,
                "spotify-auth-code"
            )
        );
        var credential = platformCredentialStore.findByUserIdAndPlatformId(userId, "spotify").orElseThrow();

        assertThat(start.authorization().authorizationMode()).isEqualTo("spotify-pkce-draft");
        assertThat(start.authorization().authorizationChannel()).isEqualTo("external_browser_redirect");
        assertThat(start.authorization().externalAuthorizationUrl())
            .contains("accounts.spotify.com/authorize")
            .contains("show_dialog=true");
        assertThat(complete.status()).isEqualTo("authorization_completed");
        assertThat(credential.accessToken()).isEqualTo("spotify-access-token");
        assertThat(credential.refreshToken()).isEqualTo("spotify-refresh-token");
        assertThat(credential.scopeSummary()).isEqualTo("user-read-email, playlist-read-private");
    }

    @Test
    void shouldResetExistingSpotifyAuthorizationWhenStartingNewAuthorization() {
        InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
        InMemoryPlatformCredentialStore platformCredentialStore = new InMemoryPlatformCredentialStore();
        InMemoryPlatformConnectionStore platformConnectionStore = new InMemoryPlatformConnectionStore();
        AuthRegistrationService authRegistrationService = new AuthRegistrationService(
            authAccountStore,
            new BCryptPasswordEncoder()
        );
        String userId = authRegistrationService.register(new AuthRegistrationRequest(
            "Forever Listener",
            "reconnect-listener@example.com",
            "music2026",
            "spotify",
            false,
            true,
            true
        )).user().userId();
        Instant connectedAt = Instant.parse("2026-05-03T00:00:00Z");

        platformCredentialStore.save(new PlatformAccountCredential(
            userId,
            "spotify",
            "spotify-pkce-draft",
            "old-spotify-user",
            "Old Spotify User",
            "old-access-token",
            "old-refresh-token",
            "Bearer",
            "user-read-email",
            Instant.parse("2026-05-04T00:00:00Z"),
            connectedAt,
            connectedAt
        ));
        platformConnectionStore.connect(new PlatformConnectionDraft(
            userId,
            "spotify",
            "spotify-pkce-draft",
            "Old Spotify User",
            "user-read-email",
            true,
            connectedAt,
            connectedAt
        ));

        PlatformOAuthProperties properties = new PlatformOAuthProperties();
        properties.getSpotify().setEnabled(true);
        properties.getSpotify().setClientId("spotify-client-id");
        properties.getSpotify().setRedirectUri("http://localhost:5173/platforms/oauth/callback");

        PlatformAuthorizationService service = new PlatformAuthorizationService(
            authAccountStore,
            new PlatformCatalogService(),
            new InMemoryPlatformAuthorizationSessionStore(),
            platformConnectionStore,
            platformCredentialStore,
            new PlatformAuthorizationCodeExchangeRegistry(List.of(new SandboxAuthorizationCodeExchangeClient())),
            new PlatformAccountProfileResolverRegistry(List.of()),
            properties
        );

        service.startAuthorization(new PlatformAuthorizationStartRequest(userId, "spotify"));

        assertThat(platformCredentialStore.findByUserIdAndPlatformId(userId, "spotify")).isEmpty();
        assertThat(platformConnectionStore.findByUserId(userId))
            .filteredOn(state -> state.platformId().equals("spotify"))
            .singleElement()
            .satisfies(state -> {
                assertThat(state.connected()).isFalse();
                assertThat(state.connectionStatus()).isEqualTo("not_connected");
            });
    }

    @Test
    void shouldRejectSpotifyAuthorizationCompleteWhenProfileVerificationFails() {
        InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
        InMemoryPlatformCredentialStore platformCredentialStore = new InMemoryPlatformCredentialStore();
        AuthRegistrationService authRegistrationService = new AuthRegistrationService(
            authAccountStore,
            new BCryptPasswordEncoder()
        );
        String userId = authRegistrationService.register(new AuthRegistrationRequest(
            "Forever Listener",
            "blocked-listener@example.com",
            "music2026",
            "spotify",
            false,
            true,
            true
        )).user().userId();

        PlatformOAuthProperties properties = new PlatformOAuthProperties();
        properties.getSpotify().setEnabled(true);
        properties.getSpotify().setClientId("spotify-client-id");
        properties.getSpotify().setRedirectUri("http://localhost:5173/platforms/oauth/callback");

        PlatformAuthorizationCodeExchangeClient fakeSpotifyClient = new PlatformAuthorizationCodeExchangeClient() {
            @Override
            public boolean supports(PlatformAuthorizationSession session) {
                return "spotify-pkce-draft".equals(session.authorizationMode());
            }

            @Override
            public PlatformTokenExchangeResult exchangeAuthorizationCode(
                PlatformAuthorizationSession session,
                String authorizationCode
            ) {
                return new PlatformTokenExchangeResult(
                    "spotify-access-token",
                    "spotify-refresh-token",
                    "Bearer",
                    List.of("user-read-email", "playlist-read-private"),
                    Instant.parse("2026-05-04T00:00:00Z")
                );
            }
        };
        PlatformAccountProfileResolver blockedSpotifyProfileResolver = new PlatformAccountProfileResolver() {
            @Override
            public boolean supports(String platformId) {
                return "spotify".equals(platformId);
            }

            @Override
            public PlatformAccountProfile resolve(PlatformAccountCredential credential) {
                throw new IllegalArgumentException("Spotify API request failed (403): user is not allowlisted");
            }
        };

        PlatformAuthorizationService service = new PlatformAuthorizationService(
            authAccountStore,
            new PlatformCatalogService(),
            new InMemoryPlatformAuthorizationSessionStore(),
            new InMemoryPlatformConnectionStore(),
            platformCredentialStore,
            new PlatformAuthorizationCodeExchangeRegistry(
                List.of(new SandboxAuthorizationCodeExchangeClient(), fakeSpotifyClient)
            ),
            new PlatformAccountProfileResolverRegistry(List.of(blockedSpotifyProfileResolver)),
            properties
        );

        var start = service.startAuthorization(new PlatformAuthorizationStartRequest(userId, "spotify"));

        assertThatThrownBy(() -> service.completeAuthorization(
            new PlatformAuthorizationCompleteRequest(
                userId,
                "spotify",
                start.authorization().state(),
                null,
                "spotify-auth-code"
            )
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not allowlisted");
        assertThat(platformCredentialStore.findByUserIdAndPlatformId(userId, "spotify")).isEmpty();
    }
}
