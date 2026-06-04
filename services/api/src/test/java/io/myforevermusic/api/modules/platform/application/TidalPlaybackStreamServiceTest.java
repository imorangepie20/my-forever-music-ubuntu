package io.myforevermusic.api.modules.platform.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TidalPlaybackStreamServiceTest {

    @Test
    void shouldDecodeDataUriDashManifestWithoutTreatingItAsCorrupt() throws IOException {
        String manifestPayload = """
            <MPD mediaPresentationDuration="PT3M31S">
                <Period>
                    <AdaptationSet mimeType="audio/mp4">
                        <Representation codecs="mp4a.40.2">
                            <BaseURL>https://audio.example.test/segment.m4s</BaseURL>
                        </Representation>
                    </AdaptationSet>
                </Period>
            </MPD>
            """;
        String dataUriManifest = "data:application/dash+xml;base64,%s".formatted(
            Base64.getEncoder().encodeToString(manifestPayload.getBytes(StandardCharsets.UTF_8))
        );
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tracks/track-001/playbackinfo", exchange -> {
            byte[] response = """
                {
                  "assetPresentation": "FULL",
                  "audioQuality": "LOSSLESS",
                  "codec": "AAC",
                  "bitRate": 1411,
                  "sampleRate": 44100,
                  "bitDepth": 16,
                  "manifest": "%s"
                }
                """.formatted(dataUriManifest).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            TidalPlaybackStreamService service = new TidalPlaybackStreamService(
                properties(server),
                new ObjectMapper()
            );

            TidalPlaybackStreamService.TidalPlaybackStream stream = service.resolve(
                credential(),
                "track-001",
                "LOSSLESS"
            );

            assertThat(stream.assetPresentation()).isEqualTo("FULL");
            assertThat(stream.manifestMimeType()).isEqualTo("application/dash+xml");
            assertThat(stream.streamUrl()).isEqualTo(dataUriManifest);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldNormalizeBase64DashManifestWithoutTreatingItAsJson() throws IOException {
        String manifestPayload = """
            <MPD xmlns:cenc="urn:mpeg:cenc:2013" mediaPresentationDuration="PT4M54S">
                <Period>
                    <AdaptationSet mimeType="audio/mp4">
                        <Representation codecs="mp4a.40.2">
                            <BaseURL>https://audio.example.test/billie-jean-segment.m4s</BaseURL>
                        </Representation>
                    </AdaptationSet>
                </Period>
            </MPD>
            """;
        String encodedManifest = Base64.getEncoder().encodeToString(manifestPayload.getBytes(StandardCharsets.UTF_8));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tracks/track-002/playbackinfo", exchange -> {
            byte[] response = """
                {
                  "assetPresentation": "FULL",
                  "audioQuality": "LOSSLESS",
                  "codec": "AAC",
                  "manifest": "%s"
                }
                """.formatted(encodedManifest).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            TidalPlaybackStreamService service = new TidalPlaybackStreamService(
                properties(server),
                new ObjectMapper()
            );

            TidalPlaybackStreamService.TidalPlaybackStream stream = service.resolve(
                credential(),
                "track-002",
                "LOSSLESS"
            );

            assertThat(stream.assetPresentation()).isEqualTo("FULL");
            assertThat(stream.manifestMimeType()).isEqualTo("application/dash+xml");
            assertThat(stream.encryptionType()).isNull();
            assertThat(stream.streamUrl()).isEqualTo("data:application/dash+xml;base64,%s".formatted(encodedManifest));
        } finally {
            server.stop(0);
        }
    }

    private PlatformOAuthProperties properties(HttpServer server) {
        PlatformOAuthProperties properties = new PlatformOAuthProperties();
        properties.getTidal().setLegacyApiBaseUri("http://127.0.0.1:%d".formatted(server.getAddress().getPort()));
        properties.getTidal().setCountryCode("KR");
        return properties;
    }

    private PlatformAccountCredential credential() {
        Instant now = Instant.parse("2026-05-31T00:00:00Z");
        return new PlatformAccountCredential(
            "user-001",
            "tidal",
            "oauth",
            "tidal-user-001",
            "TIDAL User",
            "access-token",
            "refresh-token",
            "Bearer",
            "r_usr w_usr w_sub r_stream playback entitlements.read",
            now.plusSeconds(3600),
            now,
            now
        );
    }
}
