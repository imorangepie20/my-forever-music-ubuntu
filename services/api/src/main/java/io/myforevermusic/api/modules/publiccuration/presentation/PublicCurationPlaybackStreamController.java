package io.myforevermusic.api.modules.publiccuration.presentation;

import io.myforevermusic.api.modules.publiccuration.application.PublicCurationPlaybackStreamService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public-curations/share/{slug}/playback")
public class PublicCurationPlaybackStreamController {

    private final PublicCurationPlaybackStreamService service;

    public PublicCurationPlaybackStreamController(PublicCurationPlaybackStreamService service) {
        this.service = service;
    }

    @GetMapping("/tracks/{track_id}/stream")
    public PublicCurationPlaybackStreamService.PublicStreamResponse stream(
        @PathVariable String slug,
        @PathVariable("track_id") Long trackId,
        @RequestParam("public_session_id") @NotBlank String publicSessionId,
        @RequestParam(value = "quality", defaultValue = "HIGH") String quality
    ) {
        return service.stream(slug, publicSessionId, trackId, quality);
    }
}
