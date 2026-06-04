package io.myforevermusic.api.modules.platform.application;

import io.myforevermusic.api.common.error.ApiResourceNotFoundException;
import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import io.myforevermusic.api.modules.auth.application.AuthRegisteredAccount;
import io.myforevermusic.api.modules.platform.presentation.PlatformAuthorizationCompleteRequest;
import io.myforevermusic.api.modules.platform.presentation.PlatformAuthorizationCompleteResponse;
import io.myforevermusic.api.modules.platform.presentation.PlatformAuthorizationStartRequest;
import io.myforevermusic.api.modules.platform.presentation.PlatformAuthorizationStartResponse;
import io.myforevermusic.api.modules.platform.presentation.PlatformCatalogResponse.PlatformOption;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import org.springframework.stereotype.Service;

@Service
public class PlatformAuthorizationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    // login.tidal.com/authorize (redirect flow) uses the modern OpenAPI v2 scopes; the legacy
    // r_usr/w_usr scopes are deprecated there and trigger a provider error.
    private static final List<String> REQUIRED_TIDAL_PMS_IMPORT_SCOPES = List.of(
        "user.read",
        "collection.read",
        "playlists.read"
    );

    private final AuthAccountStore authAccountStore;
    private final PlatformCatalogService platformCatalogService;
    private final PlatformAuthorizationSessionStore platformAuthorizationSessionStore;
    private final PlatformConnectionStore platformConnectionStore;
    private final PlatformCredentialStore platformCredentialStore;
    private final PlatformAuthorizationCodeExchangeRegistry platformAuthorizationCodeExchangeRegistry;
    private final PlatformAccountProfileResolverRegistry platformAccountProfileResolverRegistry;
    private final PlatformOAuthProperties platformOAuthProperties;

    public PlatformAuthorizationService(
        AuthAccountStore authAccountStore,
        PlatformCatalogService platformCatalogService,
        PlatformAuthorizationSessionStore platformAuthorizationSessionStore,
        PlatformConnectionStore platformConnectionStore,
        PlatformCredentialStore platformCredentialStore,
        PlatformAuthorizationCodeExchangeRegistry platformAuthorizationCodeExchangeRegistry,
        PlatformAccountProfileResolverRegistry platformAccountProfileResolverRegistry,
        PlatformOAuthProperties platformOAuthProperties
    ) {
        this.authAccountStore = authAccountStore;
        this.platformCatalogService = platformCatalogService;
        this.platformAuthorizationSessionStore = platformAuthorizationSessionStore;
        this.platformConnectionStore = platformConnectionStore;
        this.platformCredentialStore = platformCredentialStore;
        this.platformAuthorizationCodeExchangeRegistry = platformAuthorizationCodeExchangeRegistry;
        this.platformAccountProfileResolverRegistry = platformAccountProfileResolverRegistry;
        this.platformOAuthProperties = platformOAuthProperties;
    }

    public PlatformAuthorizationStartResponse startAuthorization(PlatformAuthorizationStartRequest request) {
        AuthRegisteredAccount account = findAccount(request.userId());
        PlatformOption platform = findPlatform(request.platformId());
        if (!platform.pmsImportSupported()) {
            throw new IllegalArgumentException(
                "%s does not have a completed PMS import provider yet.".formatted(platform.displayName())
            );
        }
        Instant now = Instant.now();
        String state = "oauth-%s".formatted(UUID.randomUUID());
        OAuthStartConfig oauthStartConfig = resolveOAuthStartConfig(platform);
        if (!oauthStartConfig.configured()) {
            throw new IllegalArgumentException(oauthStartConfig.notConfiguredMessage());
        }
        String authorizationMode = oauthStartConfig.authorizationMode();
        String authorizationChannel = "external_browser_redirect";
        List<String> requestedScopes = oauthStartConfig.requestedScopes();
        String pkceCodeVerifier = generatePkceCodeVerifier();
        String externalAuthorizationUrl = buildPkceAuthorizationUrl(
            oauthStartConfig,
            state,
            requestedScopes,
            pkceCodeVerifier
        );
        String redirectUri = oauthStartConfig.redirectUri();
        String approvalCode = null;

        PlatformAuthorizationSession session = platformAuthorizationSessionStore.create(
            new PlatformAuthorizationSessionDraft(
                state,
                account.userId(),
                platform.platformId(),
                platform.displayName(),
                authorizationMode,
                authorizationChannel,
                requestedScopes,
                approvalCode,
                externalAuthorizationUrl,
                redirectUri,
                pkceCodeVerifier,
                now.plusSeconds(600),
                now
            )
        );
        resetExistingAuthorization(account.userId(), platform.platformId());

        return new PlatformAuthorizationStartResponse(
            "api",
            "authorization_pending",
            now,
            new PlatformAuthorizationStartResponse.AuthorizationUser(
                account.userId(),
                account.displayName(),
                account.email()
            ),
            new PlatformAuthorizationStartResponse.AuthorizationSession(
                session.state(),
                session.platformId(),
                session.platformDisplayName(),
                session.authorizationMode(),
                session.authorizationChannel(),
                session.requestedScopes(),
                session.expiresAt(),
                "internal_approval_page".equals(session.authorizationChannel())
                    ? "/platforms/oauth/authorize?state=%s".formatted(session.state())
                    : null,
                "internal_approval_page".equals(session.authorizationChannel())
                    ? "/platforms/oauth/callback?state=%s&code=%s".formatted(session.state(), session.approvalCode())
                    : "%s?state=%s".formatted(session.redirectUri(), session.state()),
                session.approvalCode(),
                session.externalAuthorizationUrl(),
                session.redirectUri()
            )
        );
    }

    public PlatformAuthorizationCompleteResponse completeAuthorization(PlatformAuthorizationCompleteRequest request) {
        AuthRegisteredAccount account = findAccount(request.userId());
        PlatformAuthorizationSession session = platformAuthorizationSessionStore.findByState(request.state())
            .orElseThrow(() -> new ApiResourceNotFoundException("No pending platform authorization was found for state: %s".formatted(request.state())));

        if (!session.userId().equals(request.userId()) || !session.platformId().equals(request.platformId())) {
            throw new IllegalArgumentException("Authorization session does not match the current user or platform.");
        }
        if (session.isCompleted()) {
            throw new IllegalArgumentException("Authorization session has already been completed.");
        }
        if (session.isExpired(Instant.now())) {
            throw new IllegalArgumentException("Authorization session has expired. Start the platform connection again.");
        }
        if ("internal_approval_page".equals(session.authorizationChannel())) {
            if (request.approvalCode() == null || !session.approvalCode().equals(request.approvalCode())) {
                throw new IllegalArgumentException("Authorization approval code is invalid.");
            }
        } else if (request.authorizationCode() == null || request.authorizationCode().isBlank()) {
            throw new IllegalArgumentException("Provider authorization code is required.");
        }

        Instant now = Instant.now();
        String callbackCode = "internal_approval_page".equals(session.authorizationChannel())
            ? request.approvalCode()
            : request.authorizationCode();
        PlatformTokenExchangeResult tokenExchangeResult = platformAuthorizationCodeExchangeRegistry
            .getRequiredClient(session)
            .exchangeAuthorizationCode(session, callbackCode);
        PlatformOption platform = findPlatform(session.platformId());
        String scopeSummary = String.join(", ", tokenExchangeResult.grantedScopes());
        String fallbackExternalUserId = "%s:%s".formatted(session.platformId().replace('-', '_'), account.userId());
        String fallbackExternalAccountLabel = "%s %s account".formatted(account.displayName(), session.platformDisplayName());
        PlatformAccountCredential provisionalCredential = new PlatformAccountCredential(
            account.userId(),
            session.platformId(),
            session.authorizationMode(),
            fallbackExternalUserId,
            fallbackExternalAccountLabel,
            tokenExchangeResult.accessToken(),
            tokenExchangeResult.refreshToken(),
            tokenExchangeResult.tokenType(),
            scopeSummary,
            tokenExchangeResult.accessTokenExpiresAt(),
            now,
            now
        );
        PlatformAccountProfile externalProfile = platformAccountProfileResolverRegistry
            .resolveStrictly(provisionalCredential)
            .orElse(null);
        String externalUserId = firstNonBlank(
            externalProfile == null ? null : externalProfile.externalUserId(),
            fallbackExternalUserId
        );
        String externalAccountLabel = firstNonBlank(
            externalProfile == null ? null : externalProfile.externalAccountLabel(),
            fallbackExternalAccountLabel
        );

        platformCredentialStore.save(
            new PlatformAccountCredential(
                account.userId(),
                session.platformId(),
                session.authorizationMode(),
                externalUserId,
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
                session.platformId(),
                session.authorizationMode(),
                externalAccountLabel,
                scopeSummary,
                platform.pmsImportSupported(),
                now,
                now
            )
        );
        PlatformAuthorizationSession completedSession = platformAuthorizationSessionStore.markCompleted(session.state(), now);

        boolean preferredConnected = account.preferredPlatformId().equals(session.platformId())
            && connectionState.connected()
            && platform.pmsImportSupported();

        return new PlatformAuthorizationCompleteResponse(
            "api",
            "authorization_completed",
            now,
            new PlatformAuthorizationCompleteResponse.AuthorizationResult(
                completedSession.state(),
                completedSession.platformId(),
                completedSession.platformDisplayName(),
                completedSession.authorizationMode(),
                completedSession.requestedScopes(),
                completedSession.completedAt()
            ),
            new PlatformAuthorizationCompleteResponse.ConnectionResult(
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
            new PlatformAuthorizationCompleteResponse.NextStep(
                preferredConnected ? "/pms" : "/platforms",
                preferredConnected
                    ? "Preferred platform connected. Continue to PMS import."
                    : account.preferredPlatformId().equals(session.platformId()) && !platform.pmsImportSupported()
                        ? "Platform connected. This source is available for analysis signals, but PMS import is not ready yet."
                        : "Platform connected. Return to platform onboarding to continue setup."
            )
        );
    }

    private AuthRegisteredAccount findAccount(String userId) {
        return authAccountStore.findByUserId(userId)
            .orElseThrow(() -> new ApiResourceNotFoundException("No registered account found for user: %s".formatted(userId)));
    }

    private PlatformOption findPlatform(String platformId) {
        return platformCatalogService.getCatalog()
            .platforms()
            .stream()
            .filter(platform -> platform.platformId().equals(platformId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Platform is not supported: %s".formatted(platformId)));
    }

    private OAuthStartConfig resolveOAuthStartConfig(PlatformOption platform) {
        if ("spotify".equals(platform.platformId())) {
            PlatformOAuthProperties.Spotify spotify = platformOAuthProperties.getSpotify();
            return new OAuthStartConfig(
                "spotify-pkce-draft",
                spotify.isConfigured(),
                spotify.getClientId(),
                spotify.getRedirectUri(),
                spotify.getAuthorizationUri(),
                spotify.getScopes(),
                true,
                "Spotify OAuth is not configured. Set SPOTIFY_OAUTH_ENABLED, SPOTIFY_CLIENT_ID, and SPOTIFY_REDIRECT_URI before starting platform onboarding."
            );
        }

        if ("tidal".equals(platform.platformId())) {
            PlatformOAuthProperties.Tidal tidal = platformOAuthProperties.getTidal();
            return new OAuthStartConfig(
                "tidal-pkce-draft",
                tidal.isConfigured(),
                tidal.getClientId(),
                tidal.getRedirectUri(),
                tidal.getAuthorizationUri(),
                REQUIRED_TIDAL_PMS_IMPORT_SCOPES,
                false,
                "TIDAL OAuth is not configured. Set TIDAL_OAUTH_ENABLED, TIDAL_CLIENT_ID, TIDAL_REDIRECT_URI, TIDAL_COUNTRY_CODE, and TIDAL_SCOPES before starting TIDAL onboarding."
            );
        }

        throw new IllegalArgumentException(
            "%s OAuth is not implemented yet.".formatted(platform.displayName())
        );
    }

    private void resetExistingAuthorization(String userId, String platformId) {
        platformCredentialStore.clear(userId, platformId);
        platformConnectionStore.disconnect(userId, platformId);
    }

    private String buildPkceAuthorizationUrl(
        OAuthStartConfig oauthStartConfig,
        String state,
        List<String> requestedScopes,
        String pkceCodeVerifier
    ) {
        String codeChallenge = toCodeChallenge(pkceCodeVerifier);
        String authorizationUrl = oauthStartConfig.authorizationUri()
            + "?client_id=" + encode(oauthStartConfig.clientId())
            + "&response_type=code"
            + "&redirect_uri=" + encode(oauthStartConfig.redirectUri());

        if (!requestedScopes.isEmpty()) {
            // Encode scope separators as %20 (not "+"): TIDAL's authorize endpoint treats a
            // literal "+" as part of the scope value, which invalidates the whole scope set.
            authorizationUrl += "&scope=" + encode(String.join(" ", requestedScopes)).replace("+", "%20");
        }
        if (oauthStartConfig.showDialog()) {
            authorizationUrl += "&show_dialog=true";
        }

        return authorizationUrl
            + "&state=" + encode(state)
            + "&code_challenge_method=S256"
            + "&code_challenge=" + encode(codeChallenge);
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private record OAuthStartConfig(
        String authorizationMode,
        boolean configured,
        String clientId,
        String redirectUri,
        String authorizationUri,
        List<String> requestedScopes,
        boolean showDialog,
        String notConfiguredMessage
    ) {
    }
}
