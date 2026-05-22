package io.myforevermusic.api.modules.recommendation.presentation;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionJobStore;
import io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AudioFeatureCompletionAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class AudioFeatureCompletionAdminControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AudioFeatureCompletionService completionService;

    @Test
    void shouldEnqueueMissingAudioFeatureJobs() throws Exception {
        when(completionService.enqueueMissingAudioFeatures(
            eq("admin-user"),
            eq("target-user"),
            eq("all"),
            eq(200),
            eq(100)
        )).thenReturn(new AudioFeatureCompletionService.EnqueueCompletionResult(
            "target-user",
            "all",
            3,
            2,
            1,
            List.of(storedJob())
        ));

        mockMvc.perform(post("/api/v1/recommendations/admin/audio-feature-completion/enqueue")
                .param("user_id", "admin-user")
                .param("target_user_id", "target-user")
                .param("scope", "all")
                .param("pms_limit", "200")
                .param("ems_limit", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.target_user_id").value("target-user"))
            .andExpect(jsonPath("$.scope").value("all"))
            .andExpect(jsonPath("$.scanned_track_count").value(3))
            .andExpect(jsonPath("$.enqueued_job_count").value(2))
            .andExpect(jsonPath("$.skipped_existing_job_count").value(1))
            .andExpect(jsonPath("$.jobs[0].job_id").value(7))
            .andExpect(jsonPath("$.jobs[0].track_scope").value("pms_user_track"))
            .andExpect(jsonPath("$.jobs[0].requested_reason").value("pms_import"));
    }

    @Test
    void shouldReturnRecentJobs() throws Exception {
        when(completionService.findRecentJobs(eq("admin-user"), eq("queued"), eq(20)))
            .thenReturn(List.of(storedJob()));

        mockMvc.perform(get("/api/v1/recommendations/admin/audio-feature-completion/jobs")
                .param("user_id", "admin-user")
                .param("status", "queued")
                .param("limit", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobs[0].job_id").value(7))
            .andExpect(jsonPath("$.jobs[0].status").value("queued"))
            .andExpect(jsonPath("$.jobs[0].created_at").value("2026-05-20T00:00:00Z"));
    }

    @Test
    void shouldProcessQueuedJobs() throws Exception {
        when(completionService.processQueuedJobs(eq("admin-user"), eq("manual-worker"), eq(10)))
            .thenReturn(new AudioFeatureCompletionService.ProcessCompletionResult(
                3,
                2,
                1,
                0,
                0
            ));

        mockMvc.perform(post("/api/v1/recommendations/admin/audio-feature-completion/process")
                .param("user_id", "admin-user")
                .param("worker_id", "manual-worker")
                .param("limit", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.claimed_job_count").value(3))
            .andExpect(jsonPath("$.completed_job_count").value(2))
            .andExpect(jsonPath("$.retry_wait_job_count").value(1))
            .andExpect(jsonPath("$.unresolved_job_count").value(0))
            .andExpect(jsonPath("$.failed_job_count").value(0));
    }

    @Test
    void shouldRequeueUnresolvedJobs() throws Exception {
        when(completionService.requeueUnresolvedJobs(
            eq("admin-user"),
            eq("target-user"),
            eq("pms_user_track"),
            eq("reccobeats_no_match"),
            eq("manual_llm_retry"),
            eq(25)
        )).thenReturn(new AudioFeatureCompletionService.RequeueUnresolvedResult(
            "target-user",
            "pms_user_track",
            "reccobeats_no_match",
            "manual_llm_retry",
            25,
            17,
            8,
            List.of(manualLlmRetryJob())
        ));

        mockMvc.perform(post("/api/v1/recommendations/admin/audio-feature-completion/requeue-unresolved")
                .param("user_id", "admin-user")
                .param("target_user_id", "target-user")
                .param("track_scope", "pms_user_track")
                .param("last_error", "reccobeats_no_match")
                .param("retry_reason", "manual_llm_retry")
                .param("limit", "25"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.target_user_id").value("target-user"))
            .andExpect(jsonPath("$.track_scope").value("pms_user_track"))
            .andExpect(jsonPath("$.last_error").value("reccobeats_no_match"))
            .andExpect(jsonPath("$.retry_reason").value("manual_llm_retry"))
            .andExpect(jsonPath("$.scanned_job_count").value(25))
            .andExpect(jsonPath("$.requeued_job_count").value(17))
            .andExpect(jsonPath("$.skipped_existing_job_count").value(8))
            .andExpect(jsonPath("$.jobs[0].requested_reason").value("manual_llm_retry"));
    }

    @Test
    void shouldEnqueuePositiveEventAudioFeatureJobs() throws Exception {
        when(completionService.enqueuePositiveEventAudioFeatures(
            eq("admin-user"),
            eq("target-user"),
            eq("ems"),
            eq(500),
            eq(10)
        )).thenReturn(new AudioFeatureCompletionService.EnqueueCompletionResult(
            "target-user",
            "ems_positive_events",
            3,
            1,
            2,
            List.of(positiveAudioTasteRetryJob())
        ));

        mockMvc.perform(post("/api/v1/recommendations/admin/audio-feature-completion/enqueue-positive-events")
                .param("user_id", "admin-user")
                .param("target_user_id", "target-user")
                .param("track_scope", "ems")
                .param("event_limit", "500")
                .param("limit", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.target_user_id").value("target-user"))
            .andExpect(jsonPath("$.scope").value("ems_positive_events"))
            .andExpect(jsonPath("$.scanned_track_count").value(3))
            .andExpect(jsonPath("$.enqueued_job_count").value(1))
            .andExpect(jsonPath("$.skipped_existing_job_count").value(2))
            .andExpect(jsonPath("$.jobs[0].requested_reason").value("positive_audio_taste_retry"));
    }

    private AudioFeatureCompletionJobStore.StoredJob positiveAudioTasteRetryJob() {
        Instant now = Instant.parse("2026-05-21T00:00:00Z");
        return new AudioFeatureCompletionJobStore.StoredJob(
            88L,
            "pms_user_track",
            "track-positive-retry",
            "target-user",
            130,
            "queued",
            "positive_audio_taste_retry",
            0,
            null,
            null,
            null,
            null,
            now,
            now
        );
    }

    private AudioFeatureCompletionJobStore.StoredJob manualLlmRetryJob() {
        Instant now = Instant.parse("2026-05-21T00:00:00Z");
        return new AudioFeatureCompletionJobStore.StoredJob(
            77L,
            "pms_user_track",
            "track-llm-retry",
            "target-user",
            110,
            "queued",
            "manual_llm_retry",
            0,
            null,
            null,
            null,
            null,
            now,
            now
        );
    }

    private AudioFeatureCompletionJobStore.StoredJob storedJob() {
        Instant now = Instant.parse("2026-05-20T00:00:00Z");
        return new AudioFeatureCompletionJobStore.StoredJob(
            7L,
            "pms_user_track",
            "track-001",
            "target-user",
            100,
            "queued",
            "pms_import",
            0,
            null,
            null,
            null,
            null,
            now,
            now
        );
    }
}
