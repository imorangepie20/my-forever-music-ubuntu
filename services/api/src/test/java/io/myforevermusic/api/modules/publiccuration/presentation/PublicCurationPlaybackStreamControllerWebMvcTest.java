package io.myforevermusic.api.modules.publiccuration.presentation;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.myforevermusic.api.modules.publiccuration.application.PublicCurationPlaybackStreamService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PublicCurationPlaybackStreamController.class)
@AutoConfigureMockMvc(addFilters = false)
class PublicCurationPlaybackStreamControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PublicCurationPlaybackStreamService service;

    @Test
    void shouldReturnPublicTidalStream() throws Exception {
        when(service.stream("rainy-night", "public-session-1", 100L, "HIGH"))
            .thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/public-curations/share/rainy-night/playback/tracks/100/stream")
                .queryParam("public_session_id", "public-session-1")
                .queryParam("quality", "HIGH"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("public-curation-playback-stream"))
            .andExpect(jsonPath("$.track_id").value(100))
            .andExpect(jsonPath("$.tidal_track_id").value("10001"))
            .andExpect(jsonPath("$.stream_url").value("https://media.example/10001.m3u8"));
    }

    private PublicCurationPlaybackStreamService.PublicStreamResponse sampleResponse() {
        return new PublicCurationPlaybackStreamService.PublicStreamResponse(
            "public-curation-playback-stream",
            "ok",
            Instant.parse("2026-05-30T04:10:00Z"),
            42L,
            "public-session-1",
            100L,
            "10001",
            "KR",
            "HIGH",
            "HIGH",
            "aac",
            320,
            44100,
            16,
            "FULL",
            "application/vnd.apple.mpegurl",
            null,
            null,
            181.0,
            "https://media.example/10001.m3u8"
        );
    }
}
