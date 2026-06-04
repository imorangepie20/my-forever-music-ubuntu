package io.myforevermusic.api.common.errorlog;

import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApplicationErrorLogService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationErrorLogService.class);
    private static final String ADMIN_EMAIL = "jowoosungtidal@gmail.com";
    private static final int MAX_STACK_TRACE_LENGTH = 16_000;
    private static final int MAX_MESSAGE_LENGTH = 4_000;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AuthAccountStore authAccountStore;

    public ApplicationErrorLogService(NamedParameterJdbcTemplate jdbcTemplate, AuthAccountStore authAccountStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.authAccountStore = authAccountStore;
    }

    public void recordException(RecordExceptionCommand command) {
        String message = firstNonBlank(command.message(), exceptionMessage(command.exception()), "Unknown error");
        String errorType = command.exception() == null ? command.errorType() : command.exception().getClass().getName();
        record(new RecordCommand(
            firstNonBlank(command.source(), "api"),
            firstNonBlank(command.severity(), severityFor(command.statusCode())),
            firstNonBlank(command.service(), "api"),
            command.statusCode(),
            firstNonBlank(errorType, "unknown"),
            message,
            stackTrace(command.exception()),
            command.requestMethod(),
            command.requestPath(),
            command.userId(),
            command.traceId(),
            command.contextJson()
        ), true);
    }

    public void recordClient(RecordClientCommand command) {
        record(new RecordCommand(
            firstNonBlank(command.source(), "web-client"),
            firstNonBlank(command.severity(), "error"),
            "web",
            command.statusCode(),
            firstNonBlank(command.errorType(), "client_error"),
            firstNonBlank(command.message(), "Client error"),
            command.stackTrace(),
            command.requestMethod(),
            command.requestPath(),
            command.userId(),
            command.traceId(),
            command.contextJson()
        ), false);
    }

    public void recordSchedulerFailure(String source, String message, Throwable exception, String contextJson) {
        recordException(new RecordExceptionCommand(
            source,
            "error",
            "api",
            null,
            exception == null ? "scheduler_failure" : exception.getClass().getName(),
            message,
            exception,
            "SCHEDULED",
            source,
            null,
            null,
            contextJson
        ));
    }

    public ErrorLogPage listForAdmin(
        String adminUserId,
        String severity,
        String source,
        boolean unresolvedOnly,
        int limit
    ) {
        assertAdmin(adminUserId);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("limit", safeLimit);
        StringBuilder sql = new StringBuilder(
            """
                select application_error_log_id, source, severity, service, status_code, error_type,
                       message, stack_trace, request_method, request_path, user_id, trace_id,
                       fingerprint, occurrence_count, first_seen_at, last_seen_at, resolved_at,
                       context_json::text as context_json
                 from application_error_log
                 where 1 = 1
                """
        );
        if (hasText(severity)) {
            sql.append(" and severity = :severity\n");
            params.addValue("severity", severity);
        }
        if (hasText(source)) {
            sql.append(" and source = :source\n");
            params.addValue("source", source);
        }
        if (unresolvedOnly) {
            sql.append(" and resolved_at is null\n");
        }
        sql.append(" order by last_seen_at desc, application_error_log_id desc\n limit :limit\n");

        List<ErrorLogEntry> entries = jdbcTemplate.query(
            sql.toString(),
            params,
            rowMapper()
        );
        return new ErrorLogPage("ok", Instant.now(), entries);
    }

    public ErrorLogEntry resolveForAdmin(String adminUserId, long logId) {
        assertAdmin(adminUserId);
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("id", logId)
            .addValue("resolvedAt", OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        int updated = jdbcTemplate.update(
            """
                update application_error_log
                   set resolved_at = :resolvedAt
                 where application_error_log_id = :id
                """,
            params
        );
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Error log was not found.");
        }
        return jdbcTemplate.queryForObject(
            """
                select application_error_log_id, source, severity, service, status_code, error_type,
                       message, stack_trace, request_method, request_path, user_id, trace_id,
                       fingerprint, occurrence_count, first_seen_at, last_seen_at, resolved_at,
                       context_json::text as context_json
                  from application_error_log
                 where application_error_log_id = :id
                """,
            new MapSqlParameterSource("id", logId),
            rowMapper()
        );
    }

    private void record(RecordCommand command, boolean suppressStorageFailure) {
        Instant now = Instant.now();
        OffsetDateTime nowOffset = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        String fingerprint = fingerprint(command);
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("source", truncate(firstNonBlank(command.source(), "api"), 80))
            .addValue("severity", truncate(firstNonBlank(command.severity(), "error"), 20))
            .addValue("service", truncate(firstNonBlank(command.service(), "api"), 80))
            .addValue("statusCode", command.statusCode())
            .addValue("errorType", truncate(command.errorType(), 255))
            .addValue("message", truncate(firstNonBlank(command.message(), "Unknown error"), MAX_MESSAGE_LENGTH))
            .addValue("stackTrace", truncate(command.stackTrace(), MAX_STACK_TRACE_LENGTH))
            .addValue("requestMethod", truncate(command.requestMethod(), 20))
            .addValue("requestPath", truncate(command.requestPath(), 500))
            .addValue("userId", truncate(command.userId(), 100))
            .addValue("traceId", truncate(command.traceId(), 100))
            .addValue("fingerprint", fingerprint)
            .addValue("now", nowOffset, Types.TIMESTAMP_WITH_TIMEZONE)
            .addValue("contextJson", command.contextJson(), Types.VARCHAR);
        try {
            jdbcTemplate.update(
                """
                    insert into application_error_log (
                        source, severity, service, status_code, error_type, message, stack_trace,
                        request_method, request_path, user_id, trace_id, fingerprint,
                        occurrence_count, first_seen_at, last_seen_at, context_json
                    ) values (
                        :source, :severity, :service, :statusCode, :errorType, :message, :stackTrace,
                        :requestMethod, :requestPath, :userId, :traceId, :fingerprint,
                        1, :now, :now, cast(:contextJson as jsonb)
                    )
                    on conflict (fingerprint) do update
                       set severity = excluded.severity,
                           service = excluded.service,
                           status_code = excluded.status_code,
                           error_type = excluded.error_type,
                           message = excluded.message,
                           stack_trace = excluded.stack_trace,
                           request_method = excluded.request_method,
                           request_path = excluded.request_path,
                           user_id = excluded.user_id,
                           trace_id = excluded.trace_id,
                           occurrence_count = application_error_log.occurrence_count + 1,
                           last_seen_at = excluded.last_seen_at,
                           resolved_at = null,
                           context_json = coalesce(excluded.context_json, application_error_log.context_json)
                    """,
                params
            );
        } catch (DataAccessException exception) {
            if (!suppressStorageFailure) {
                throw exception;
            }
            log.warn(
                "Application error log storage failed. source={}, errorType={}, requestPath={}",
                command.source(),
                command.errorType(),
                command.requestPath(),
                exception
            );
        }
    }

    private void assertAdmin(String userId) {
        String normalizedEmail = authAccountStore.findByUserId(userId)
            .map(account -> account.normalizedEmail())
            .orElse("");
        if (!ADMIN_EMAIL.equals(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Application error log admin access is restricted.");
        }
    }

    private RowMapper<ErrorLogEntry> rowMapper() {
        return (rs, rowNum) -> new ErrorLogEntry(
            rs.getLong("application_error_log_id"),
            rs.getString("source"),
            rs.getString("severity"),
            rs.getString("service"),
            (Integer) rs.getObject("status_code"),
            rs.getString("error_type"),
            rs.getString("message"),
            rs.getString("stack_trace"),
            rs.getString("request_method"),
            rs.getString("request_path"),
            rs.getString("user_id"),
            rs.getString("trace_id"),
            rs.getString("fingerprint"),
            rs.getInt("occurrence_count"),
            rs.getTimestamp("first_seen_at").toInstant(),
            rs.getTimestamp("last_seen_at").toInstant(),
            rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
            rs.getString("context_json")
        );
    }

    private static String severityFor(Integer statusCode) {
        if (statusCode == null || statusCode >= 500) {
            return "error";
        }
        if (statusCode >= 400) {
            return "warning";
        }
        return "info";
    }

    private static String exceptionMessage(Throwable exception) {
        return exception == null ? null : exception.getMessage();
    }

    private static String stackTrace(Throwable exception) {
        if (exception == null) {
            return null;
        }
        StringWriter writer = new StringWriter();
        exception.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static String fingerprint(RecordCommand command) {
        String seed = "%s|%s|%s|%s|%s".formatted(
            firstNonBlank(command.source(), "api"),
            firstNonBlank(command.errorType(), "unknown"),
            firstNonBlank(command.message(), ""),
            firstNonBlank(command.requestMethod(), ""),
            firstNonBlank(command.requestPath(), "")
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            return Integer.toHexString(seed.hashCode());
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record RecordExceptionCommand(
        String source,
        String severity,
        String service,
        Integer statusCode,
        String errorType,
        String message,
        Throwable exception,
        String requestMethod,
        String requestPath,
        String userId,
        String traceId,
        String contextJson
    ) {}

    public record RecordClientCommand(
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

    private record RecordCommand(
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
        String contextJson
    ) {}

    public record ErrorLogPage(
        String status,
        Instant generatedAt,
        List<ErrorLogEntry> entries
    ) {}

    public record ErrorLogEntry(
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
    ) {}
}
