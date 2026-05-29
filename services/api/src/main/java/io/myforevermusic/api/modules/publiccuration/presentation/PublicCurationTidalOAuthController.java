package io.myforevermusic.api.modules.publiccuration.presentation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.modules.publiccuration.application.PublicCurationTidalOAuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public-curations/share/{slug}")
public class PublicCurationTidalOAuthController {

    private final PublicCurationTidalOAuthService service;

    public PublicCurationTidalOAuthController(PublicCurationTidalOAuthService service) {
        this.service = service;
    }

    @PostMapping("/tidal/oauth/start")
    public PublicCurationTidalOAuthService.PublicTidalOAuthStartResponse start(
        @PathVariable String slug
    ) {
        return service.start(slug);
    }

    @PostMapping("/tidal/oauth/complete")
    public PublicCurationTidalOAuthService.PublicTidalOAuthCompleteResponse complete(
        @PathVariable String slug,
        @RequestBody CompleteRequest request
    ) {
        return service.complete(slug, request.state(), request.authorizationCode());
    }

    @GetMapping("/playback/session")
    public PublicCurationTidalOAuthService.PublicTidalPlaybackSessionResponse session(
        @PathVariable String slug,
        @RequestParam("session_id") String sessionId
    ) {
        return service.session(slug, sessionId);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CompleteRequest(
        String state,
        String authorizationCode
    ) {
    }
}
