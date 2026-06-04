package io.myforevermusic.api.modules.melon.application;

import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically refreshes the Melon Hot 100 snapshot.
 *
 * Disabled by default. Enable via:
 *   app.melon.scrape.enabled=true
 *
 * Defaults to one run every 24h with a 5-minute initial delay so a freshly
 * deployed instance does not hit the source CDN during startup. Override:
 *   app.melon.scrape.fixed-delay-ms (default 86_400_000)
 *   app.melon.scrape.initial-delay-ms (default 300_000)
 */
@org.springframework.context.annotation.Profile("!local")
@Component
public class MelonChartScraperScheduler {

    private static final Logger log = LoggerFactory.getLogger(MelonChartScraperScheduler.class);

    private final MelonChartService melonChartService;
    private final Optional<ApplicationErrorLogService> errorLogService;

    private volatile MelonChartScrapeRun lastRun;

    @Value("${app.melon.scrape.enabled:false}")
    private boolean enabled;

    public MelonChartScraperScheduler(
        MelonChartService melonChartService,
        Optional<ApplicationErrorLogService> errorLogService
    ) {
        this.melonChartService = melonChartService;
        this.errorLogService = errorLogService;
    }

    public MelonChartScrapeRun lastRun() {
        return lastRun;
    }

    @Scheduled(
        fixedDelayString = "${app.melon.scrape.fixed-delay-ms:86400000}",
        initialDelayString = "${app.melon.scrape.initial-delay-ms:300000}"
    )
    public void run() {
        if (!enabled) {
            return;
        }
        Instant startedAt = Instant.now();
        try {
            int count = melonChartService.refresh();
            lastRun = new MelonChartScrapeRun(
                "completed",
                count,
                "Stored %d Melon Hot 100 tracks and materialized EMS playlist.".formatted(count),
                startedAt,
                Instant.now()
            );
            log.info("Melon chart auto-refresh stored {} tracks", count);
        } catch (Exception exception) {
            lastRun = new MelonChartScrapeRun(
                "failed",
                0,
                exception.getMessage(),
                startedAt,
                Instant.now()
            );
            log.warn("Melon chart auto-refresh failed: {}", exception.getMessage());
            errorLogService.ifPresent(service -> service.recordSchedulerFailure(
                "melon-hot-100-scrape",
                "Melon chart auto-refresh failed: %s".formatted(exception.getMessage()),
                exception,
                null
            ));
        }
    }

    public record MelonChartScrapeRun(
        String status,
        int trackCount,
        String message,
        Instant startedAt,
        Instant completedAt
    ) {
    }
}
