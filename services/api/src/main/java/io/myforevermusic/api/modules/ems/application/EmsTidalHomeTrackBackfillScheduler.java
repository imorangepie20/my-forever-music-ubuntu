package io.myforevermusic.api.modules.ems.application;

import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService.EmsTidalHomeBackfillSummary;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService.EmsTidalHomeTrackBackfillResult;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@org.springframework.context.annotation.Profile("!local")
@Component
public class EmsTidalHomeTrackBackfillScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmsTidalHomeTrackBackfillScheduler.class);

    private final EmsCollectionService collectionService;
    private final EmsTidalHomeTrackBackfillProperties properties;
    private final Optional<ApplicationErrorLogService> errorLogService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<EmsTidalHomeTrackBackfillRun> lastRun = new AtomicReference<>();

    public EmsTidalHomeTrackBackfillScheduler(
        EmsCollectionService collectionService,
        EmsTidalHomeTrackBackfillProperties properties,
        Optional<ApplicationErrorLogService> errorLogService
    ) {
        this.collectionService = collectionService;
        this.properties = properties;
        this.errorLogService = errorLogService;
    }

    @Scheduled(
        initialDelayString = "${app.ems.tidal-home-track-backfill.initial-delay-ms:180000}",
        fixedDelayString = "${app.ems.tidal-home-track-backfill.refresh-interval-ms:60000}"
    )
    public void backfillScheduled() {
        backfill("scheduled");
    }

    public EmsTidalHomeTrackBackfillRun runNow() {
        return backfill("manual");
    }

    public EmsTidalHomeTrackBackfillRun lastRun() {
        return lastRun.get();
    }

    public EmsTidalHomeTrackBackfillStatus status() {
        return new EmsTidalHomeTrackBackfillStatus(
            properties.isEnabled(),
            properties.getBatchSize(),
            lastRun(),
            collectionService.getTidalHomeBackfillSummary()
        );
    }

    private EmsTidalHomeTrackBackfillRun backfill(String trigger) {
        Instant startedAt = Instant.now();
        if (!properties.isEnabled()) {
            return remember(new EmsTidalHomeTrackBackfillRun(
                trigger,
                "skipped",
                startedAt,
                Instant.now(),
                0,
                0,
                0,
                0,
                "EMS TIDAL home track backfill is disabled."
            ));
        }
        if (!running.compareAndSet(false, true)) {
            return remember(new EmsTidalHomeTrackBackfillRun(
                trigger,
                "skipped",
                startedAt,
                Instant.now(),
                0,
                0,
                0,
                0,
                "EMS TIDAL home track backfill skipped because a previous run is still active."
            ));
        }
        try {
            List<EmsCollectedPlaylistEntity> playlists =
                collectionService.getPendingTidalHomeTrackBackfillPlaylists(properties.getBatchSize());
            int processed = 0;
            int failed = 0;
            int linkedTracks = 0;
            for (EmsCollectedPlaylistEntity playlist : playlists) {
                try {
                    EmsTidalHomeTrackBackfillResult result =
                        collectionService.backfillTidalHomePlaylistTracks(playlist.getId());
                    processed++;
                    linkedTracks += result.linkedTrackCount();
                } catch (RuntimeException exception) {
                    failed++;
                    log.warn(
                        "EMS TIDAL home track backfill failed playlist_id={}: {}",
                        playlist.getId(),
                        exception.getMessage()
                    );
                    errorLogService.ifPresent(service -> service.recordSchedulerFailure(
                        "ems-tidal-home-track-backfill",
                        "EMS TIDAL home track backfill failed playlist_id=%d: %s"
                            .formatted(playlist.getId(), exception.getMessage()),
                        exception,
                        "{\"playlistId\":%d}".formatted(playlist.getId())
                    ));
                }
            }
            String status = failed == 0 ? "completed" : "completed_with_failures";
            return remember(new EmsTidalHomeTrackBackfillRun(
                trigger,
                status,
                startedAt,
                Instant.now(),
                playlists.size(),
                processed,
                failed,
                linkedTracks,
                "EMS TIDAL home track backfill %s.".formatted(status)
            ));
        } finally {
            running.set(false);
        }
    }

    private EmsTidalHomeTrackBackfillRun remember(EmsTidalHomeTrackBackfillRun run) {
        lastRun.set(run);
        return run;
    }

    public record EmsTidalHomeTrackBackfillRun(
        String trigger,
        String status,
        Instant startedAt,
        Instant completedAt,
        int candidatePlaylistCount,
        int processedPlaylistCount,
        int failedPlaylistCount,
        int linkedTrackCount,
        String message
    ) {}

    public record EmsTidalHomeTrackBackfillStatus(
        boolean enabled,
        int batchSize,
        EmsTidalHomeTrackBackfillRun lastRun,
        EmsTidalHomeBackfillSummary summary
    ) {}
}
