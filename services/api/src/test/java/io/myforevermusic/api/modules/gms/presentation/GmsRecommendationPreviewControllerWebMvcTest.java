package io.myforevermusic.api.modules.gms.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GmsRecommendationPreviewController.class)
@AutoConfigureMockMvc(addFilters = false)
class GmsRecommendationPreviewControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private GmsRecommendationPreviewService gmsRecommendationPreviewService;

    @Test
    void shouldReturnRecommendationPreview() throws Exception {
        when(gmsRecommendationPreviewService.previewRecommendations(any())).thenReturn(sampleResponse());

        GmsRecommendationPreviewRequest request = new GmsRecommendationPreviewRequest(
            "preview-001",
            "user-123",
            "playlist-001",
            "gms",
            "upbeat",
            4,
            3,
            3,
            List.of("track-alpha", "track-beta"),
            List.of("Artist One"),
            List.of("synth-pop"),
            true
        );

        mockMvc.perform(post("/api/v1/gms/recommendations/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("ai"))
            .andExpect(jsonPath("$.context.strategy").value("gms-hybrid-blend"))
            .andExpect(jsonPath("$.input_summary.limit").value(3))
            .andExpect(jsonPath("$.items[0].track_id").value("rec-track-alpha-01"))
            .andExpect(jsonPath("$.items[0].audio_feature_track_id").value("rec-track-alpha-01"));
    }

    @Test
    void shouldRejectInvalidLimit() throws Exception {
        mockMvc.perform(post("/api/v1/gms/recommendations/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "mode": "gms",
                      "limit": 60
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    private GmsRecommendationPreviewResponse sampleResponse() {
        return new GmsRecommendationPreviewResponse(
            "preview-001",
            Instant.parse("2026-04-29T12:00:00Z"),
            "ai",
            "ok",
            new GmsRecommendationPreviewResponse.RecommendationContext(
                "gms-hybrid-blend",
                "rule-based-preview-v1",
                "gms",
                "upbeat",
                4,
                List.of("track-alpha", "track-beta", "artist-one")
            ),
            new GmsRecommendationPreviewResponse.RecommendationInputSummary(
                "user-123",
                "playlist-001",
                2,
                1,
                1,
                3,
                3
            ),
            List.of(
                new GmsRecommendationPreviewResponse.RecommendationItem(
                    1,
                    "rec-track-alpha-01",
                    "Track Alpha Echo",
                    "The Track Alpha",
                    "spotify",
                    "playlist-001",
                    "Forever Midnight Drive",
                    "Signal Bloom",
                    null,
                    "https://open.spotify.com/track/rec-track-alpha-01",
                    "spotify:track:rec-track-alpha-01",
                    null,
                    "rec-track-alpha-01",
                    214000,
                    0.97,
                    "gms",
                    4,
                    "Track Alpha was selected by gms-hybrid-blend to support an upbeat listening flow."
                )
            ),
            List.of()
        );
    }
}
