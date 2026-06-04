package io.myforevermusic.api.modules.platform.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.modules.platform.application.PlatformAccountCredential;
import io.myforevermusic.api.modules.platform.application.PlatformCredentialResolution;
import io.myforevermusic.api.modules.platform.application.PlatformCredentialService;
import io.myforevermusic.api.modules.platform.application.PlatformOAuthProperties;
import io.myforevermusic.api.modules.platform.application.PlatformReconnectRequiredException;
import io.myforevermusic.api.modules.platform.application.TidalPlaybackStreamService;
import io.myforevermusic.api.modules.platform.application.TidalPlaybackStreamService.TidalPlaybackStream;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platforms/playback/tidal")
@Validated
public class TidalPlaybackStreamController {

    private static final String TIDAL_PLATFORM_ID = "tidal";
    // Scopes required for legacy v1 /tracks/{id}/playbackinfo streaming. `r_stream` is the
    // one that gates streaming access; without it TIDAL returns 403 even if r_usr/w_usr/w_sub
    // are all present. Update `app.platform.oauth.tidal.scopes` together with this list.
    private static final List<String> LEGACY_STREAMING_SCOPES = List.of("r_usr", "w_usr", "w_sub", "r_stream");

    private final PlatformCredentialService platformCredentialService;
    private final PlatformOAuthProperties platformOAuthProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final TidalPlaybackStreamService tidalPlaybackStreamService;

    public TidalPlaybackStreamController(
        PlatformCredentialService platformCredentialService,
        PlatformOAuthProperties platformOAuthProperties,
        ObjectMapper objectMapper,
        TidalPlaybackStreamService tidalPlaybackStreamService
    ) {
        this.platformCredentialService = platformCredentialService;
        this.platformOAuthProperties = platformOAuthProperties;
        this.objectMapper = objectMapper;
        this.tidalPlaybackStreamService = tidalPlaybackStreamService;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    @Operation(summary = "Resolve a verified full-track TIDAL stream URL")
    @GetMapping("/tracks/{track_id}/stream")
    public TidalPlaybackStreamResponse streamUrl(
        @RequestParam("user_id") @NotBlank String userId,
        @PathVariable("track_id") @NotBlank String trackId,
        @RequestParam(value = "quality", defaultValue = "LOSSLESS") String quality
    ) {
        PlatformAccountCredential credential = resolveCredential(userId);
        TidalPlaybackStream stream = tidalPlaybackStreamService.resolve(credential, trackId, quality);

        return new TidalPlaybackStreamResponse(
            "api",
            "ok",
            Instant.now(),
            userId,
            trackId,
            stream.countryCode(),
            stream.requestedQuality(),
            stream.audioQuality(),
            stream.codec(),
            stream.bitRate(),
            stream.sampleRate(),
            stream.bitDepth(),
            stream.assetPresentation(),
            stream.manifestMimeType(),
            stream.manifestCodecs(),
            stream.encryptionType(),
            stream.durationSeconds(),
            stream.streamUrl()
        );
    }

    @Operation(summary = "Fetch a verified full-track TIDAL audio stream for visual analysis")
    @GetMapping("/tracks/{track_id}/analysis-audio")
    public ResponseEntity<byte[]> analysisAudio(
        @RequestParam("user_id") @NotBlank String userId,
        @PathVariable("track_id") @NotBlank String trackId,
        @RequestParam(value = "quality", defaultValue = "HIGH") String quality
    ) {
        PlatformAccountCredential credential = resolveCredential(userId);
        String countryCode = countryCodeForCredential(credential);
        String requestedQuality = normalizeQuality(quality);
        TidalPlaybackInfo playbackInfo = fetchPlaybackInfo(credential, trackId, countryCode, requestedQuality);

        if (!"FULL".equalsIgnoreCase(playbackInfo.assetPresentation())) {
            throw new PlatformReconnectRequiredException(
                TIDAL_PLATFORM_ID,
                "TIDAL returned %s manifest for visual analysis. track=%s country=%s quality=%s"
                    .formatted(firstNonBlank(playbackInfo.assetPresentation(), "UNKNOWN"), trackId, countryCode, requestedQuality)
            );
        }
        if (playbackInfo.streamUrl() == null || playbackInfo.streamUrl().isBlank()) {
            throw new IllegalStateException(
                "TIDAL did not return an analysis audio URL. track=%s country=%s quality=%s"
                    .formatted(trackId, countryCode, requestedQuality)
            );
        }
        if (isPlaylistManifest(playbackInfo)) {
            throw new IllegalStateException(
                "TIDAL returned an HLS/DASH manifest for visual analysis; use the HLS segment capture path instead. track=%s mime=%s"
                    .formatted(trackId, playbackInfo.manifestMimeType())
            );
        }

        byte[] audioBytes = fetchAnalysisAudioBytes(playbackInfo.streamUrl());
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_TYPE, firstNonBlank(playbackInfo.manifestMimeType(), MediaType.APPLICATION_OCTET_STREAM_VALUE))
            .header("X-TIDAL-Requested-Quality", requestedQuality)
            .body(audioBytes);
    }

