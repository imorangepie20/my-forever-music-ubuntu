package io.myforevermusic.api.modules.publiccuration.presentation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.modules.publiccuration.application.PublicCurationPlaylistStore;
import io.myforevermusic.api.modules.publiccuration.application.PublicPlaylistPlayEventStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/public-curations/share/{slug}/playback/events")
public class PublicCurationPlaybackEventController {

    private final PublicCurationPlaylistStore playlistStore;
    private final PublicPlaylistPlayEventStore eventStore;

    public PublicCurationPlaybackEventController(
        PublicCurationPlaylistStore playlistStore,
        PublicPlaylistPlayEventStore eventStore
    ) {
        this.playlistStore = playlistStore;
        this.eventStore = eventStore;
    }

    @PostMapping
    public PublicPlaybackEventResponse recordEvent(
        @PathVariable String slug,
        @Valid @RequestBody PublicPlaybackEventRequest request
    ) {
        PublicCurationPlaylistStore.StoredPlaylist playlist = playlistStore.findPublishedBySlug(slug)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Published public curation playlist was not found."
            ));
        Instant receivedAt = Instant.now();
        Instant occurredAt = request.occurredAt() == null ? receivedAt : request.occurredAt();
        PublicPlaylistPlayEventStore.StoredEvent event = eventStore.record(new PublicPlaylistPlayEventStore.RecordEvent(
            playlist.playlistId(),
            request.publicSessionId(),
            request.trackId(),
            request.eventType(),
            request.positionMs(),
            request.durationMs(),
            occurredAt,
            receivedAt
        ));
        return PublicPlaybackEventResponse.from(event);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PublicPlaybackEventRequest(
        @Size(max = 120) String publicSessionId,
        Long trackId,
        @NotBlank @Size(max = 40) String eventType,
        @Min(0) Integer positionMs,
        @Min(0) Integer durationMs,
        Instant occurredAt
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PublicPlaybackEventResponse(
        String service,
        String status,
        Event event
    ) {
        static PublicPlaybackEventResponse from(PublicPlaylistPlayEventStore.StoredEvent event) {
            return new PublicPlaybackEventResponse(
                "public-playback-event",
                "recorded",
                Event.from(event)
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Event(
        Long eventId,
        Long playlistId,
        String publicSessionId,
        Long trackId,
        String eventType,
        Integer positionMs,
        Integer durationMs,
        Instant occurredAt,
        Instant receivedAt
    ) {
        static Event from(PublicPlaylistPlayEventStore.StoredEvent event) {
            return new Event(
                event.eventId(),
                event.playlistId(),
                event.publicSessionId(),
                event.trackId(),
                event.eventType(),
                event.positionMs(),
                event.durationMs(),
                event.occurredAt(),
                event.receivedAt()
            );
        }
    }
}
