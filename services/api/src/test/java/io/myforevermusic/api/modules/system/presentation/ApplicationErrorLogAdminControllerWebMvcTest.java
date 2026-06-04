package io.myforevermusic.api.modules.system.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService;
import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService.ErrorLogEntry;
import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService.RecordClientCommand;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ApplicationErrorLogAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class ApplicationErrorLogAdminControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ApplicationErrorLogService errorLogService;

    @Test
    void shouldListAdminErrorLogs() throws Exception {
        when(errorLogService.listForAdmin("admin-user", "error", "platform", true, 50))
            .thenReturn(new ApplicationErrorLogService.ErrorLogPage(
                "ok",
                Instant.parse("2026-06-01T01:00:00Z"),
                List.of(errorLogEntry())
            ));

        mockMvc.perform(get("/api/v1/system/admin/error-logs")
                .param("user_id", "admin-user")
                .param("severity", "error")
                .param("source", "platform")
                .param("unresolved_only", "true")
                .param("limit", "50"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("api"))
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.entries[0].error_log_id").value(10))
            .andExpect(jsonPath("$.entries[0].source").value("platform"))
            .andExpect(jsonPath("$.entries[0].request_path").value("/api/v1/platforms/playback/tidal/tracks/1/stream"))
            .andExpect(jsonPath("$.entries[0].occurrence_count").value(3));
    }

    @Test
    void shouldResolveAdminErrorLog() throws Exception {
        when(errorLogService.resolveForAdmin("admin-user", 10L)).thenReturn(errorLogEntry());

        mockMvc.perform(patch("/api/v1/system/admin/error-logs/10/resolve")
                .param("user_id", "admin-user"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error_log_id").value(10))
            .andExpect(jsonPath("$.fingerprint").value("fingerprint-1"));
    }

    @Test
    void shouldRecordClientRuntimeError() throws Exception {
        mockMvc.perform(post("/api/v1/system/error-logs/client")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "source": "web-runtime",
                      "severity": "error",
                      "error_type": "window_error",
                      "message": "Cannot read properties of undefined",
                      "request_path": "/pms",
                      "user_id": "user-1"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("recorded"));

        verify(errorLogService).recordClient(any(RecordClientCommand.class));
    }

    private ErrorLogEntry errorLogEntry() {
        return new ErrorLogEntry(
            10L,
            "platform",
            "error",
            "api",
            502,
            "tidal_manifest_decode_failed",
            "TIDAL playback manifest could not be decoded.",
            "stack trace",
            "GET",
            "/api/v1/platforms/playback/tidal/tracks/1/stream",
            "user-1",
            "trace-1",
            "fingerprint-1",
            3,
            Instant.parse("2026-06-01T00:00:00Z"),
            Instant.parse("2026-06-01T00:30:00Z"),
            null,
            "{\"track_id\":\"1\"}"
        );
    }
}
