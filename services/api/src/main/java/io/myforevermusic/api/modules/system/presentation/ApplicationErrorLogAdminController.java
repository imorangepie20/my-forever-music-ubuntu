package io.myforevermusic.api.modules.system.presentation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService;
import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService.ErrorLogEntry;
import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService.RecordClientCommand;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApplicationErrorLogAdminController {

    private final ApplicationErrorLogService errorLogService;

    public ApplicationErrorLogAdminController(ApplicationErrorLogService errorLogService) {
        this.errorLogService = errorLogService;
    }

    @Operation(summary = "List application error logs for admin operations")
    @GetMapping("/api/v1/system/admin/error-logs")
    public ApplicationErrorLogListResponse list(
        @RequestParam("user_id") String userId,
        @RequestParam(value = "severity", required = false) String severity,
        @RequestParam(value = "source", required = false) String source,
        @RequestParam(value = "unresolved_only", defaultValue = "false") boolean unresolvedOnly,
        @RequestParam(value = "limit", defaultValue = "100") int limit
    ) {
        ApplicationErrorLogService.ErrorLogPage page = errorLogService.listForAdmin(
            userId,
            severity,
            source,
            unresolvedOnly,
            limit
        );
        return new ApplicationErrorLogListResponse(
            "api",
            page.status(),
            page.generatedAt(),
            page.entries().stream().map(ApplicationErrorLogItem::from).toList()
        );
    }

    @Operation(summary = "Mark an application error log as resolved")
    @PatchMapping("/api/v1/system/admin/error-logs/{logId}/resolve")
    public ApplicationErrorLogItem resolve(
        @PathVariable("logId") long logId,
        @RequestParam("user_id") String userId
    ) {
        return ApplicationErrorLogItem.from(errorLogService.resolveForAdmin(userId, logId));
    }

    @Operation(summary = "Record a client-side application error")
    @PostMapping("/api/v1/system/error-logs/client")
    public ClientErrorLogResponse recordClient(@RequestBody ClientErrorLogRequest request) {
        errorLogService.recordClient(new RecordClientCommand(
            request.source(),
            request.severity(),
            request.statusCode(),
            request.errorType(),
            request.message(),
            request.stackTrace(),
            request.requestMethod(),
            request.requestPath(),
            request.userId(),
            request.traceId(),
            request.contextJson()
        ));
        return new ClientErrorLogResponse("api", "recorded", Instant.now());
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ApplicationErrorLogListResponse(
        String service,
        String status,
        Instant generatedAt,
        List<ApplicationErrorLogItem> entries
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ApplicationErrorLogItem(
        Long errorLogId,
        String source,
        String severity,
        String service,
        Integer statusCode,
        String errorType,
        String message,
        String stackTrace,
        String requestMethod,
        String requestPath,
        String userId,
        String traceId,
        String fingerprint,
        int occurrenceCount,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant resolvedAt,
        String contextJson
    ) {
        static ApplicationErrorLogItem from(ErrorLogEntry entry) {
            return new ApplicationErrorLogItem(
                entry.errorLogId(),
                entry.source(),
                entry.severity(),
                entry.service(),
                entry.statusCode(),
                entry.errorType(),
                entry.message(),
                entry.stackTrace(),
                entry.requestMethod(),
                entry.requestPath(),
                entry.userId(),
                entry.traceId(),
                entry.fingerprint(),
                entry.occurrenceCount(),
                entry.firstSeenAt(),
                entry.lastSeenAt(),
                entry.resolvedAt(),
                entry.contextJson()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ClientErrorLogRequest(
        String source,
        String severity,
        Integer statusCode,
        String errorType,
        String message,
        String stackTrace,
        String requestMethod,
        String requestPath,
        String userId,
        String traceId,
        String contextJson
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ClientErrorLogResponse(
        String service,
        String status,
        Instant recordedAt
    ) {}
}
