package io.myforevermusic.api.modules.ems.application;

import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService;
import io.myforevermusic.api.modules.ems.application.EmsLooseTrackPlaylistService.LooseTrackPlaylistMaterializationResult;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@org.springframework.context.annotation.Profile("!local")
@Component
public class EmsLooseTrackPlaylistScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmsLooseTrackPlaylistScheduler.class);

    private final EmsLooseTrackPlaylistService playlistService;
    private final EmsLooseTrackPlaylistProperties properties;
    private final Optional<ApplicationErrorLogService> errorLogService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<EmsLooseTrackPlaylistRun> lastRun = new AtomicReference<>();

    public EmsLooseTrackPlaylistScheduler(
        EmsLooseTrackPlaylistService playlistService,
        EmsLooseTrackPlaylistProperties properties,
        Optional<ApplicationErrorLogService> errorLogService
    ) {
        this.playlistService = playlistService;
        this.properties = properties;
        this.errorLogService = errorLogService;
    }

    @Scheduled(
        initialDelayString = "${app.ems.loose-track-playlists.initial-delay-ms:300000}",
        fixedDelayString = "${app.ems.loose-track-playlists.refresh-interval-ms:86400000}"
    )
    public void materializeScheduled() {
        materialize("scheduled");
    }

    public EmsLooseTrackPlaylistRun runNow() {
        return materialize("manual");
    }

    public EmsLooseTrackPlaylistRun lastRun() {
        return lastRun.get();
    }

    private EmsLooseTrackPlaylistRun materialize(String trigger) {
        Instant startedAt = Instant.now();
        if (!properties.isEnabled()) {
            EmsLooseTrackPlaylistRun run = skipped(
                trigger,
                startedAt,
                "EMS loose track playlist materialization is disabled by app.ems.loose-track-playlists.enabled."
            );
            lastRun.set(run);
            return run;
        }
        if (!running.compareAndSet(false, true)) {
            EmsLooseTrackPlaylistRun run = skipped(
                trigger,
                startedAt,
                "EMS loose track playlist materialization skipped because a previous run is still active."
            );
            lastRun.set(run);
            log.info(run.message());
            return run;
        }
        try {
            long unassignedTrackCount = playlistService.countUnassignedTracks();
            int minTrackCount = Math.max(1, properties.getMinTrackCount());
            if (unassignedTrackCount < minTrackCount) {
                EmsLooseTrackPlaylistRun run = new EmsLooseTrackPlaylistRun(
                    trigger,
                    "skipped",
                    startedAt,
                    Instant.now(),
                    unassignedTrackCount,
                    0,
                    0,
                    0,
                    unassignedTrackCount,
                    "EMS loose track playlist materialization skipped: %d unassigned track(s), threshold is %d."
                        .formatted(unassignedTrackCount, minTrackCount)
                );
                lastRun.set(run);
                log.info(run.message());
                return run;
            }

            LooseTrackPlaylistMaterializationResult result = playlistService.materializeLooseTracks(
                properties.getTrackLimit(),
                properties.getTracksPerPlaylist()
            );
            EmsLooseTrackPlaylistRun run = new EmsLooseTrackPlaylistRun(
                trigger,
                "completed",
                startedAt,
                result.materializedAt(),
                result.unassignedTrackCountBefore(),
                result.selectedTrackCount(),
                result.createdPlaylistCount(),
                result.linkedTrackCount(),
                result.unassignedTrackCountAfter(),
                "EMS loose track playlist materialization completed: %d playlist(s), %d track(s).".formatted(
                    result.createdPlaylistCount(),
                    result.linkedTrackCount()
                )
            );
            lastRun.set(run);
            log.info(run.message());
            return run;
        } catch (RuntimeException exception) {
            EmsLooseTrackPlaylistRun run = new EmsLooseTrackPlaylistRun(
                trigger,
                "failed",
                startedAt,
                Instant.now(),
                0,
                0,
                0,
                0,
                0,
                "EMS loose track playlist materialization failed: %s".formatted(exception.getMessage())
            );
            lastRun.set(run);
            log.warn(run.message());
            errorLogService.ifPresent(service -> service.recordSchedulerFailure(
                "ems-loose-track-playlists",
                run.message(),
                exception,
                null
            ));
            return run;
        } finally {
            running.set(false);
        }
    }

    private EmsLooseTrackPlaylistRun skipped(String trigger, Instant startedAt, String message) {
        return new EmsLooseTrackPlaylistRun(
            trigger,
            "skipped",
            startedAt,
            Instant.now(),
            0,
            0,
            0,
            0,
            0,
            message
        );
    }

    public record EmsLooseTrackPlaylistRun(
        String trigger,
        String status,
        Instant startedAt,
        Instant completedAt,
        long unassignedTrackCountBefore,
        int selectedTrackCount,
        int createdPlaylistCount,
        int linkedTrackCount,
        long unassignedTrackCountAfter,
        String message
    ) {}
}
