package io.myforevermusic.api.modules.publiccuration.application;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PublicCurationTidalOAuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final List<String> REQUIRED_PUBLIC_TIDAL_SCOPES = List.of(
        "user.read",
        "collection.read",
        "playlists.read"
    );
    private static final String TIDAL_AUTHORIZATION_MODE = "tidal-pkce-draft";

    private final PublicCurationPlaylistStore playlistStore;
    private final PlatformAuthorizationSessionStore authorizationSessionStore;
    private final PublicPlaybackSessionStore publicPlaybackSessionStore;
    private final PlatformAuthorizationCodeExchangeRegistry authorizationCodeExchangeRegistry;
    private final PlatformAccountProfileResolverRegistry accountProfileResolverRegistry;
    private final PlatformOAuthProperties platformOAuthProperties;

    public PublicCurationTidalOAuthService(
        PublicCurationPlaylistStore playlistStore,
        PlatformAuthorizationSessionStore authorizationSessionStore,
        PublicPlaybackSessionStore publicPlaybackSessionStore,
        PlatformAuthorizationCodeExchangeRegistry authorizationCodeExchangeRegistry,
        PlatformAccountProfileResolverRegistry accountProfileResolverRegistry,
        PlatformOAuthProperties platformOAuthProperties
    ) {
        this.playlistStore = playlistStore;
        this.authorizationSessionStore = authorizationSessionStore;
        this.publicPlaybackSessionStore = publicPlaybackSessionStore;
        this.authorizationCodeExchangeRegistry = authorizationCodeExchangeRegistry;
        this.accountProfileResolverRegistry = accountProfileResolverRegistry;
        this.platformOAuthProperties = platformOAuthProperties;
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
    public record Session(
        String sessionId,
        Long playlistId,
        String tidalAccountLabel,
        String scopeSummary,
        Instant expiresAt
    ) {
    }
}
