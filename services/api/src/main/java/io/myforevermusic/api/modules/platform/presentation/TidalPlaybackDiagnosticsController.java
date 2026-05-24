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
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platforms/playback/tidal")
@Validated
public class TidalPlaybackDiagnosticsController {

    private static final String TIDAL_PLATFORM_ID = "tidal";
    private static final List<String> DEFAULT_QUALITIES = List.of("LOW", "HIGH", "LOSSLESS");
    private static final List<String> DEFAULT_FALLBACK_COUNTRIES = List.of("KR", "US");
    private static final List<TidalPlaybackInfoEndpoint> PLAYBACK_INFO_ENDPOINTS = List.of(
        new TidalPlaybackInfoEndpoint("playbackinfo", "playbackinfo"),
        new TidalPlaybackInfoEndpoint("playbackinfopostpaywall", "playbackinfopostpaywall")
    );

    private final PlatformCredentialService platformCredentialService;
    private final PlatformOAuthProperties platformOAuthProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public TidalPlaybackDiagnosticsController(
        PlatformCredentialService platformCredentialService,
        PlatformOAuthProperties platformOAuthProperties,
        ObjectMapper objectMapper
    ) {
        this.platformCredentialService = platformCredentialService;
        this.platformOAuthProperties = platformOAuthProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    }

    @Operation(summary = "Probe TIDAL playback manifest presentation without exposing stream URLs")
    @GetMapping("/manifest-diagnostics")
    public TidalPlaybackManifestDiagnosticsResponse manifestDiagnostics(
        @RequestParam("user_id") @NotBlank String userId,
        @RequestParam("track_id") @NotBlank String trackId,
        @RequestParam(value = "country_code", required = false) String countryCodes,
        @RequestParam(value = "quality", required = false) String qualities
    ) {
        PlatformCredentialResolution resolution = platformCredentialService.resolveCredential(userId, TIDAL_PLATFORM_ID);
        if (PlatformCredentialResolution.STATUS_MISSING.equals(resolution.status())) {
            throw new IllegalArgumentException("No stored TIDAL credential exists for playback diagnostics.");
        }
        if (!resolution.usable()) {
            throw new PlatformReconnectRequiredException(
                TIDAL_PLATFORM_ID,
                resolution.detail() == null || resolution.detail().isBlank()
                    ? "Reconnect TIDAL before running playback diagnostics."
                    : resolution.detail()
            );
        }

        PlatformAccountCredential credential = resolution.credential();
        Map<String, Object> claims = accessTokenClaims(credential.accessToken());
        List<String> resolvedCountries = resolveCountries(countryCodes, claims);
        List<String> resolvedQualities = resolveQualities(qualities);
        List<TidalPlaybackManifestProbe> probes = new ArrayList<>();

        for (String countryCode : resolvedCountries) {
            for (String quality : resolvedQualities) {
                for (TidalPlaybackInfoEndpoint endpoint : PLAYBACK_INFO_ENDPOINTS) {
                    probes.add(probePlaybackInfo(credential.accessToken(), trackId, countryCode, quality, endpoint));
                }
            }
        }
        probes.add(probeTrackManifest(credential.accessToken(), trackId));

        String conclusion = probes.stream().anyMatch(TidalPlaybackManifestProbe::fullPlaybackAvailable)
            ? "provider_returned_full_manifest"
            : probes.stream().anyMatch(TidalPlaybackManifestProbe::providerReturnedPreview)
                ? "provider_returned_preview_manifest"
                : "provider_manifest_unresolved";

        return new TidalPlaybackManifestDiagnosticsResponse(
            "api",
            "ok",
            Instant.now(),
            userId,
            trackId,
            new TidalPlaybackTokenDiagnostics(
                safeClaim(claims, "cid"),
                safeClaim(claims, "uid"),
                safeClaim(claims, "cc"),
                safeClaim(claims, "at"),
                safeClaim(claims, "typ"),
                scopeList(credential.scopeSummary(), claims),
                hasAllScopes(credential.scopeSummary(), claims, List.of("r_usr", "w_usr", "w_sub", "r_stream")),
                hasAllScopes(credential.scopeSummary(), claims, List.of("playback", "entitlements.read"))
            ),
            resolvedCountries,
            resolvedQualities,
            conclusion,
            probes
        );
    }

