package io.myforevermusic.api.modules.recommendation.application;

import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AudioFeatureCompletionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AudioFeatureCompletionScheduler.class);

    private final Optional<AudioFeatureCompletionWorkerService> workerService;
    private final Optional<ApplicationErrorLogService> errorLogService;

    @Value("${app.audio-features.completion.scheduler.enabled:false}")
    private boolean enabled;

    @Value("${app.audio-features.completion.scheduler.worker-id:audio-feature-completion-scheduler}")
    private String workerId;

    @Value("${app.audio-features.completion.scheduler.batch-limit:20}")
    private int batchLimit;

    private volatile AudioFeatureCompletionRun lastRun;

    public AudioFeatureCompletionScheduler(
        Optional<AudioFeatureCompletionWorkerService> workerService,
        Optional<ApplicationErrorLogService> errorLogService
    ) {
        this.workerService = workerService;
        this.errorLogService = errorLogService;
    }

    @Scheduled(
        fixedDelayString = "${app.audio-features.completion.scheduler.fixed-delay-ms:300000}",
        initialDelayString = "${app.audio-features.completion.scheduler.initial-delay-ms:60000}"
    )
    public void run() {
        if (!enabled) {
            return;
        }
        Instant startedAt = Instant.now();
        if (workerService.isEmpty()) {
            String message = "Audio feature completion worker is not available for this profile.";
            lastRun = new AudioFeatureCompletionRun("skipped", message, startedAt, Instant.now());
            log.warn(message);
            return;
        }
        try {
            AudioFeatureCompletionWorkerService.ProcessCompletionResult result = workerService.get()
                .processQueuedJobs(normalizedWorkerId(), normalizedBatchLimit());
            String message = summary(result);
            lastRun = new AudioFeatureCompletionRun("completed", message, startedAt, Instant.now());
            log.info("Audio feature completion scheduler tick {}", message);
        } catch (RuntimeException exception) {
            String message = truncate(exception.getMessage());
            lastRun = new AudioFeatureCompletionRun("failed", message, startedAt, Instant.now());
            log.warn("Audio feature completion scheduler failed: {}", message);
            errorLogService.ifPresent(service -> service.recordSchedulerFailure(
                "audio-feature-completion",
                "Audio feature completion scheduler failed: %s".formatted(message),
                exception,
                null
            ));
        }
    }

    public AudioFeatureCompletionRun lastRun() {
        return lastRun;
    }

    private String normalizedWorkerId() {
        return workerId == null || workerId.isBlank()
            ? "audio-feature-completion-scheduler"
            : workerId.trim();
    }

    private int normalizedBatchLimit() {
        if (batchLimit <= 0) {
            return 20;
        }
        return Math.min(batchLimit, 200);
    }

    private String summary(AudioFeatureCompletionWorkerService.ProcessCompletionResult result) {
        return "claimed=%d completed=%d retry_wait=%d unresolved=%d failed=%d".formatted(
            result.claimedJobCount(),
            result.completedJobCount(),
            result.retryWaitJobCount(),
            result.unresolvedJobCount(),
            result.failedJobCount()
        );
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }

    public record AudioFeatureCompletionRun(
        String status,
        String message,
        Instant startedAt,
        Instant completedAt
    ) {}
}
