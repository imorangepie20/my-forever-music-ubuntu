package io.myforevermusic.api.modules.melon.presentation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService;
import io.myforevermusic.api.modules.melon.application.MelonChartService;
import java.time.Instant;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@org.springframework.context.annotation.Profile("!local")
@RestController
@RequestMapping("/api/v1/admin/melon")
public class MelonChartAdminController {

    private final MelonChartService melonChartService;

    public MelonChartAdminController(MelonChartService melonChartService) {
        this.melonChartService = melonChartService;
    }

    @PostMapping("/scrape")
    public ScrapeResponse scrape() {
        int count = melonChartService.refresh();
        return new ScrapeResponse("ok", count, Instant.now());
    }

    @PostMapping("/materialize-ems")
    public MaterializeEmsResponse materializeEms() {
        EmsCollectionService.MelonHot100CollectionResult result = melonChartService.materializeCurrentChartToEms();
        return new MaterializeEmsResponse(
            "ok",
            result.playlistId(),
            result.collectedPlaylistCount(),
            result.collectedTrackCount(),
            result.collectedAt()
        );
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScrapeResponse(String status, int trackCount, Instant ranAt) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MaterializeEmsResponse(
        String status,
        Long playlistId,
        int collectedPlaylistCount,
        int collectedTrackCount,
        Instant materializedAt
    ) {
    }
}
