package io.myforevermusic.api.modules.platform.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.myforevermusic.api.modules.auth.application.AuthRegistrationDraft;
import io.myforevermusic.api.modules.auth.infrastructure.local.InMemoryAuthAccountStore;
import io.myforevermusic.api.modules.platform.infrastructure.local.InMemoryPlatformConnectionStore;
import io.myforevermusic.api.modules.platform.infrastructure.local.InMemoryPlatformCredentialStore;
import io.myforevermusic.api.modules.platform.presentation.TidalDeviceAuthorizationStartRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TidalDeviceAuthorizationServiceTest {

    @Test
    void shouldRequestOnlyTidalDeviceAllowedScopesWhenMixedScopesAreConfigured() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/device_authorization", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {
                  "device_code": "tidal-device-code",
                  "user_code": "ABCD-EFGH",
                  "verification_uri": "https://link.tidal.com/device",
                  "verification_uri_complete": "https://link.tidal.com/device?user_code=ABCD-EFGH",
                  "expires_in": 300,
                  "interval": 5
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
            authAccountStore.register(new AuthRegistrationDraft(
                "user-001",
                "listener@example.com",
                "listener@example.com",
                "Listener",
                "hash",
                "tidal",
                false,
                "complete",
                Instant.now(),
                Instant.now(),
                Instant.now()
            ));

            PlatformOAuthProperties properties = new PlatformOAuthProperties();
            properties.getTidal().setEnabled(true);
            properties.getTidal().setClientId("tidal-client-id");
            properties.getTidal().setClientSecret("tidal-client-secret");
            properties.getTidal().setTokenUri("http://127.0.0.1:%d/token".formatted(server.getAddress().getPort()));
            properties.getTidal().setScopes(List.of(
                "r_usr",
                "w_usr",
                "w_sub",
                "r_stream",
                "playback",
                "entitlements.read"
            ));

            TidalDeviceAuthorizationService service = new TidalDeviceAuthorizationService(
                authAccountStore,
                new PlatformCatalogService(),
                new InMemoryPlatformConnectionStore(),
                new InMemoryPlatformCredentialStore(),
                properties,
                new ObjectMapper()
            );

            service.start(new TidalDeviceAuthorizationStartRequest("user-001"));

            assertThat(decodedFormValue(requestBody.get(), "scope"))
                .isEqualTo("r_usr w_usr w_sub");
        } finally {
            server.stop(0);
        }
    }

    private String decodedFormValue(String body, String key) {
        return Arrays.stream(body.split("&"))
            .map(part -> part.split("=", 2))
            .filter(parts -> parts.length == 2 && parts[0].equals(key))
            .map(parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8))
            .findFirst()
            .orElse("");
    }
}
