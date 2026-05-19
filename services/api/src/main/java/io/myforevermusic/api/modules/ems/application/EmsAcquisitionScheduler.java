package io.myforevermusic.api.modules.ems.application;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@org.springframework.context.annotation.Profile("!local")
@Component
public class EmsAcquisitionScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmsAcquisitionScheduler.class);

    private final EmsAcquisitionService acquisitionService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EmsAcquisitionScheduler(EmsAcquisitionService acquisitionService) {
        this.acquisitionService = acquisitionService;
    }

    @Scheduled(
        initialDelayString = "${app.ems.acquisition.initial-delay-ms:60000}",
        fixedDelayString = "${app.ems.acquisition.refresh-interval-ms:86400000}"
    )
    public void collectEditorialSignals() {
        if (!running.compareAndSet(false, true)) {
            log.info("EMS acquisition skipped because a previous run is still active.");
            return;
        }
        try {
            acquisitionService.runScheduled();
        } catch (RuntimeException exception) {
            log.warn("EMS acquisition scheduled run failed: {}", exception.getMessage());
        } finally {
            running.set(false);
        }
    }
}