    private TidalPlaybackManifestProbe probePlaybackInfo(
        String accessToken,
        String trackId,
        String countryCode,
        String quality,
        TidalPlaybackInfoEndpoint endpoint
    ) {
        String uri = "%s/tracks/%s/%s?audioquality=%s&playbackmode=STREAM&assetpresentation=FULL&countryCode=%s"
            .formatted(
                trimTrailingSlash(platformOAuthProperties.getTidal().getLegacyApiBaseUri()),
                encode(trackId),
                endpoint.pathSegment(),
                encode(quality),
                encode(countryCode)
            );

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer %s".formatted(accessToken))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body() == null || response.body().isBlank()
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(response.body());
            String assetPresentation = text(body, "assetPresentation", text(body, "trackPresentation", null));
            String audioQuality = text(body, "audioQuality", null);
            String codec = text(body, "codec", null);
            TidalPlaybackManifestSummary manifestSummary = decodeManifest(body.path("manifest").asText(null));
            boolean fullPlaybackAvailable = response.statusCode() >= 200
                && response.statusCode() < 300
                && "FULL".equalsIgnoreCase(firstNonBlank(assetPresentation, manifestSummary.assetPresentation()));
            boolean providerReturnedPreview = response.statusCode() >= 200
                && response.statusCode() < 300
                && "PREVIEW".equalsIgnoreCase(firstNonBlank(assetPresentation, manifestSummary.assetPresentation()));

            return new TidalPlaybackManifestProbe(
                endpoint.name(),
                countryCode,
                quality,
                response.statusCode(),
                assetPresentation,
                audioQuality,
                codec,
                body.path("bitRate").isNumber() ? body.path("bitRate").asInt() : null,
                body.path("sampleRate").isNumber() ? body.path("sampleRate").asInt() : null,
                body.path("bitDepth").isNumber() ? body.path("bitDepth").asInt() : null,
                null,
                manifestSummary,
                fullPlaybackAvailable,
                providerReturnedPreview,
                response.statusCode() >= 400 ? safeErrorMessage(body) : null
            );
        } catch (IOException exception) {
            return failedProbe(endpoint.name(), countryCode, quality, "TIDAL playbackinfo response could not be parsed.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failedProbe(endpoint.name(), countryCode, quality, "TIDAL playbackinfo request was interrupted.");
        } catch (RuntimeException exception) {
            return failedProbe(endpoint.name(), countryCode, quality, "TIDAL playbackinfo request failed: %s".formatted(exception.getMessage()));
        }
    }

    private TidalPlaybackManifestProbe probeTrackManifest(String accessToken, String trackId) {
        String uri = "%s/trackManifests/%s?adaptive=true&formats=HEAACV1&formats=AACLC&manifestType=MPEG_DASH&uriScheme=DATA&usage=PLAYBACK"
            .formatted(
                trimTrailingSlash(platformOAuthProperties.getTidal().getApiBaseUri()),
                encode(trackId)
            );

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.api+json")
                .header("Authorization", "Bearer %s".formatted(accessToken))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body() == null || response.body().isBlank()
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(response.body());
            JsonNode attributes = body.path("data").path("attributes");
            String assetPresentation = text(attributes, "trackPresentation", text(attributes, "assetPresentation", null));
            String previewReason = text(attributes, "previewReason", null);
            TidalPlaybackManifestSummary manifestSummary = decodeManifest(attributes.path("uri").asText(null));
            boolean fullPlaybackAvailable = response.statusCode() >= 200
                && response.statusCode() < 300
                && "FULL".equalsIgnoreCase(assetPresentation);
            boolean providerReturnedPreview = response.statusCode() >= 200
                && response.statusCode() < 300
                && "PREVIEW".equalsIgnoreCase(assetPresentation);

            return new TidalPlaybackManifestProbe(
                "openapi-trackManifests",
                null,
                null,
                response.statusCode(),
                assetPresentation,
                null,
                null,
                null,
                null,
                null,
                previewReason,
                manifestSummary,
                fullPlaybackAvailable,
                providerReturnedPreview,
                response.statusCode() >= 400 ? safeErrorMessage(body) : null
            );
        } catch (IOException exception) {
            return failedProbe("openapi-trackManifests", null, null, "TIDAL trackManifests response could not be parsed.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failedProbe("openapi-trackManifests", null, null, "TIDAL trackManifests request was interrupted.");
        } catch (RuntimeException exception) {
            return failedProbe(
                "openapi-trackManifests",
                null,
                null,
                "TIDAL trackManifests request failed: %s".formatted(exception.getMessage())
            );
        }
    }

