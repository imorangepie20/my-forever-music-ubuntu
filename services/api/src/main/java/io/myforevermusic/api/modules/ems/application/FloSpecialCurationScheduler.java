package io.myforevermusic.api.modules.ems.application;

import io.myforevermusic.api.modules.ems.application.EmsCollectionService.FloSpecialCollectionFailure;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService.FloSpecialCollectionResult;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@org.springframework.context.annotation.Profile("!local")
@Component
public class FloSpecialCurationScheduler {

    private static final Logger log = LoggerFactory.getLogger(FloSpecialCurationScheduler.class);

    private final EmsCollectionService emsCollectionService;
    private final FloSpecialProperties properties;
    private final AtomicReference<FloSpecialUpdateRun> lastRun = new AtomicReference<>();

    public FloSpecialCurationScheduler(
        EmsCollectionService emsCollectionService,
        FloSpecialProperties properties
    ) {
        this.emsCollectionService = emsCollectionService;
        this.properties = properties;
    }

    @Scheduled(
        initialDelayString = "${app.ems.flo-special.initial-delay-ms:120000}",
        fixedDelayString = "${app.ems.flo-special.refresh-interval-ms:86400000}"
    )
    public void refreshScheduled() {
        refresh("scheduled");
    }

    public FloSpecialUpdateRun refreshNow() {
        return refresh("manual");
    }

    public FloSpecialUpdateRun lastRun() {
        return lastRun.get();
    }

    private FloSpecialUpdateRun refresh(String trigger) {
        Instant startedAt = Instant.now();
        if (!properties.isEnabled()) {
            FloSpecialUpdateRun run = new FloSpecialUpdateRun(
                trigger,
                "skipped",
                startedAt,
                Instant.now(),
                0,
                0,
                0,
                List.of(),
                "FLO special refresh is disabled by app.ems.flo-special.enabled."
            );
            lastRun.set(run);
            return run;
        }

        try {
            FloSpecialCollectionResult result = emsCollectionService.collectFloSpecial();
            String status = result.failures().isEmpty() ? "completed" : "completed_with_failures";
            FloSpecialUpdateRun run = new FloSpecialUpdateRun(
                trigger,
                status,
                startedAt,
                result.collectedAt(),
                result.sectionCount(),
                result.collectedPlaylistCount(),
                result.collectedTrackCount(),
                result.failures(),
                "FLO special refresh %s: %d section(s), %d playlist(s), %d track(s).".formatted(
                    status,
                    result.sectionCount(),
                    result.collectedPlaylistCount(),
                    result.collectedTrackCount()
                )
            );
            lastRun.set(run);
            log.info(run.message());
            return run;
        } catch (RuntimeException exception) {
            FloSpecialUpdateRun run = new FloSpecialUpdateRun(
                trigger,
                "failed",
                startedAt,
                Instant.now(),
                0,
                0,
                0,
                List.of(new FloSpecialCollectionFailure(null, null, exception.getMessage())),
                "FLO special refresh failed: %s".formatted(exception.getMessage())
            );
            lastRun.set(run);
            log.warn(run.message());
            return run;
        }
    }

    public record FloSpecialUpdateRun(
        String trigger,
        String status,
        Instant startedAt,
        Instant completedAt,
        int sectionCount,
        int collectedPlaylistCount,
        int collectedTrackCount,
        List<FloSpecialCollectionFailure> failures,
        String message
    ) {}
}
