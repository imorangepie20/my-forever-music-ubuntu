package io.myforevermusic.api.modules.ems.application;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@org.springframework.context.annotation.Profile("!local")
@Component
public class EmsPoolIngestWorker {

    private static final Logger log = LoggerFactory.getLogger(EmsPoolIngestWorker.class);

    private final EmsPoolIngestService ingestService;
    private final AtomicBoolean processing = new AtomicBoolean(false);

    public EmsPoolIngestWorker(EmsPoolIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onQueued(EmsPoolRunQueuedEvent event) {
        if (!processing.compareAndSet(false, true)) {
            return;
        }
        try {
            ingestService.processRun(event.runId());
        } catch (Exception e) {
            log.warn("EMS pool ingest worker failed for run {}: {}", event.runId(), e.getMessage());
        } finally {
            processing.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${app.ems.pool.worker.fixed-delay-ms:10000}")
    public void processQueuedRuns() {
        if (!processing.compareAndSet(false, true)) {
            return;
        }
        try {
            ingestService.processOldestQueuedRun();
        } catch (Exception e) {
            log.warn("EMS pool scheduled ingest failed: {}", e.getMessage());
        } finally {
            processing.set(false);
        }
    }
}