    private TidalPlaybackManifestProbe failedProbe(String endpoint, String countryCode, String quality, String errorMessage) {
        return new TidalPlaybackManifestProbe(
            endpoint,
            countryCode,
            quality,
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new TidalPlaybackManifestSummary(false, null, null, null, null, null, 0),
            false,
            false,
            errorMessage
        );
    }

    private TidalPlaybackManifestSummary decodeManifest(String manifest) {
        if (manifest == null || manifest.isBlank()) {
            return new TidalPlaybackManifestSummary(false, null, null, null, null, null, 0);
        }

        if (manifest.startsWith("http")) {
            return new TidalPlaybackManifestSummary(true, "direct-url", null, null, null, null, 1);
        }
        if (manifest.startsWith("data:")) {
            return decodeDataUriManifest(manifest);
        }

        try {
            String decoded = new String(Base64.getDecoder().decode(manifest), StandardCharsets.UTF_8);
            JsonNode decodedJson = objectMapper.readTree(decoded);
            return new TidalPlaybackManifestSummary(
                true,
                text(decodedJson, "mimeType", null),
                text(decodedJson, "codecs", null),
                text(decodedJson, "encryptionType", null),
                text(decodedJson, "assetPresentation", text(decodedJson, "trackPresentation", null)),
                decodedJson.path("duration").isNumber() ? decodedJson.path("duration").asDouble() : null,
                countManifestUrls(decodedJson)
            );
        } catch (RuntimeException | IOException exception) {
            return new TidalPlaybackManifestSummary(true, "unparsed-base64", null, null, null, null, 0);
        }
    }

