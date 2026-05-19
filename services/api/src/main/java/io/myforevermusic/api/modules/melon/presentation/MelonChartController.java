package io.myforevermusic.api.modules.melon.presentation;

import io.myforevermusic.api.modules.melon.application.MelonChartResolverService;
import io.myforevermusic.api.modules.melon.application.MelonChartService;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@org.springframework.context.annotation.Profile("!local")
@RestController
@RequestMapping("/api/v1/main-page")
public class MelonChartController {

    private static final int DEFAULT_LIMIT = 10;

    private final MelonChartService melonChartService;
    private final MelonChartResolverService melonChartResolverService;

    public MelonChartController(
        MelonChartService melonChartService,
        MelonChartResolverService melonChartResolverService
    ) {
        this.melonChartService = melonChartService;
        this.melonChartResolverService = melonChartResolverService;
    }

    @GetMapping("/melon-hot-100")
    public ResponseEntity<MelonChartTrackResponse.ListEnvelope> getTop(
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        int effective = limit == null || limit <= 0 ? DEFAULT_LIMIT : limit;
        List<MelonChartTrackResponse> tracks = melonChartService.getTopTracks(effective);
        if (tracks.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        Instant snapshotAt = tracks.get(0).snapshotAt();
        return ResponseEntity.ok(new MelonChartTrackResponse.ListEnvelope(snapshotAt, tracks));
    }

    @GetMapping("/melon-hot-100/full")
    public ResponseEntity<MelonChartTrackResponse.ListEnvelope> getFull() {
        List<MelonChartTrackResponse> tracks = melonChartService.getFullChart();
        if (tracks.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        Instant snapshotAt = tracks.get(0).snapshotAt();
        return ResponseEntity.ok(new MelonChartTrackResponse.ListEnvelope(snapshotAt, tracks));
    }

    @GetMapping("/melon-hot-100/{rank}/resolve")
    public ResponseEntity<MelonResolveResponse> resolve(
        @PathVariable("rank") int rank,
        @RequestParam(value = "user_id", required = false) String userId
    ) {
        return melonChartResolverService
            .resolveByRank(rank, userId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
