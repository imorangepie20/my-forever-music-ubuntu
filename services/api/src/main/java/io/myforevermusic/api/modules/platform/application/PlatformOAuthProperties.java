package io.myforevermusic.api.modules.platform.application;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.platform.oauth")
public class PlatformOAuthProperties {

    private Spotify spotify = new Spotify();
    private Tidal tidal = new Tidal();

    public Spotify getSpotify() {
        return spotify;
    }

    public void setSpotify(Spotify spotify) {
        this.spotify = spotify == null ? new Spotify() : spotify;
    }

    public Tidal getTidal() {
        return tidal;
    }

    public void setTidal(Tidal tidal) {
        this.tidal = tidal == null ? new Tidal() : tidal;
    }

    public static class Spotify {

        private boolean enabled = false;
        private String clientId = "";
        private String clientSecret = "";
        private String redirectUri = "http://localhost:5173/platforms/oauth/callback";
        private String authorizationUri = "https://accounts.spotify.com/authorize";
        private String tokenUri = "https://accounts.spotify.com/api/token";
        private String apiBaseUri = "https://api.spotify.com/v1";
        private List<String> scopes = List.of(
            "user-read-email",
            "playlist-read-private",
            "playlist-read-collaborative",
            "streaming",
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing"
        );

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId == null ? "" : clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret == null ? "" : clientSecret;
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri == null ? "" : redirectUri;
        }

        public String getAuthorizationUri() {
            return authorizationUri;
        }

        public void setAuthorizationUri(String authorizationUri) {
            this.authorizationUri = authorizationUri == null ? "" : authorizationUri;
        }

        public String getTokenUri() {
            return tokenUri;
        }

        public void setTokenUri(String tokenUri) {
            this.tokenUri = tokenUri == null ? "" : tokenUri;
        }

        public String getApiBaseUri() {
            return apiBaseUri;
        }

        public void setApiBaseUri(String apiBaseUri) {
            this.apiBaseUri = apiBaseUri == null ? "" : apiBaseUri;
        }

        public List<String> getScopes() {
            return scopes;
        }

        public void setScopes(List<String> scopes) {
            this.scopes = normalizeScopes(scopes);
        }

        public boolean isConfigured() {
            return enabled
                && !clientId.isBlank()
                && !redirectUri.isBlank()
                && !authorizationUri.isBlank()
                && !tokenUri.isBlank()
                && !apiBaseUri.isBlank();
        }
    }

    public static class Tidal {

        private boolean enabled = false;
        private String clientId = "";
        private String clientSecret = "";
        private String redirectUri = "http://localhost:5173/platforms/oauth/callback";
        private String authorizationUri = "https://login.tidal.com/authorize";
        private String tokenUri = "https://auth.tidal.com/v1/oauth2/token";
        private String apiBaseUri = "https://openapi.tidal.com/v2";
        private String legacyApiBaseUri = "https://api.tidal.com/v1";
        // Public TIDAL web app endpoints (no OAuth) used for EMS home-page discovery.
        // The endpoints gate only on the presence of an x-tidal-client-version header (the
        // value is not validated and there is no secret token to rotate).
        private String webBaseUri = "https://tidal.com";
        private String webClientVersion = "2026.5.27";
        private String countryCode = "US";
        private List<String> scopes = List.of(
            "r_usr",
            "w_usr",
            "w_sub",
            "r_stream",
            "playback",
            "entitlements.read"
        );

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId == null ? "" : clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret == null ? "" : clientSecret;
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri == null ? "" : redirectUri;
        }

        public String getAuthorizationUri() {
            return authorizationUri;
        }

        public void setAuthorizationUri(String authorizationUri) {
            this.authorizationUri = authorizationUri == null ? "" : authorizationUri;
        }

        public String getTokenUri() {
            return tokenUri;
        }

        public void setTokenUri(String tokenUri) {
            this.tokenUri = tokenUri == null ? "" : tokenUri;
        }

        public String getApiBaseUri() {
            return apiBaseUri;
        }

        public void setApiBaseUri(String apiBaseUri) {
            this.apiBaseUri = apiBaseUri == null ? "" : apiBaseUri;
        }

        public String getLegacyApiBaseUri() {
            return legacyApiBaseUri;
        }

        public void setLegacyApiBaseUri(String legacyApiBaseUri) {
            this.legacyApiBaseUri = legacyApiBaseUri == null ? "" : legacyApiBaseUri;
        }

        public String getWebBaseUri() {
            return webBaseUri;
        }

        public void setWebBaseUri(String webBaseUri) {
            this.webBaseUri = webBaseUri == null ? "" : webBaseUri;
        }

        public String getWebClientVersion() {
            return webClientVersion;
        }

        public void setWebClientVersion(String webClientVersion) {
            this.webClientVersion = webClientVersion == null ? "" : webClientVersion;
        }

        public String getCountryCode() {
            return countryCode;
        }

        public void setCountryCode(String countryCode) {
            this.countryCode = countryCode == null ? "" : countryCode;
        }

        public List<String> getScopes() {
            return scopes;
        }

        public void setScopes(List<String> scopes) {
            this.scopes = normalizeScopes(scopes);
        }

        public boolean isConfigured() {
            return enabled
                && !clientId.isBlank()
                && !redirectUri.isBlank()
                && !authorizationUri.isBlank()
                && !tokenUri.isBlank()
                && !apiBaseUri.isBlank()
                && !legacyApiBaseUri.isBlank()
                && !countryCode.isBlank()
                && !scopes.isEmpty();
        }
    }

    private static List<String> normalizeScopes(List<String> scopes) {
        if (scopes == null) {
            return List.of();
        }

        return scopes.stream()
            .flatMap(scope -> Arrays.stream((scope == null ? "" : scope).split("[,\\s]+")))
            .map(String::trim)
            .filter(scope -> !scope.isBlank())
            .distinct()
            .toList();
    }
}