    private TidalPlaybackManifestSummary decodeDataUriManifest(String manifest) {
        int commaIndex = manifest.indexOf(',');
        if (commaIndex < 0 || commaIndex == manifest.length() - 1) {
            return new TidalPlaybackManifestSummary(true, "data-uri", null, null, null, null, 0);
        }

        String metadata = manifest.substring(0, commaIndex);
        String payload = manifest.substring(commaIndex + 1);
        String mimeType = metadata.substring("data:".length()).replace(";base64", "");
        try {
            String decoded = metadata.contains(";base64")
                ? new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8)
                : java.net.URLDecoder.decode(payload, StandardCharsets.UTF_8);
            return new TidalPlaybackManifestSummary(
                true,
                mimeType,
                null,
                decoded.contains("cenc:") ? "CENC" : null,
                null,
                parseDashDurationSeconds(decoded),
                countDashSegmentUrls(decoded)
            );
        } catch (RuntimeException exception) {
            return new TidalPlaybackManifestSummary(true, mimeType, null, null, null, null, 0);
        }
    }

    private int countManifestUrls(JsonNode decodedJson) {
        if (decodedJson == null || decodedJson.isMissingNode() || decodedJson.isNull()) {
            return 0;
        }
        int count = 0;
        if (decodedJson.path("urls").isArray()) {
            count += decodedJson.path("urls").size();
        }
        if (decodedJson.path("url").isTextual() && !decodedJson.path("url").asText().isBlank()) {
            count += 1;
        }
        return count;
    }

    private int countDashSegmentUrls(String decodedDash) {
        int count = 0;
        int index = 0;
        while ((index = decodedDash.indexOf("https://", index)) >= 0) {
            count++;
            index += "https://".length();
        }
        return count;
    }

    private Double parseDashDurationSeconds(String decodedDash) {
        String marker = "mediaPresentationDuration=\"PT";
        int start = decodedDash.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int valueStart = start + marker.length();
        int valueEnd = decodedDash.indexOf('S', valueStart);
        if (valueEnd < 0) {
            return null;
        }
        try {
            return Double.parseDouble(decodedDash.substring(valueStart, valueEnd));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Map<String, Object> accessTokenClaims(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return Map.of();
        }

        String[] jwtParts = accessToken.split("\\.");
        if (jwtParts.length < 2) {
            return Map.of();
        }

        try {
            String payload = new String(Base64.getUrlDecoder().decode(jwtParts[1]), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = objectMapper.readValue(payload, Map.class);
            return claims;
        } catch (RuntimeException | IOException exception) {
            return Map.of();
        }
    }

    private List<String> resolveCountries(String requestedCountries, Map<String, Object> claims) {
        LinkedHashSet<String> countries = new LinkedHashSet<>();
        parseList(requestedCountries).forEach(country -> countries.add(country.toUpperCase()));
        String tokenCountry = safeClaim(claims, "cc");
        if (tokenCountry != null) {
            countries.add(tokenCountry.toUpperCase());
        }
        String configuredCountry = platformOAuthProperties.getTidal().getCountryCode();
        if (configuredCountry != null && !configuredCountry.isBlank()) {
            countries.add(configuredCountry.trim().toUpperCase());
        }
        DEFAULT_FALLBACK_COUNTRIES.forEach(countries::add);
        return countries.stream().limit(5).toList();
    }

    private List<String> resolveQualities(String requestedQualities) {
        List<String> values = parseList(requestedQualities);
        if (values.isEmpty()) {
            return DEFAULT_QUALITIES;
        }
        return values.stream()
            .map(String::toUpperCase)
            .distinct()
            .limit(5)
            .toList();
    }

    private List<String> parseList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        return Arrays.stream(value.split("[,\\s]+"))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .toList();
    }

    private List<String> scopeList(String scopeSummary, Map<String, Object> claims) {
        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        parseList(scopeSummary).forEach(scopes::add);
        Object claimScope = claims.get("scope");
        if (claimScope != null) {
            parseList(claimScope.toString()).forEach(scopes::add);
        }
        Object claimScopes = claims.get("scp");
        if (claimScopes != null) {
            parseList(claimScopes.toString()).forEach(scopes::add);
        }
        return List.copyOf(scopes);
    }

    private boolean hasAllScopes(String scopeSummary, Map<String, Object> claims, List<String> expectedScopes) {
        List<String> scopes = scopeList(scopeSummary, claims);
        return expectedScopes.stream().allMatch(scopes::contains);
    }

    private String safeClaim(Map<String, Object> claims, String claim) {
        Object value = claims.get(claim);
        return value == null ? null : firstNonBlank(value.toString());
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

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TidalPlaybackManifestDiagnosticsResponse(
        String service,
        String status,
        Instant generatedAt,
        String userId,
        String trackId,
        TidalPlaybackTokenDiagnostics token,
        List<String> testedCountryCodes,
        List<String> testedQualities,
        String conclusion,
        List<TidalPlaybackManifestProbe> probes
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TidalPlaybackTokenDiagnostics(
        String clientClaim,
        String providerUserId,
        String countryCode,
        String accessTokenType,
        String tokenType,
        List<String> scopes,
        boolean hasLegacyStreamingScopes,
        boolean hasSdkPlaybackScopes
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TidalPlaybackManifestProbe(
        String endpoint,
        String countryCode,
        String requestedQuality,
        int httpStatus,
        String assetPresentation,
        String audioQuality,
        String codec,
        Integer bitRate,
        Integer sampleRate,
        Integer bitDepth,
        String previewReason,
        TidalPlaybackManifestSummary manifest,
        boolean fullPlaybackAvailable,
        boolean providerReturnedPreview,
        String error
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TidalPlaybackManifestSummary(
        boolean present,
        String mimeType,
        String codecs,
        String encryptionType,
        String assetPresentation,
        Double durationSeconds,
        int urlCount
    ) {
    }

    private record TidalPlaybackInfoEndpoint(String name, String pathSegment) {
    }
}
