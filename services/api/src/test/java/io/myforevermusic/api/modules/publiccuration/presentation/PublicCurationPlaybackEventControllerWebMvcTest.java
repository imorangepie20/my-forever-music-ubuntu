package io.myforevermusic.api.modules.publiccuration.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.myforevermusic.api.modules.publiccuration.application.PublicCurationPlaylistStore;
import io.myforevermusic.api.modules.publiccuration.application.PublicPlaylistPlayEventStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PublicCurationPlaybackEventController.class)
@AutoConfigureMockMvc(addFilters = false)
class PublicCurationPlaybackEventControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PublicCurationPlaylistStore playlistStore;

    @MockBean
    private PublicPlaylistPlayEventStore eventStore;

    @Test
    void shouldRecordPublicPlaybackEventForPublishedPlaylist() throws Exception {
        when(playlistStore.findPublishedBySlug("rainy-night-public-curation"))
            .thenReturn(Optional.of(publishedPlaylist()));
        when(eventStore.record(any(PublicPlaylistPlayEventStore.RecordEvent.class)))
            .thenReturn(storedEvent());

        mockMvc.perform(post("/api/v1/public-curations/share/rainy-night-public-curation/playback/events")
                .contentType("application/json")
                .content("""
                    {
                      "public_session_id": "public-session-001",
                      "track_id": 100,
                      "event_type": "play_started",
                      "position_ms": 0,
                      "duration_ms": 181000,
                      "occurred_at": "2026-05-30T02:10:00Z"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("public-playback-event"))
            .andExpect(jsonPath("$.status").value("recorded"))
            .andExpect(jsonPath("$.event.playlist_id").value(10))
            .andExpect(jsonPath("$.event.event_type").value("play_started"))
            .andExpect(jsonPath("$.event.public_session_id").value("public-session-001"));

        ArgumentCaptor<PublicPlaylistPlayEventStore.RecordEvent> eventCaptor =
            ArgumentCaptor.forClass(PublicPlaylistPlayEventStore.RecordEvent.class);
        verify(eventStore).record(eventCaptor.capture());
        assertThat(eventCaptor.getValue().playlistId()).isEqualTo(10L);
        assertThat(eventCaptor.getValue().trackId()).isEqualTo(100L);
        assertThat(eventCaptor.getValue().eventType()).isEqualTo("play_started");
    }

    @Test
    void shouldRejectEventWhenPlaylistIsNotPublished() throws Exception {
        when(playlistStore.findPublishedBySlug("draft-only"))
            .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/public-curations/share/draft-only/playback/events")
                .contentType("application/json")
                .content("""
                    {
                      "event_type": "play_started",
                      "occurred_at": "2026-05-30T02:10:00Z"
                    }
                    """))
            .andExpect(status().isNotFound());
    }

    private PublicCurationPlaylistStore.StoredPlaylist publishedPlaylist() {
        Instant now = Instant.parse("2026-05-30T02:00:00Z");
        return new PublicCurationPlaylistStore.StoredPlaylist(
            10L,
            "rainy-night-public-curation",
            "비 오는 밤의 Public Curation",
            "모델이 고른 외부 공유용 플레이리스트",
            "TIDAL-ready 후보를 선별했습니다.",
            "비 오는 밤에 듣기 좋은 한국 인디와 재즈 감성",
            "{\"targetTrackCount\":1}",
            "published",
            "poster-dark",
            "public-curation-deterministic-v1",
            1,
            181000L,
            now,
            "admin-001",
            now,
            now,
            List.of(),
            null
        );
    }

    private PublicPlaylistPlayEventStore.StoredEvent storedEvent() {
        Instant occurredAt = Instant.parse("2026-05-30T02:10:00Z");
        Instant receivedAt = Instant.parse("2026-05-30T02:10:01Z");
        return new PublicPlaylistPlayEventStore.StoredEvent(
            501L,
            10L,
            "public-session-001",
            100L,
            "play_started",
            0,
            181000,
            occurredAt,
            receivedAt
        );
    }
}
