package io.myforevermusic.api.modules.system.presentation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService.EmsTidalHomeBackfillSummary;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService.EmsTidalHomeSourceBackfillSummary;
import io.myforevermusic.api.modules.ems.application.EmsTidalHomeTrackBackfillScheduler.EmsTidalHomeTrackBackfillRun;
import io.myforevermusic.api.modules.ems.application.EmsTidalHomeTrackBackfillScheduler.EmsTidalHomeTrackBackfillStatus;
import io.myforevermusic.api.modules.system.application.SchedulingAdminService;
import io.myforevermusic.api.modules.system.application.SchedulingAdminService.ScheduledServiceStatus;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system/admin/schedules")
public class SchedulingAdminController {

    private final SchedulingAdminService schedulingAdminService;

    public SchedulingAdminController(SchedulingAdminService schedulingAdminService) {
        this.schedulingAdminService = schedulingAdminService;
    }

    @Operation(summary = "Get scheduler cadence and health for admin operations")
    @GetMapping
    public SchedulingAdminResponse getSchedules(@RequestParam("user_id") String userId) {
        return SchedulingAdminResponse.from(schedulingAdminService.summarize(userId));
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SchedulingAdminResponse(
        String service,
        String status,
        Instant generatedAt,
        List<ScheduledServiceItem> schedules,
        List<String> recommendations,
        TidalHomeBackfillStatusItem tidalHomeBackfill
    ) {
        static SchedulingAdminResponse from(SchedulingAdminService.SchedulingAdminReport report) {
            return new SchedulingAdminResponse(
                "api",
                report.status(),
                report.generatedAt(),
                report.schedules().stream().map(ScheduledServiceItem::from).toList(),
                report.recommendations(),
                TidalHomeBackfillStatusItem.from(report.tidalHomeBackfill())
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScheduledServiceItem(
        String id,
        String domain,
        String name,
        String mode,
        boolean enabled,
        boolean configured,
        String status,
        Long fixedDelayMs,
        Long initialDelayMs,
        String cadenceLabel,
        String purpose,
        String managementPath,
        String lastStatus,
        String lastMessage,
        Instant lastStartedAt,
        Instant lastCompletedAt,
        List<String> configKeys,
        List<String> notes
    ) {
        static ScheduledServiceItem from(ScheduledServiceStatus schedule) {
            return new ScheduledServiceItem(
                schedule.id(),
                schedule.domain(),
                schedule.name(),
                schedule.mode(),
                schedule.enabled(),
                schedule.configured(),
                schedule.status(),
                schedule.fixedDelayMs(),
                schedule.initialDelayMs(),
                schedule.cadenceLabel(),
                schedule.purpose(),
                schedule.managementPath(),
                schedule.lastStatus(),
                schedule.lastMessage(),
                schedule.lastStartedAt(),
                schedule.lastCompletedAt(),
                schedule.configKeys(),
                schedule.notes()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TidalHomeBackfillStatusItem(
        boolean enabled,
        int batchSize,
        TidalHomeBackfillRunItem lastRun,
        TidalHomeBackfillSummaryItem summary
    ) {
        static TidalHomeBackfillStatusItem from(EmsTidalHomeTrackBackfillStatus status) {
            if (status == null) {
                return null;
            }
            return new TidalHomeBackfillStatusItem(
                status.enabled(),
                status.batchSize(),
                TidalHomeBackfillRunItem.from(status.lastRun()),
                TidalHomeBackfillSummaryItem.from(status.summary())
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TidalHomeBackfillRunItem(
        String trigger,
        String status,
        Instant startedAt,
        Instant completedAt,
        int candidatePlaylistCount,
        int processedPlaylistCount,
        int failedPlaylistCount,
        int linkedTrackCount,
        String message
    ) {
        static TidalHomeBackfillRunItem from(EmsTidalHomeTrackBackfillRun run) {
            if (run == null) {
                return null;
            }
            return new TidalHomeBackfillRunItem(
                run.trigger(),
                run.status(),
                run.startedAt(),
                run.completedAt(),
                run.candidatePlaylistCount(),
                run.processedPlaylistCount(),
                run.failedPlaylistCount(),
                run.linkedTrackCount(),
                run.message()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TidalHomeBackfillSummaryItem(
        long playlistCount,
        long playlistWithTracksCount,
        long playlistWithoutTracksCount,
        long linkedTrackCount,
        double completionRatio,
        List<TidalHomeBackfillSourceSummaryItem> sources
    ) {
        static TidalHomeBackfillSummaryItem from(EmsTidalHomeBackfillSummary summary) {
            if (summary == null) {
                return null;
            }
            return new TidalHomeBackfillSummaryItem(
                summary.playlistCount(),
                summary.playlistWithTracksCount(),
                summary.playlistWithoutTracksCount(),
                summary.linkedTrackCount(),
                summary.completionRatio(),
                summary.sources().stream().map(TidalHomeBackfillSourceSummaryItem::from).toList()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TidalHomeBackfillSourceSummaryItem(
        String sourceId,
        long playlistCount,
        long playlistWithTracksCount,
        long playlistWithoutTracksCount,
        long linkedTrackCount,
        double completionRatio
    ) {
        static TidalHomeBackfillSourceSummaryItem from(EmsTidalHomeSourceBackfillSummary source) {
            return new TidalHomeBackfillSourceSummaryItem(
                source.sourceId(),
                source.playlistCount(),
                source.playlistWithTracksCount(),
                source.playlistWithoutTracksCount(),
                source.linkedTrackCount(),
                source.completionRatio()
            );
        }
    }
}
