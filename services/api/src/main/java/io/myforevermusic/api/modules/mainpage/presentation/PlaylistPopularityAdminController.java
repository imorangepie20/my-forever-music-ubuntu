package io.myforevermusic.api.modules.mainpage.presentation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.modules.mainpage.application.PlaylistPopularityRefreshService;
import io.myforevermusic.api.modules.mainpage.application.PlaylistPopularityRefreshService.RefreshResult;
import java.time.Instant;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@org.springframework.context.annotation.Profile("!local")
@RestController
@RequestMapping("/api/v1/admin/playlists")
public class PlaylistPopularityAdminController {

    private static final int DEFAULT_LIMIT = 50;

    private final PlaylistPopularityRefreshService refreshService;

    public PlaylistPopularityAdminController(PlaylistPopularityRefreshService refreshService) {
        this.refreshService = refreshService;
    }

    @PostMapping("/refresh-popularity")
    public RefreshResponse refresh(@RequestParam(value = "limit", required = false) Integer limit) {
        int effective = limit == null || limit <= 0 ? DEFAULT_LIMIT : limit;
        RefreshResult result = refreshService.refreshSpotify(effective);
        return new RefreshResponse(
            "ok",
            result.considered(),
            result.refreshed(),
            result.unchanged(),
            result.skipped(),
            Instant.now()
        );
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RefreshResponse(
        String status,
        int considered,
        int refreshed,
        int unchanged,
        int skipped,
        Instant ranAt
    ) {
    }
}
