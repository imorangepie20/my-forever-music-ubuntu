package io.myforevermusic.api.modules.publiccuration.presentation;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.myforevermusic.api.modules.publiccuration.application.PublicCurationTidalOAuthService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PublicCurationTidalOAuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class PublicCurationTidalOAuthControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PublicCurationTidalOAuthService service;

    @Test
    void shouldStartPublicTidalOAuth() throws Exception {
        when(service.start("rainy-night"))
            .thenReturn(sampleStart());

        mockMvc.perform(post("/api/v1/public-curations/share/rainy-night/tidal/oauth/start"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("public-curation-tidal-oauth"))
            .andExpect(jsonPath("$.status").value("authorization_pending"))
            .andExpect(jsonPath("$.authorization.state").value("public-curation-oauth-test"))
            .andExpect(jsonPath("$.authorization.external_authorization_url")
                .value("https://login.tidal.com/authorize?state=public-curation-oauth-test"));
    }

    @Test
    void shouldCompletePublicTidalOAuth() throws Exception {
        when(service.complete("rainy-night", "public-curation-oauth-test", "tidal-code"))
            .thenReturn(sampleComplete());

        mockMvc.perform(post("/api/v1/public-curations/share/rainy-night/tidal/oauth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"state":"public-curation-oauth-test","authorization_code":"tidal-code"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("public-curation-tidal-oauth"))
            .andExpect(jsonPath("$.status").value("authorization_completed"))
            .andExpect(jsonPath("$.session.session_id").value("public-curation-session-test"))
            .andExpect(jsonPath("$.return_path").value("/share/playlists/rainy-night?playback=ready"));
    }

    @Test
    void shouldReturnReadyPublicPlaybackSession() throws Exception {
        when(service.session("rainy-night", "public-curation-session-test"))
            .thenReturn(sampleSession());

        mockMvc.perform(get("/api/v1/public-curations/share/rainy-night/playback/session")
                .queryParam("session_id", "public-curation-session-test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("public-curation-playback-session"))
            .andExpect(jsonPath("$.status").value("ready"))
            .andExpect(jsonPath("$.session.session_id").value("public-curation-session-test"))
            .andExpect(jsonPath("$.session.playlist_id").value(42));
    }

    private PublicCurationTidalOAuthService.PublicTidalOAuthStartResponse sampleStart() {
        Instant now = Instant.parse("2026-05-30T03:00:00Z");
        return new PublicCurationTidalOAuthService.PublicTidalOAuthStartResponse(
            "public-curation-tidal-oauth",
            "authorization_pending",
            now,
            new PublicCurationTidalOAuthService.Authorization(
                "public-curation-oauth-test",
                "tidal",
                List.of("user.read", "collection.read", "playlists.read"),
                now.plusSeconds(600),
                "https://login.tidal.com/authorize?state=public-curation-oauth-test",
                "https://approid.team/platforms/oauth/callback"
            )
        );
    }

    private PublicCurationTidalOAuthService.PublicTidalOAuthCompleteResponse sampleComplete() {
        Instant now = Instant.parse("2026-05-30T03:05:00Z");
        return new PublicCurationTidalOAuthService.PublicTidalOAuthCompleteResponse(
            "public-curation-tidal-oauth",
            "authorization_completed",
            now,
            sampleStoredSession(now.plusSeconds(3600)),
            "/share/playlists/rainy-night?playback=ready"
        );
    }

    private PublicCurationTidalOAuthService.PublicTidalPlaybackSessionResponse sampleSession() {
        Instant now = Instant.parse("2026-05-30T03:06:00Z");
        return new PublicCurationTidalOAuthService.PublicTidalPlaybackSessionResponse(
            "public-curation-playback-session",
            "ready",
            now,
            sampleStoredSession(now.plusSeconds(3600))
        );
    }

    private PublicCurationTidalOAuthService.Session sampleStoredSession(Instant expiresAt) {
        return new PublicCurationTidalOAuthService.Session(
            "public-curation-session-test",
            42L,
            "TIDAL Listener",
            "user.read, collection.read, playlists.read",
            expiresAt
        );
    }
}
