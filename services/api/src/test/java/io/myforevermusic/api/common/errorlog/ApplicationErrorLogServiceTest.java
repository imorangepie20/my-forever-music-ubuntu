package io.myforevermusic.api.common.errorlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService.RecordClientCommand;
import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService.RecordExceptionCommand;
import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import io.myforevermusic.api.modules.auth.application.AuthRegisteredAccount;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class ApplicationErrorLogServiceTest {

    private final NamedParameterJdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(NamedParameterJdbcTemplate.class);
    private final AuthAccountStore authAccountStore = org.mockito.Mockito.mock(AuthAccountStore.class);
    private final ApplicationErrorLogService service = new ApplicationErrorLogService(jdbcTemplate, authAccountStore);

    @BeforeEach
    void setUp() {
        when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(account(
            "admin-user",
            "jowoosungtidal@gmail.com"
        )));
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void shouldBuildListQueryWithoutNullablePreparedPredicates() {
        when(jdbcTemplate.query(
            any(String.class),
            any(MapSqlParameterSource.class),
            any(RowMapper.class)
        )).thenReturn(List.of());

        service.listForAdmin("admin-user", null, null, false, 50);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
            sqlCaptor.capture(),
            any(MapSqlParameterSource.class),
            any(RowMapper.class)
        );
        assertThat(sqlCaptor.getValue())
            .doesNotContain(":severity is null")
            .doesNotContain(":source is null")
            .doesNotContain(":unresolvedOnly = false");
    }

    @Test
    void shouldUpsertDuplicateErrorsByFingerprint() {
        service.recordException(new RecordExceptionCommand(
            "tidal-playback",
            "error",
            "api",
            502,
            "tidal_manifest_decode_failed",
            "TIDAL playback manifest could not be decoded.",
            new IllegalStateException("decode failed"),
            "GET",
            "/api/v1/platforms/playback/tidal/tracks/1/stream",
            "user-1",
            "trace-1",
            "{\"track_id\":\"1\"}"
        ));

        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).update(
            contains("on conflict (fingerprint) do update"),
            paramsCaptor.capture()
        );
        assertThat(paramsCaptor.getValue().getValue("now")).isInstanceOf(OffsetDateTime.class);
    }

    @Test
    void shouldNeverBreakOriginalFlowWhenErrorLoggingStorageFails() {
        doThrow(new DataAccessResourceFailureException("db down"))
            .when(jdbcTemplate)
            .update(any(String.class), any(MapSqlParameterSource.class));

        assertThatCode(() -> service.recordException(new RecordExceptionCommand(
            "ems",
            "error",
            "api",
            500,
            "provider_failed",
            "Provider failed",
            new RuntimeException("provider failed"),
            "POST",
            "/api/v1/ems/collection/search",
            "user-1",
            null,
            null
        ))).doesNotThrowAnyException();
    }

    @Test
    void shouldExposeClientErrorLogStorageFailures() {
        doThrow(new DataAccessResourceFailureException("db down"))
            .when(jdbcTemplate)
            .update(any(String.class), any(MapSqlParameterSource.class));

        assertThatThrownBy(() -> service.recordClient(new RecordClientCommand(
            "web-client",
            "error",
            500,
            "client_error",
            "Client error",
            null,
            "GET",
            "/admin/error-logs",
            "user-1",
            null,
            "{\"source\":\"test\"}"
        ))).isInstanceOf(DataAccessResourceFailureException.class);
    }

    private AuthRegisteredAccount account(String userId, String email) {
        return new AuthRegisteredAccount(
            userId,
            email,
            email,
            "Admin",
            "tidal",
            null,
            null,
            false,
            "done",
            Instant.parse("2026-06-01T00:00:00Z"),
            Instant.parse("2026-06-01T00:00:00Z"),
            Instant.parse("2026-06-01T00:00:00Z")
        );
    }
}
