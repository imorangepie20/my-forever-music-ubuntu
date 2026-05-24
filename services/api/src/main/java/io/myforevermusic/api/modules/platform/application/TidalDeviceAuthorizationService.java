package io.myforevermusic.api.modules.platform.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.common.error.ApiResourceNotFoundException;
import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import io.myforevermusic.api.modules.auth.application.AuthRegisteredAccount;
import io.myforevermusic.api.modules.platform.presentation.PlatformCatalogResponse.PlatformOption;
import io.myforevermusic.api.modules.platform.presentation.TidalDeviceAuthorizationPollRequest;
import io.myforevermusic.api.modules.platform.presentation.TidalDeviceAuthorizationPollResponse;
import io.myforevermusic.api.modules.platform.presentation.TidalDeviceAuthorizationStartRequest;
import io.myforevermusic.api.modules.platform.presentation.TidalDeviceAuthorizationStartResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TidalDeviceAuthorizationService {

    private static final String TIDAL_PLATFORM_ID = "tidal";
    private static final String AUTHORIZATION_MODE = "tidal-device-code";
    private static final String DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";
    private static final List<String> DEVICE_AUTHORIZATION_SCOPES = List.of("r_usr", "w_usr", "w_sub", "r_stream");

    private final AuthAccountStore authAccountStore;
    private final PlatformCatalogService platformCatalogService;
    private final PlatformConnectionStore platformConnectionStore;
    private final PlatformCredentialStore platformCredentialStore;
    private final PlatformOAuthProperties platformOAuthProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public TidalDeviceAuthorizationService(
        AuthAccountStore authAccountStore,
        PlatformCatalogService platformCatalogService,
        PlatformConnectionStore platformConnectionStore,
        PlatformCredentialStore platformCredentialStore,
        PlatformOAuthProperties platformOAuthProperties,
        ObjectMapper objectMapper
    ) {
        this(
            authAccountStore,
            platformCatalogService,
            platformConnectionStore,
            platformCredentialStore,
            platformOAuthProperties,
            objectMapper,
            HttpClient.newHttpClient()
        );
    }

    TidalDeviceAuthorizationService(
        AuthAccountStore authAccountStore,
        PlatformCatalogService platformCatalogService,
        PlatformConnectionStore platformConnectionStore,
        PlatformCredentialStore platformCredentialStore,
        PlatformOAuthProperties platformOAuthProperties,
        ObjectMapper objectMapper,
        HttpClient httpClient
    ) {
        this.authAccountStore = authAccountStore;
        this.platformCatalogService = platformCatalogService;
        this.platformConnectionStore = platformConnectionStore;
        this.platformCredentialStore = platformCredentialStore;
        this.platformOAuthProperties = platformOAuthProperties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public TidalDeviceAuthorizationStartResponse start(TidalDeviceAuthorizationStartRequest request) {
        AuthRegisteredAccount account = findAccount(request.userId());
        validateTidalConfiguration();
        Instant now = Instant.now();
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(deviceAuthorizationUri()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "client_id=%s&scope=%s".formatted(
                        encode(tidalProperties().getClientId()),
                        encode(scopeString())
                    )
                ))
                .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode payload = parseJson(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException(readProviderError(payload, response.statusCode(), "TIDAL device authorization failed"));
            }

            String deviceCode = firstText(payload, "deviceCode", "device_code");
            String userCode = firstText(payload, "userCode", "user_code");
            String verificationUri = firstText(payload, "verificationUri", "verification_uri");
            String verificationUriComplete = firstText(payload, "verificationUriComplete", "verification_uri_complete");
            int expiresIn = firstInt(payload, 300, "expiresIn", "expires_in");
            int interval = firstInt(payload, 5, "interval");

            if (isBlank(deviceCode) || isBlank(userCode) || isBlank(verificationUri)) {
                throw new IllegalArgumentException("TIDAL device authorization response was missing device code, user code, or verification URI.");
            }

            platformCredentialStore.clear(account.userId(), TIDAL_PLATFORM_ID);
            platformConnectionStore.disconnect(account.userId(), TIDAL_PLATFORM_ID);

            return new TidalDeviceAuthorizationStartResponse(
                "api",
                "authorization_pending",
                now,
                account.userId(),
                new TidalDeviceAuthorizationStartResponse.Authorization(
                    deviceCode,
                    userCode,
                    normalizeExternalUrl(verificationUri),
                    normalizeExternalUrl(verificationUriComplete),
                    now.plusSeconds(expiresIn),
                    interval,
                    requestedScopes()
                )
            );
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL device authorization response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL device authorization was interrupted.", exception);
        }
    }

    public TidalDeviceAuthorizationPollResponse poll(TidalDeviceAuthorizationPollRequest request) {
        AuthRegisteredAccount account = findAccount(request.userId());
        validateTidalConfiguration();
        Instant now = Instant.now();
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(tidalProperties().getTokenUri()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded");
            applyClientAuthentication(requestBuilder);
            HttpRequest httpRequest = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(buildPollForm(request.deviceCode())))
                .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode payload = parseJson(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String providerError = firstText(payload, "error");
                if ("authorization_pending".equals(providerError) || "slow_down".equals(providerError)) {
                    return new TidalDeviceAuthorizationPollResponse(
                        "api",
                        providerError,
                        now,
                        account.userId(),
                        requestedScopes(),
                        null,
                        readProviderDescription(payload, "TIDAL authorization is still pending.")
                    );
                }
                throw new IllegalArgumentException(readProviderError(payload, response.statusCode(), "TIDAL device token exchange failed"));
            }

            PlatformTokenExchangeResult tokenExchangeResult = toTokenExchangeResult(payload, now);
            TidalDeviceAccountProfile profile = resolveAccountProfile(tokenExchangeResult.accessToken());
            if (isBlank(profile.externalUserId())) {
                throw new IllegalArgumentException("TIDAL device token did not expose a provider account id. Authorization cannot be stored.");
            }

            PlatformOption platform = findTidalPlatform();
            String scopeSummary = String.join(", ", tokenExchangeResult.grantedScopes());
            String externalAccountLabel = firstNonBlank(
                profile.externalAccountLabel(),
                "%s TIDAL account".formatted(account.displayName())
            );
            platformCredentialStore.save(
                new PlatformAccountCredential(
                    account.userId(),
                    TIDAL_PLATFORM_ID,
                    AUTHORIZATION_MODE,
                    profile.externalUserId(),
                    externalAccountLabel,
                    tokenExchangeResult.accessToken(),
                    tokenExchangeResult.refreshToken(),
                    tokenExchangeResult.tokenType(),
                    scopeSummary,
                    tokenExchangeResult.accessTokenExpiresAt(),
                    now,
                    now
                )
            );

            PlatformConnectionState connectionState = platformConnectionStore.connect(
                new PlatformConnectionDraft(
                    account.userId(),
                    TIDAL_PLATFORM_ID,
                    AUTHORIZATION_MODE,
                    externalAccountLabel,
                    scopeSummary,
                    platform.pmsImportSupported(),
                    now,
                    now
                )
            );

            return new TidalDeviceAuthorizationPollResponse(
                "api",
                "authorization_completed",
                now,
                account.userId(),
                tokenExchangeResult.grantedScopes(),
                new TidalDeviceAuthorizationPollResponse.Connection(
                    account.userId(),
                    connectionState.platformId(),
                    connectionState.connected(),
                    connectionState.connectionStatus(),
                    connectionState.connectionMode(),
                    connectionState.externalAccountLabel(),
                    connectionState.scopeSummary(),
                    connectionState.syncReady(),
                    connectionState.connectedAt()
                ),
                "TIDAL device authorization completed."
            );
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL device token response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL device token exchange was interrupted.", exception);
        }
    }

    private PlatformTokenExchangeResult toTokenExchangeResult(JsonNode payload, Instant now) {
        String accessToken = firstText(payload, "access_token", "accessToken");
        if (isBlank(accessToken)) {
            throw new IllegalArgumentException("TIDAL device token exchange did not return an access token.");
        }
        Long expiresIn = firstLong(payload, null, "expires_in", "expiresIn");
        List<String> grantedScopes = splitScopes(firstNonBlank(firstText(payload, "scope"), scopeString()));
        return new PlatformTokenExchangeResult(
            accessToken,
            firstText(payload, "refresh_token", "refreshToken"),
            firstNonBlank(firstText(payload, "token_type", "tokenType"), "Bearer"),
            grantedScopes.isEmpty() ? requestedScopes() : grantedScopes,
            expiresIn == null ? null : now.plusSeconds(expiresIn)
        );
    }

    private TidalDeviceAccountProfile resolveAccountProfile(String accessToken) {
        TidalDeviceAccountProfile fromToken = accountProfileFromJwt(accessToken);
        if (!isBlank(fromToken.externalUserId())) {
            return fromToken;
        }

        for (String endpoint : List.of("/users/me", "/me", "/sessions")) {
            TidalDeviceAccountProfile profile = accountProfileFromLegacyApi(accessToken, endpoint);
            if (!isBlank(profile.externalUserId())) {
                return profile;
            }
        }
        return fromToken;
    }

    private TidalDeviceAccountProfile accountProfileFromJwt(String accessToken) {
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) {
                return TidalDeviceAccountProfile.empty();
            }
            JsonNode jwt = objectMapper.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            String userId = firstText(jwt, "uid", "userId", "user_id", "sub");
            String countryCode = firstText(jwt, "cc", "countryCode", "country_code", "country");
            return new TidalDeviceAccountProfile(userId, countryCode, labelFor(userId, countryCode));
        } catch (IllegalArgumentException | IOException ignored) {
            return TidalDeviceAccountProfile.empty();
        }
    }

    private TidalDeviceAccountProfile accountProfileFromLegacyApi(String accessToken, String endpoint) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tidalProperties().getLegacyApiBaseUri() + endpoint))
                .header("Accept", "application/vnd.tidal.v1+json")
                .header("Authorization", "Bearer %s".formatted(accessToken))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return TidalDeviceAccountProfile.empty();
            }
            JsonNode payload = parseJson(response.body());
            String userId = firstText(payload, "userId", "user_id", "id");
            String countryCode = firstText(payload, "countryCode", "country_code", "country");
            String username = firstNonBlank(
                firstText(payload, "username"),
                firstText(payload, "name"),
                joinName(firstText(payload, "firstName"), firstText(payload, "lastName"))
            );
            return new TidalDeviceAccountProfile(userId, countryCode, firstNonBlank(username, labelFor(userId, countryCode)));
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return TidalDeviceAccountProfile.empty();
        }
    }

    private void validateTidalConfiguration() {
        PlatformOAuthProperties.Tidal tidal = tidalProperties();
        if (!tidal.isEnabled() || isBlank(tidal.getClientId()) || isBlank(tidal.getClientSecret()) || isBlank(tidal.getTokenUri())) {
            throw new IllegalArgumentException("TIDAL device authorization is not configured. Set the TIDAL client id, secret, and token URI.");
        }
    }

    private AuthRegisteredAccount findAccount(String userId) {
        return authAccountStore.findByUserId(userId)
            .orElseThrow(() -> new ApiResourceNotFoundException("No registered account found for user: %s".formatted(userId)));
    }

    private PlatformOption findTidalPlatform() {
        return platformCatalogService.getCatalog()
            .platforms()
            .stream()
            .filter(platform -> TIDAL_PLATFORM_ID.equals(platform.platformId()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Platform is not supported: tidal"));
    }

    private String deviceAuthorizationUri() {
        String tokenUri = tidalProperties().getTokenUri();
        String suffix = "/token";
        return tokenUri.endsWith(suffix)
            ? tokenUri.substring(0, tokenUri.length() - suffix.length()) + "/device_authorization"
            : tokenUri + "/device_authorization";
    }

    private String buildPollForm(String deviceCode) {
        return "client_id=%s&device_code=%s&grant_type=%s&scope=%s".formatted(
            encode(tidalProperties().getClientId()),
            encode(deviceCode),
            encode(DEVICE_GRANT_TYPE),
            encode(scopeString())
        );
    }

    private void applyClientAuthentication(HttpRequest.Builder requestBuilder) {
        String credentials = "%s:%s".formatted(tidalProperties().getClientId(), tidalProperties().getClientSecret());
        requestBuilder.header(
            "Authorization",
            "Basic %s".formatted(Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
        );
    }

    private PlatformOAuthProperties.Tidal tidalProperties() {
        return platformOAuthProperties.getTidal();
    }

    private String scopeString() {
        return String.join(" ", requestedScopes());
    }

    private List<String> requestedScopes() {
        List<String> scopes = tidalProperties().getScopes();
        if (scopes == null || scopes.isEmpty()) {
            return DEVICE_AUTHORIZATION_SCOPES;
        }

        List<String> deviceScopes = scopes.stream()
            .filter(DEVICE_AUTHORIZATION_SCOPES::contains)
            .distinct()
            .toList();
        return deviceScopes.isEmpty() ? DEVICE_AUTHORIZATION_SCOPES : deviceScopes;
    }

    private JsonNode parseJson(String body) throws IOException {
        return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
    }

    private String readProviderError(JsonNode payload, int statusCode, String fallback) {
        String message = readProviderDescription(payload, fallback);
        return "%s (%s): %s".formatted(fallback, statusCode, message);
    }

    private String readProviderDescription(JsonNode payload, String fallback) {
        return firstNonBlank(
            firstText(payload, "error_description", "errorDescription"),
            firstText(payload, "error"),
            fallback
        );
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.isNumber() ? value.asText() : value.asText(null);
                if (!isBlank(text)) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private int firstInt(JsonNode node, int defaultValue, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isInt() || value.isLong()) {
                return value.asInt();
            }
        }
        return defaultValue;
    }

    private Long firstLong(JsonNode node, Long defaultValue, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isLong() || value.isInt()) {
                return value.asLong();
            }
        }
        return defaultValue;
    }

    private List<String> splitScopes(String scopeSummary) {
        if (isBlank(scopeSummary)) {
            return List.of();
        }
        return java.util.Arrays.stream(scopeSummary.split("[,\\s]+"))
            .map(String::trim)
            .filter(scope -> !scope.isBlank())
            .distinct()
            .toList();
    }

    private String labelFor(String userId, String countryCode) {
        if (isBlank(userId)) {
            return null;
        }
        return isBlank(countryCode) ? "TIDAL %s".formatted(userId) : "TIDAL %s (%s)".formatted(userId, countryCode);
    }

    private String joinName(String firstName, String lastName) {
        String joined = "%s %s".formatted(firstName == null ? "" : firstName, lastName == null ? "" : lastName).trim();
        return joined.isBlank() ? null : joined;
    }

    private String normalizeExternalUrl(String value) {
        if (isBlank(value)) {
            return value;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("http://") || trimmed.startsWith("https://")
            ? trimmed
            : "https://%s".formatted(trimmed);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private record TidalDeviceAccountProfile(
        String externalUserId,
        String countryCode,
        String externalAccountLabel
    ) {
        private static TidalDeviceAccountProfile empty() {
            return new TidalDeviceAccountProfile(null, null, null);
        }
    }
}