    private PlatformAccountCredential resolveCredential(String userId) {
        PlatformCredentialResolution resolution = platformCredentialService.resolveCredential(userId, TIDAL_PLATFORM_ID);
        if (PlatformCredentialResolution.STATUS_MISSING.equals(resolution.status())) {
            throw new IllegalArgumentException("No stored TIDAL credential exists for playback.");
        }
        if (!resolution.usable()) {
            throw new PlatformReconnectRequiredException(
                TIDAL_PLATFORM_ID,
                resolution.detail() == null || resolution.detail().isBlank()
                    ? "Reconnect TIDAL before starting playback."
                    : resolution.detail()
            );
        }
        return resolution.credential();
    }

    private TidalPlaybackInfo fetchPlaybackInfo(
        PlatformAccountCredential credential,
        String trackId,
        String countryCode,
        String quality
    ) {
        String uri = "%s/tracks/%s/playbackinfo?audioquality=%s&playbackmode=STREAM&assetpresentation=FULL&countryCode=%s"
            .formatted(
                trimTrailingSlash(platformOAuthProperties.getTidal().getLegacyApiBaseUri()),
                encode(trackId),
                encode(quality),
                encode(countryCode)
            );

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer %s".formatted(credential.accessToken()))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body() == null || response.body().isBlank()
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(response.body());

            if (response.statusCode() == 401 || response.statusCode() == 403) {
                boolean streamingScopeReady = hasAllScopes(credential, LEGACY_STREAMING_SCOPES);
                String hint = streamingScopeReady
                    ? "Token has the required streaming scopes; TIDAL still refused — likely track-level restriction (region/license) or partner approval issue."
                    : "Token is missing `r_stream` (required for v1 playbackinfo). Disconnect TIDAL and reconnect to grant the new scope set.";
                throw new PlatformReconnectRequiredException(
                    TIDAL_PLATFORM_ID,
                    "TIDAL playbackinfo access was denied (%s). scopes=%s streaming_scopes_present=%s detail=%s. %s"
                        .formatted(
                            response.statusCode(),
                            scopeList(credential),
                            streamingScopeReady,
                            firstNonBlank(safeErrorMessage(body), "(no body)"),
                            hint
                        )
                );
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                    "TIDAL playbackinfo request failed (%s): %s"
                        .formatted(response.statusCode(), firstNonBlank(safeErrorMessage(body), "empty response"))
                );
            }

