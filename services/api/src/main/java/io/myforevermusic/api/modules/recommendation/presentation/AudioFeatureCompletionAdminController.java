package io.myforevermusic.api.modules.recommendation.presentation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionJobStore;
import io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionService;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recommendations/admin/audio-feature-completion")
public class AudioFeatureCompletionAdminController {

    private final AudioFeatureCompletionService completionService;

    public AudioFeatureCompletionAdminController(AudioFeatureCompletionService completionService) {
        this.completionService = completionService;
    }

    @Operation(summary = "Enqueue PMS and EMS tracks missing usable audio features")
    @PostMapping("/enqueue")
    public EnqueueCompletionResponse enqueueMissingAudioFeatures(
        @RequestParam("user_id") String userId,
        @RequestParam(value = "target_user_id", required = false) String targetUserId,
        @RequestParam(value = "scope", defaultValue = "all") String scope,
        @RequestParam(value = "pms_limit", defaultValue = "100") int pmsLimit,
        @RequestParam(value = "ems_limit", defaultValue = "100") int emsLimit
    ) {
        return EnqueueCompletionResponse.from(completionService.enqueueMissingAudioFeatures(
            userId,
            targetUserId,
            scope,
            pmsLimit,
            emsLimit
        ));
    }

    @Operation(summary = "List recent audio feature completion jobs")
    @GetMapping("/jobs")
    public RecentCompletionJobsResponse getRecentJobs(
        @RequestParam("user_id") String userId,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        return new RecentCompletionJobsResponse(
            completionService.findRecentJobs(userId, status, limit).stream()
                .map(CompletionJobItem::from)
                .toList()
        );
    }

    @Operation(summary = "Process queued audio feature completion jobs")
    @PostMapping("/process")
    public ProcessCompletionResponse processQueuedJobs(
        @RequestParam("user_id") String userId,
        @RequestParam(value = "worker_id", defaultValue = "manual-worker") String workerId,
        @RequestParam(value = "limit", defaultValue = "20") int limit
    ) {
        return ProcessCompletionResponse.from(completionService.processQueuedJobs(userId, workerId, limit));
    }

    @Operation(summary = "Requeue unresolved audio feature completion jobs for manual inference retry")
    @PostMapping("/requeue-unresolved")
    public RequeueUnresolvedResponse requeueUnresolvedJobs(
        @RequestParam("user_id") String userId,
        @RequestParam(value = "target_user_id", required = false) String targetUserId,
        @RequestParam(value = "track_scope", required = false) String trackScope,
        @RequestParam(value = "last_error", required = false) String lastError,
        @RequestParam(value = "retry_reason", defaultValue = "manual_llm_retry") String retryReason,
        @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        return RequeueUnresolvedResponse.from(completionService.requeueUnresolvedJobs(
            userId,
            targetUserId,
            trackScope,
            lastError,
            retryReason,
            limit
        ));
    }

    @Operation(summary = "Enqueue positive-event PMS or EMS tracks missing audio features")
    @PostMapping("/enqueue-positive-events")
    public EnqueueCompletionResponse enqueuePositiveEventAudioFeatures(
        @RequestParam("user_id") String userId,
        @RequestParam(value = "target_user_id", required = false) String targetUserId,
        @RequestParam(value = "track_scope", defaultValue = "all") String trackScope,
        @RequestParam(value = "event_limit", defaultValue = "500") int eventLimit,
        @RequestParam(value = "limit", defaultValue = "20") int limit
    ) {
        return EnqueueCompletionResponse.from(completionService.enqueuePositiveEventAudioFeatures(
            userId,
            targetUserId,
            trackScope,
            eventLimit,
            limit
        ));
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EnqueueCompletionResponse(
        String targetUserId,
        String scope,
        int scannedTrackCount,
        int enqueuedJobCount,
        int skippedExistingJobCount,
        List<CompletionJobItem> jobs
    ) {
        static EnqueueCompletionResponse from(AudioFeatureCompletionService.EnqueueCompletionResult result) {
            return new EnqueueCompletionResponse(
                result.targetUserId(),
                result.scope(),
                result.scannedTrackCount(),
                result.enqueuedJobCount(),
                result.skippedExistingJobCount(),
                result.jobs().stream().map(CompletionJobItem::from).toList()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RecentCompletionJobsResponse(
        List<CompletionJobItem> jobs
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProcessCompletionResponse(
        int claimedJobCount,
        int completedJobCount,
        int retryWaitJobCount,
        int unresolvedJobCount,
        int failedJobCount
    ) {
        static ProcessCompletionResponse from(AudioFeatureCompletionService.ProcessCompletionResult result) {
            return new ProcessCompletionResponse(
                result.claimedJobCount(),
                result.completedJobCount(),
                result.retryWaitJobCount(),
                result.unresolvedJobCount(),
                result.failedJobCount()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RequeueUnresolvedResponse(
        String targetUserId,
        String trackScope,
        String lastError,
        String retryReason,
        int scannedJobCount,
        int requeuedJobCount,
        int skippedExistingJobCount,
        List<CompletionJobItem> jobs
    ) {
        static RequeueUnresolvedResponse from(AudioFeatureCompletionService.RequeueUnresolvedResult result) {
            return new RequeueUnresolvedResponse(
                result.targetUserId(),
                result.trackScope(),
                result.lastError(),
                result.retryReason(),
                result.scannedJobCount(),
                result.requeuedJobCount(),
                result.skippedExistingJobCount(),
                result.jobs().stream().map(CompletionJobItem::from).toList()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CompletionJobItem(
        Long jobId,
        String trackScope,
        String trackId,
        String userId,
        int priority,
        String status,
        String requestedReason,
        int attemptCount,
        Instant nextRetryAt,
        Instant lockedAt,
        String lockedBy,
        String lastError,
        Instant createdAt,
        Instant updatedAt
    ) {
        static CompletionJobItem from(AudioFeatureCompletionJobStore.StoredJob job) {
            return new CompletionJobItem(
                job.jobId(),
                job.trackScope(),
                job.trackId(),
                job.userId(),
                job.priority(),
                job.status(),
                job.requestedReason(),
                job.attemptCount(),
                job.nextRetryAt(),
                job.lockedAt(),
                job.lockedBy(),
                job.lastError(),
                job.createdAt(),
                job.updatedAt()
            );
        }
    }
}
