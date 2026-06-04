package io.myforevermusic.api.modules.melon.presentation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService;
import io.myforevermusic.api.modules.melon.application.MelonChartService;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@org.springframework.context.annotation.Profile("!local")
@RestController
@RequestMapping("/api/v1/admin/melon")
public class MelonChartAdminController {

    private static final String ADMIN_EMAIL = "jowoosungtidal@gmail.com";

    private final MelonChartService melonChartService;
    private final AuthAccountStore authAccountStore;

    public MelonChartAdminController(MelonChartService melonChartService, AuthAccountStore authAccountStore) {
        this.melonChartService = melonChartService;
        this.authAccountStore = authAccountStore;
    }

    @PostMapping("/scrape")
    public ScrapeResponse scrape(@RequestParam("user_id") String userId) {
        assertAdmin(userId);
        int count = melonChartService.refresh();
        return new ScrapeResponse("ok", count, Instant.now());
    }

    @PostMapping("/materialize-ems")
    public MaterializeEmsResponse materializeEms(@RequestParam("user_id") String userId) {
        assertAdmin(userId);
        EmsCollectionService.MelonHot100CollectionResult result = melonChartService.materializeCurrentChartToEms();
        return new MaterializeEmsResponse(
            "ok",
            result.playlistId(),
            result.collectedPlaylistCount(),
            result.collectedTrackCount(),
            result.collectedAt()
        );
    }

    private void assertAdmin(String userId) {
        String normalizedEmail = authAccountStore.findByUserId(userId)
            .map(account -> account.normalizedEmail())
            .orElse("");
        if (!ADMIN_EMAIL.equals(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Melon chart admin access is restricted.");
        }
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