            TidalManifest manifest = decodeManifest(body.path("manifest").asText(null));
            String bodyAssetPresentation = text(body, "assetPresentation", text(body, "trackPresentation", null));
            return new TidalPlaybackInfo(
                firstNonBlank(bodyAssetPresentation, manifest.assetPresentation()),
                text(body, "audioQuality", null),
                text(body, "codec", null),
                body.path("bitRate").isNumber() ? body.path("bitRate").asInt() : null,
                body.path("sampleRate").isNumber() ? body.path("sampleRate").asInt() : null,
                body.path("bitDepth").isNumber() ? body.path("bitDepth").asInt() : null,
                manifest.mimeType(),
                manifest.codecs(),
                manifest.encryptionType(),
                manifest.durationSeconds(),
                manifest.streamUrl()
            );
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL playbackinfo response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL playbackinfo request was interrupted.", exception);
        }
    }

    private TidalManifest decodeManifest(String manifest) {
        if (manifest == null || manifest.isBlank()) {
            return new TidalManifest(null, null, null, null, null, null);
        }
        if (manifest.startsWith("http")) {
            return new TidalManifest("direct-url", null, null, null, null, manifest);
        }
        if (manifest.startsWith("data:")) {
            return decodeDataUriManifest(manifest);
        }

        try {
            String decoded = new String(Base64.getDecoder().decode(manifest), StandardCharsets.UTF_8);
            JsonNode decodedJson = objectMapper.readTree(decoded);
            return new TidalManifest(
                text(decodedJson, "mimeType", null),
                text(decodedJson, "codecs", null),
                text(decodedJson, "encryptionType", null),
                text(decodedJson, "assetPresentation", text(decodedJson, "trackPresentation", null)),
                decodedJson.path("duration").isNumber() ? decodedJson.path("duration").asDouble() : null,
                firstManifestUrl(decodedJson)
            );
        } catch (RuntimeException | IOException exception) {
            throw new IllegalStateException("TIDAL playback manifest could not be decoded.", exception);
        }
    }

    private TidalManifest decodeDataUriManifest(String manifest) {
        int commaIndex = manifest.indexOf(',');
        if (commaIndex < 0 || commaIndex == manifest.length() - 1) {
            return new TidalManifest("data-uri", null, null, null, null, manifest);
        }

        String metadata = manifest.substring(0, commaIndex);
        String payload = manifest.substring(commaIndex + 1);
        String mimeType = metadata.substring("data:".length()).replace(";base64", "");
        try {
            String decoded = metadata.contains(";base64")
                ? new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8)
                : URLDecoder.decode(payload, StandardCharsets.UTF_8);
            JsonNode decodedJson = tryReadJson(decoded);
            if (decodedJson != null) {
                return new TidalManifest(
                    firstNonBlank(text(decodedJson, "mimeType", null), mimeType),
                    text(decodedJson, "codecs", null),
                    text(decodedJson, "encryptionType", null),
                    text(decodedJson, "assetPresentation", text(decodedJson, "trackPresentation", null)),
                    decodedJson.path("duration").isNumber() ? decodedJson.path("duration").asDouble() : null,
                    firstNonBlank(firstManifestUrl(decodedJson), manifest)
                );
            }
            return new TidalManifest(
                mimeType,
                null,
                decoded.contains("cenc:") ? "CENC" : null,
                null,
                null,
                manifest
            );
        } catch (RuntimeException exception) {
            return new TidalManifest(mimeType, null, null, null, null, manifest);
        }
    }

    private JsonNode tryReadJson(String decoded) {
        try {
            return objectMapper.readTree(decoded);
        } catch (IOException exception) {
            return null;
        }
    }

    private String firstManifestUrl(JsonNode decodedJson) {
        if (decodedJson.path("urls").isArray()) {
            for (JsonNode url : decodedJson.path("urls")) {
                if (url.isTextual() && !url.asText().isBlank()) {
                    return url.asText();
                }
            }
        }
        return text(decodedJson, "url", null);
    }

    private byte[] fetchAnalysisAudioBytes(String streamUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(streamUrl))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "audio/mp4,audio/*,*/*")
                .GET()
                .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                    "TIDAL visual analysis audio fetch failed (%s)."
                        .formatted(response.statusCode())
                );
            }
            if (response.body() == null || response.body().length == 0) {
                throw new IllegalStateException("TIDAL visual analysis audio fetch returned an empty body.");
            }
            return response.body();
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL visual analysis audio fetch could not be read.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL visual analysis audio fetch was interrupted.", exception);
        }
    }

    private boolean isPlaylistManifest(TidalPlaybackInfo playbackInfo) {
        String mimeType = playbackInfo.manifestMimeType() == null ? "" : playbackInfo.manifestMimeType().toLowerCase();
        String streamUrl = playbackInfo.streamUrl() == null ? "" : playbackInfo.streamUrl().toLowerCase();
        return mimeType.contains("mpegurl")
            || mimeType.contains("dash")
            || streamUrl.contains(".m3u8")
            || streamUrl.contains(".mpd");
    }

    private String countryCodeForCredential(PlatformAccountCredential credential) {
        return firstNonBlank(
            claimFromAccessToken(credential.accessToken(), "cc"),
            platformOAuthProperties.getTidal().getCountryCode(),
            "US"
        ).toUpperCase();
    }

    private String claimFromAccessToken(String accessToken, String key) {
        if (accessToken == null || accessToken.isBlank()) {
            return null;
        }
        String[] jwtParts = accessToken.split("\\.");
        if (jwtParts.length < 2) {
            return null;
        }

        try {
            String payload = new String(Base64.getUrlDecoder().decode(jwtParts[1]), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = objectMapper.readValue(payload, Map.class);
            Object value = claims.get(key);
            return value == null ? null : firstNonBlank(value.toString());
        } catch (RuntimeException | IOException exception) {
            return null;
        }
    }

    private List<String> scopeList(PlatformAccountCredential credential) {
        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        if (credential.scopeSummary() != null && !credential.scopeSummary().isBlank()) {
            Arrays.stream(credential.scopeSummary().split("[,\\s]+"))
                .map(String::trim)
                .filter(scope -> !scope.isBlank())
                .forEach(scopes::add);
        }
        String claimScope = claimFromAccessToken(credential.accessToken(), "scope");
        if (claimScope != null) {
            Arrays.stream(claimScope.split("[,\\s]+"))
                .map(String::trim)
                .filter(scope -> !scope.isBlank())
                .forEach(scopes::add);
        }
        return List.copyOf(scopes);
    }

    private boolean hasAllScopes(PlatformAccountCredential credential, List<String> requiredScopes) {
        List<String> scopes = scopeList(credential);
        return requiredScopes.stream().allMatch(scopes::contains);
    }

    private String normalizeQuality(String quality) {
        String normalized = quality == null || quality.isBlank() ? "LOSSLESS" : quality.trim().toUpperCase();
        return switch (normalized) {
            case "LOW", "HIGH", "LOSSLESS", "HI_RES", "HI_RES_LOSSLESS" -> normalized;
            case "MEDIUM", "NORMAL" -> "HIGH";
            default -> "LOSSLESS";
        };
    }

    private String safeErrorMessage(JsonNode body) {
        return firstNonBlank(
            text(body, "userMessage", null),
            text(body, "message", null),
            text(body, "error", null),
            text(body, "error_description", null)
        );
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://api.tidal.com/v1";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record TidalPlaybackInfo(
        String assetPresentation,
        String audioQuality,
        String codec,
        Integer bitRate,
        Integer sampleRate,
        Integer bitDepth,
        String manifestMimeType,
        String manifestCodecs,
        String encryptionType,
        Double durationSeconds,
        String streamUrl
    ) {
    }

    private record TidalManifest(
        String mimeType,
        String codecs,
        String encryptionType,
        String assetPresentation,
        Double durationSeconds,
        String streamUrl
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TidalPlaybackStreamResponse(
        String service,
        String status,
        Instant generatedAt,
        String userId,
        String trackId,
        String countryCode,
        String requestedQuality,
        String audioQuality,
        String codec,
        Integer bitRate,
        Integer sampleRate,
        Integer bitDepth,
        String assetPresentation,
        String manifestMimeType,
        String manifestCodecs,
        String encryptionType,
        Double durationSeconds,
        String streamUrl
    ) {
    }
}
