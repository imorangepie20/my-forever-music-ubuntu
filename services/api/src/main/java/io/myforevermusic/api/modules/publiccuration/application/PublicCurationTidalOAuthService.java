package io.myforevermusic.api.modules.publiccuration.application;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.common.error.ApiResourceNotFoundException;
import io.myforevermusic.api.modules.platform.application.PlatformAccountCredential;
import io.myforevermusic.api.modules.platform.application.PlatformAccountProfile;
import io.myforevermusic.api.modules.platform.application.PlatformAccountProfileResolverRegistry;
import io.myforevermusic.api.modules.platform.application.PlatformAuthorizationCodeExchangeRegistry;
import io.myforevermusic.api.modules.platform.application.PlatformAuthorizationSession;
import io.myforevermusic.api.modules.platform.application.PlatformAuthorizationSessionDraft;
import io.myforevermusic.api.modules.platform.application.PlatformAuthorizationSessionStore;
import io.myforevermusic.api.modules.platform.application.PlatformOAuthProperties;
import io.myforevermusic.api.modules.platform.application.PlatformTokenExchangeResult;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PublicCurationTidalOAuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final List<String> REQUIRED_PUBLIC_TIDAL_SCOPES = List.of(
        "playback",
        "entitlements.read"
    );
    private static final String TIDAL_DEVICE_AUTHORIZATION_MODE = "tidal-device-code";
    private static final String DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";
    private static final List<String> DEVICE_AUTHORIZATION_SCOPES = List.of(
        "r_usr",
        "w_usr",
        "w_sub"
    );
    private static final String TIDAL_AUTHORIZATION_MODE = "tidal-pkce-draft";

    private final PublicCurationPlaylistStore playlistStore;
    private final PlatformAuthorizationSessionStore authorizationSessionStore;
    private final PublicPlaybackSessionStore publicPlaybackSessionStore;
    private final PlatformAuthorizationCodeExchangeRegistry authorizationCodeExchangeRegistry;
    private final PlatformAccountProfileResolverRegistry accountProfileResolverRegistry;
    private final PlatformOAuthProperties platformOAuthProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @org.springframework.beans.factory.annotation.Autowired
    public PublicCurationTidalOAuthService(
        PublicCurationPlaylistStore playlistStore,
        PlatformAuthorizationSessionStore authorizationSessionStore,
        PublicPlaybackSessionStore publicPlaybackSessionStore,
        PlatformAuthorizationCodeExchangeRegistry authorizationCodeExchangeRegistry,
        PlatformAccountProfileResolverRegistry accountProfileResolverRegistry,
        PlatformOAuthProperties platformOAuthProperties,
        ObjectMapper objectMapper
    ) {
        this(
            playlistStore,
            authorizationSessionStore,
            publicPlaybackSessionStore,
            authorizationCodeExchangeRegistry,
            accountProfileResolverRegistry,
            platformOAuthProperties,
            objectMapper,
            HttpClient.newHttpClient()
        );
    }

    PublicCurationTidalOAuthService(
        PublicCurationPlaylistStore playlistStore,
        PlatformAuthorizationSessionStore authorizationSessionStore,
        PublicPlaybackSessionStore publicPlaybackSessionStore,
        PlatformAuthorizationCodeExchangeRegistry authorizationCodeExchangeRegistry,
        PlatformAccountProfileResolverRegistry accountProfileResolverRegistry,
        PlatformOAuthProperties platformOAuthProperties,
        ObjectMapper objectMapper,
        HttpClient httpClient
    ) {
        this.playlistStore = playlistStore;
        this.authorizationSessionStore = authorizationSessionStore;
        this.publicPlaybackSessionStore = publicPlaybackSessionStore;
        this.authorizationCodeExchangeRegistry = authorizationCodeExchangeRegistry;
        this.accountProfileResolverRegistry = accountProfileResolverRegistry;
        this.platformOAuthProperties = platformOAuthProperties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public PublicTidalOAuthStartResponse start(String slug) {
        PublicCurationPlaylistStore.StoredPlaylist playlist = findPublishedPlaylist(slug);
        PlatformOAuthProperties.Tidal tidal = platformOAuthProperties.getTidal();
        if (!tidal.isConfigured()) {
            throw new IllegalArgumentException(
                "TIDAL OAuth is not configured. Set TIDAL_OAUTH_ENABLED, TIDAL_CLIENT_ID, TIDAL_REDIRECT_URI, TIDAL_COUNTRY_CODE, and TIDAL_SCOPES before starting public playback."
            );
        }

        Instant now = Instant.now();
        String state = "public-curation-oauth-%s".formatted(UUID.randomUUID());
        String pkceCodeVerifier = generatePkceCodeVerifier();
        String externalAuthorizationUrl = buildPkceAuthorizationUrl(
            tidal.getAuthorizationUri(),
            tidal.getClientId(),
            tidal.getRedirectUri(),
            state,
            pkceCodeVerifier
        );

        PlatformAuthorizationSession session = authorizationSessionStore.create(new PlatformAuthorizationSessionDraft(
            state,
            publicOwnerId(playlist.playlistId()),
            "tidal",
            "TIDAL",
            TIDAL_AUTHORIZATION_MODE,
            "external_browser_redirect",
            REQUIRED_PUBLIC_TIDAL_SCOPES,
            null,
            externalAuthorizationUrl,
            tidal.getRedirectUri(),
            pkceCodeVerifier,
            now.plusSeconds(600),
            now
        ));

        return new PublicTidalOAuthStartResponse(
            "public-curation-tidal-oauth",
            "authorization_pending",
            now,
            new Authorization(
                session.state(),
                session.platformId(),
                session.requestedScopes(),
                session.expiresAt(),
                session.externalAuthorizationUrl(),
                session.redirectUri()
            )
        );
    }

    public PublicTidalDeviceStartResponse startDeviceAuthorization(String slug) {
        PublicCurationPlaylistStore.StoredPlaylist playlist = findPublishedPlaylist(slug);
        PlatformOAuthProperties.Tidal tidal = platformOAuthProperties.getTidal();
        if (!tidal.isConfigured()) {
            throw new IllegalArgumentException(
                "TIDAL OAuth is not configured. Set TIDAL_OAUTH_ENABLED, TIDAL_CLIENT_ID, TIDAL_REDIRECT_URI, TIDAL_COUNTRY_CODE, and TIDAL_SCOPES before starting public playback."
            );
        }

        Instant now = Instant.now();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(deviceAuthorizationUri()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "client_id=%s&scope=%s".formatted(
                        encode(tidal.getClientId()),
                        encode(deviceScopeString())
                    )
                ))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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

            String normalizedVerificationUri = normalizeExternalUrl(verificationUri);
            String normalizedVerificationUriComplete = normalizeExternalUrl(verificationUriComplete);
            String state = "public-curation-device-%s".formatted(UUID.randomUUID());
            Instant expiresAt = now.plusSeconds(expiresIn);
            PlatformAuthorizationSession session = authorizationSessionStore.create(new PlatformAuthorizationSessionDraft(
                state,
                publicOwnerId(playlist.playlistId()),
                "tidal",
                "TIDAL",
                TIDAL_DEVICE_AUTHORIZATION_MODE,
                "external_device_link",
                DEVICE_AUTHORIZATION_SCOPES,
                deviceCode,
                firstNonBlank(normalizedVerificationUriComplete, normalizedVerificationUri),
                normalizedVerificationUri,
                null,
                expiresAt,
                now
            ));

            return new PublicTidalDeviceStartResponse(
                "public-curation-tidal-device",
                "authorization_pending",
                now,
                new DeviceAuthorization(
                    session.state(),
                    session.platformId(),
                    deviceCode,
                    userCode,
                    normalizedVerificationUri,
                    normalizedVerificationUriComplete,
                    expiresAt,
                    interval,
                    session.requestedScopes()
                )
            );
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL device authorization response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL device authorization was interrupted.", exception);
        }
    }

    public PublicTidalDevicePollResponse pollDeviceAuthorization(String slug, String state, String deviceCode) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("Public TIDAL device authorization state is required.");
        }
        if (deviceCode == null || deviceCode.isBlank()) {
            throw new IllegalArgumentException("Public TIDAL device code is required.");
        }
        PublicCurationPlaylistStore.StoredPlaylist playlist = findPublishedPlaylist(slug);
        PlatformAuthorizationSession session = authorizationSessionStore.findByState(state)
            .orElseThrow(() -> new ApiResourceNotFoundException("No pending public curation device authorization was found for state: %s".formatted(state)));
        if (!publicOwnerId(playlist.playlistId()).equals(session.userId()) || !"tidal".equals(session.platformId())) {
            throw new IllegalArgumentException("Public curation device authorization session does not match the requested playlist.");
        }
        if (!TIDAL_DEVICE_AUTHORIZATION_MODE.equals(session.authorizationMode())) {
            throw new IllegalArgumentException("Public curation authorization session is not a TIDAL device authorization session.");
        }
        if (session.isCompleted()) {
            throw new IllegalArgumentException("Public curation device authorization session has already been completed.");
        }
        Instant now = Instant.now();
        if (session.isExpired(now)) {
            throw new IllegalArgumentException("Public curation device authorization session has expired.");
        }
        if (!deviceCode.equals(session.approvalCode())) {
            throw new IllegalArgumentException("Public TIDAL device code does not match the authorization session.");
        }

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(platformOAuthProperties.getTidal().getTokenUri()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded");
            applyClientAuthentication(requestBuilder);
            HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(buildDevicePollForm(deviceCode)))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode payload = parseJson(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String providerError = firstText(payload, "error");
                if ("authorization_pending".equals(providerError) || "slow_down".equals(providerError)) {
                    return new PublicTidalDevicePollResponse(
                        "public-curation-tidal-device",
                        providerError,
                        now,
                        session.requestedScopes(),
                        null,
                        readProviderDescription(payload, "TIDAL authorization is still pending.")
                    );
                }
                throw new IllegalArgumentException(readProviderError(payload, response.statusCode(), "TIDAL device token exchange failed"));
            }

            PlatformTokenExchangeResult tokenExchangeResult = toDeviceTokenExchangeResult(payload, session, now);
            String scopeSummary = String.join(", ", tokenExchangeResult.grantedScopes());
            String accountLabel = resolveTidalAccountLabel(session, tokenExchangeResult, scopeSummary);
            String sessionId = "public-curation-session-%s".formatted(UUID.randomUUID());
            PublicPlaybackSessionStore.StoredSession storedSession = publicPlaybackSessionStore.save(
                new PublicPlaybackSessionStore.SessionDraft(
                    sessionId,
                    playlist.playlistId(),
                    accountLabel,
                    tokenExchangeResult.accessToken(),
                    tokenExchangeResult.refreshToken(),
                    scopeSummary,
                    tokenExchangeResult.accessTokenExpiresAt(),
                    now
                )
            );
            authorizationSessionStore.markCompleted(session.state(), now);

            return new PublicTidalDevicePollResponse(
                "public-curation-tidal-device",
                "authorization_completed",
                now,
                tokenExchangeResult.grantedScopes(),
                toSession(storedSession),
                "TIDAL authorization completed."
            );
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL device token response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL device token exchange was interrupted.", exception);
        }
    }

    public PublicTidalOAuthCompleteResponse complete(String slug, String state, String authorizationCode) {
        if (authorizationCode == null || authorizationCode.isBlank()) {
            throw new IllegalArgumentException("TIDAL authorization code is required.");
        }
        PublicCurationPlaylistStore.StoredPlaylist playlist = findPublishedPlaylist(slug);
        PlatformAuthorizationSession session = authorizationSessionStore.findByState(state)
            .orElseThrow(() -> new ApiResourceNotFoundException("No pending public curation authorization was found for state: %s".formatted(state)));
        if (!publicOwnerId(playlist.playlistId()).equals(session.userId()) || !"tidal".equals(session.platformId())) {
            throw new IllegalArgumentException("Public curation authorization session does not match the requested playlist.");
        }
        if (session.isCompleted()) {
            throw new IllegalArgumentException("Public curation authorization session has already been completed.");
        }
        Instant now = Instant.now();
        if (session.isExpired(now)) {
            throw new IllegalArgumentException("Public curation authorization session has expired.");
        }

        PlatformTokenExchangeResult tokenExchangeResult = authorizationCodeExchangeRegistry
            .getRequiredClient(session)
            .exchangeAuthorizationCode(session, authorizationCode);
        String scopeSummary = String.join(", ", tokenExchangeResult.grantedScopes());
        String accountLabel = resolveTidalAccountLabel(session, tokenExchangeResult, scopeSummary);
        String sessionId = "public-curation-session-%s".formatted(UUID.randomUUID());
        PublicPlaybackSessionStore.StoredSession storedSession = publicPlaybackSessionStore.save(
            new PublicPlaybackSessionStore.SessionDraft(
                sessionId,
                playlist.playlistId(),
                accountLabel,
                tokenExchangeResult.accessToken(),
                tokenExchangeResult.refreshToken(),
                scopeSummary,
                tokenExchangeResult.accessTokenExpiresAt(),
                now
            )
        );
        authorizationSessionStore.markCompleted(session.state(), now);

        return new PublicTidalOAuthCompleteResponse(
            "public-curation-tidal-oauth",
            "authorization_completed",
            now,
            toSession(storedSession),
            "/mix/%s?playback=ready".formatted(slug)
        );
    }

    public PublicTidalPlaybackSessionResponse session(String slug, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Public playback session_id is required.");
        }

        PublicCurationPlaylistStore.StoredPlaylist playlist = findPublishedPlaylist(slug);
        Instant now = Instant.now();
        return publicPlaybackSessionStore.findActiveBySessionId(sessionId, now)
            .filter(storedSession -> playlist.playlistId().equals(storedSession.playlistId()))
            .map(storedSession -> new PublicTidalPlaybackSessionResponse(
                "public-curation-playback-session",
                "ready",
                now,
                toSession(storedSession)
            ))
            .orElseGet(() -> new PublicTidalPlaybackSessionResponse(
                "public-curation-playback-session",
                "not_ready",
                now,
                null
            ));
    }

    private PublicCurationPlaylistStore.StoredPlaylist findPublishedPlaylist(String slug) {
        return playlistStore.findPublishedBySlug(slug)
            .orElseThrow(() -> new ApiResourceNotFoundException("Published public curation playlist was not found."));
    }

    private String resolveTidalAccountLabel(
        PlatformAuthorizationSession session,
        PlatformTokenExchangeResult tokenExchangeResult,
        String scopeSummary
    ) {
        PlatformAccountCredential credential = new PlatformAccountCredential(
            session.userId(),
            session.platformId(),
            session.authorizationMode(),
            "public-curation-tidal",
            "TIDAL Listener",
            tokenExchangeResult.accessToken(),
            tokenExchangeResult.refreshToken(),
            tokenExchangeResult.tokenType(),
            scopeSummary,
            tokenExchangeResult.accessTokenExpiresAt(),
            Instant.now(),
            Instant.now()
        );
        return accountProfileResolverRegistry.resolve(credential)
            .map(PlatformAccountProfile::externalAccountLabel)
            .filter(label -> label != null && !label.isBlank())
            .orElse("TIDAL Listener");
    }

    private String publicOwnerId(Long playlistId) {
        return "public-curation:%s".formatted(playlistId);
    }

    private Session toSession(PublicPlaybackSessionStore.StoredSession storedSession) {
        return new Session(
            storedSession.sessionId(),
            storedSession.playlistId(),
            storedSession.tidalAccountLabel(),
            storedSession.scopeSummary(),
            storedSession.expiresAt()
        );
    }

    private String buildPkceAuthorizationUrl(
        String authorizationUri,
        String clientId,
        String redirectUri,
        String state,
        String pkceCodeVerifier
    ) {
        String authorizationUrl = authorizationUri
            + "?client_id=" + encode(clientId)
            + "&response_type=code"
            + "&redirect_uri=" + encode(redirectUri)
            + "&scope=" + encode(String.join(" ", REQUIRED_PUBLIC_TIDAL_SCOPES)).replace("+", "%20");

        return authorizationUrl
            + "&state=" + encode(state)
            + "&code_challenge_method=S256"
            + "&code_challenge=" + encode(toCodeChallenge(pkceCodeVerifier));
    }

    private String generatePkceCodeVerifier() {
        byte[] random = new byte[64];
        SECURE_RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private String toCodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for PKCE support.", exception);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String deviceAuthorizationUri() {
        String tokenUri = platformOAuthProperties.getTidal().getTokenUri();
        String suffix = "/token";
        return tokenUri.endsWith(suffix)
            ? tokenUri.substring(0, tokenUri.length() - suffix.length()) + "/device_authorization"
            : tokenUri + "/device_authorization";
    }

    private String deviceScopeString() {
        return String.join(" ", DEVICE_AUTHORIZATION_SCOPES);
    }

    private String buildDevicePollForm(String deviceCode) {
        return "client_id=%s&device_code=%s&grant_type=%s&scope=%s".formatted(
            encode(platformOAuthProperties.getTidal().getClientId()),
            encode(deviceCode),
            encode(DEVICE_GRANT_TYPE),
            encode(deviceScopeString())
        );
    }

    private void applyClientAuthentication(HttpRequest.Builder requestBuilder) {
        String clientSecret = platformOAuthProperties.getTidal().getClientSecret();
        if (clientSecret == null || clientSecret.isBlank()) {
            return;
        }

        String credentials = "%s:%s".formatted(platformOAuthProperties.getTidal().getClientId(), clientSecret);
        requestBuilder.header(
            "Authorization",
            "Basic %s".formatted(Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
        );
    }

    private PlatformTokenExchangeResult toDeviceTokenExchangeResult(
        JsonNode payload,
        PlatformAuthorizationSession session,
        Instant now
    ) {
        String accessToken = firstText(payload, "access_token", "accessToken");
        if (isBlank(accessToken)) {
            throw new IllegalArgumentException("TIDAL device token exchange did not return an access token.");
        }
        Long expiresIn = firstLong(payload, null, "expires_in", "expiresIn");
        List<String> grantedScopes = splitScopes(firstNonBlank(firstText(payload, "scope"), deviceScopeString()));
        return new PlatformTokenExchangeResult(
            accessToken,
            firstText(payload, "refresh_token", "refreshToken"),
            firstNonBlank(firstText(payload, "token_type", "tokenType"), "Bearer"),
            grantedScopes.isEmpty() ? session.requestedScopes() : grantedScopes,
            expiresIn == null ? null : now.plusSeconds(expiresIn)
        );
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

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private int firstInt(JsonNode node, int fallback, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isNumber()) {
                return value.asInt();
            }
            if (value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText());
                } catch (NumberFormatException ignored) {
                    // Try the next field.
                }
            }
        }
        return fallback;
    }

    private Long firstLong(JsonNode node, Long fallback, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isNumber()) {
                return value.asLong();
            }
            if (value.isTextual()) {
                try {
                    return Long.parseLong(value.asText());
                } catch (NumberFormatException ignored) {
                    // Try the next field.
                }
            }
        }
        return fallback;
    }

    private List<String> splitScopes(String scopeSummary) {
        if (isBlank(scopeSummary)) {
            return List.of();
        }
        return Arrays.stream(scopeSummary.split("[,\\s]+"))
            .map(String::trim)
            .filter(scope -> !scope.isBlank())
            .distinct()
            .toList();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PublicTidalOAuthStartResponse(
        String service,
        String status,
        Instant generatedAt,
        Authorization authorization
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Authorization(
        String state,
        String platformId,
        List<String> requestedScopes,
        Instant expiresAt,
        String externalAuthorizationUrl,
        String redirectUri
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PublicTidalOAuthCompleteResponse(
        String service,
        String status,
        Instant completedAt,
        Session session,
        String returnPath
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PublicTidalPlaybackSessionResponse(
        String service,
        String status,
        Instant generatedAt,
        Session session
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PublicTidalDeviceStartResponse(
        String service,
        String status,
        Instant generatedAt,
        DeviceAuthorization authorization
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeviceAuthorization(
        String state,
        String platformId,
        String deviceCode,
        String userCode,
        String verificationUri,
        String verificationUriComplete,
        Instant expiresAt,
        int intervalSeconds,
        List<String> requestedScopes
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PublicTidalDevicePollResponse(
        String service,
        String status,
        Instant processedAt,
        List<String> requestedScopes,
        Session session,
        String message
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Session(
        String sessionId,
        Long playlistId,
        String tidalAccountLabel,
        String scopeSummary,
        Instant expiresAt
    ) {
    }
}
