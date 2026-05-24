package io.myforevermusic.api.modules.platform.infrastructure.tidal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.myforevermusic.api.modules.platform.application.PlatformAuthorizationSession;
import io.myforevermusic.api.modules.platform.application.PlatformOAuthProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TidalAuthorizationCodeExchangeClientTest {

    @Test
    void shouldExchangeAuthorizationCodeUsingTidalPkceForm() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {
                  "access_token": "tidal-access-token",
                  "token_type": "Bearer",
                  "scope": "r_usr w_usr r_stream",
                  "expires_in": 86400,
                  "refresh_token": "tidal-refresh-token"
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            PlatformOAuthProperties properties = new PlatformOAuthProperties();
            properties.getTidal().setClientId("tidal-client-id");
            properties.getTidal().setTokenUri("http://127.0.0.1:%d/token".formatted(server.getAddress().getPort()));
            TidalAuthorizationCodeExchangeClient client = new TidalAuthorizationCodeExchangeClient(
                properties,
                new ObjectMapper()
            );
            PlatformAuthorizationSession session = new PlatformAuthorizationSession(
                "oauth-state",
                "user-001",
                "tidal",
                "TIDAL",
                "tidal-pkce-draft",
                "external_browser_redirect",
                List.of("r_usr", "w_usr", "r_stream"),
                "pending",
                null,
                "https://login.tidal.com/authorize?...",
                "http://localhost:5173/platforms/oauth/callback",
                "tidal-code-verifier",
                Instant.now().plusSeconds(600),
                Instant.now(),
                null
            );

            var result = client.exchangeAuthorizationCode(session, "tidal-auth-code");

            assertThat(result.accessToken()).isEqualTo("tidal-access-token");
            assertThat(result.refreshToken()).isEqualTo("tidal-refresh-token");
            assertThat(result.grantedScopes()).containsExactly("r_usr", "w_usr", "r_stream");
            assertThat(result.accessTokenExpiresAt()).isAfter(Instant.now());
            assertThat(requestBody.get()).contains("grant_type=authorization_code");
            assertThat(requestBody.get()).contains("client_id=tidal-client-id");
            assertThat(requestBody.get()).contains("code=tidal-auth-code");
            assertThat(requestBody.get()).contains("redirect_uri=http%3A%2F%2Flocalhost%3A5173%2Fplatforms%2Foauth%2Fcallback");
            assertThat(requestBody.get()).contains("code_verifier=tidal-code-verifier");
        } finally {
            server.stop(0);
        }
    }
}
