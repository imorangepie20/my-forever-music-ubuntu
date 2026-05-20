package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackRepository;
import io.myforevermusic.api.modules.platform.infrastructure.reccobeats.ReccoBeatsAudioFeaturesClient;
import io.myforevermusic.api.modules.platform.infrastructure.reccobeats.ReccoBeatsAudioFeaturesClient.ReccoBeatsAudioFeaturesSnapshot;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsTrackAudioFeatures;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsUserTrackEntity;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsUserTrackRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AudioFeatureCompletionWorkerServiceTest {

    @Test
    void shouldCompleteQueuedPmsSpotifyJobWithReccoBeatsAudioFeatures() {
        InMemoryJobStore jobStore = new InMemoryJobStore();
        PmsUserTrackRepository pmsTrackRepository = mock(PmsUserTrackRepository.class);
        EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
        ReccoBeatsAudioFeaturesClient reccoBeatsClient = mock(ReccoBeatsAudioFeaturesClient.class);
        PmsUserTrackEntity track = new PmsUserTrackEntity(pmsTrack("pms-track-001", "spotify-track-001"));

        jobStore.addQueued(new AudioFeatureCompletionJobStore.StoredJob(
            1L,
            "pms_user_track",
            "pms-track-001",
            "user-001",
            100,
            "queued",
            "pms_import",
            0,
            null,
            null,
            null,
            null,
            Instant.parse("2026-05-20T00:00:00Z"),
            Instant.parse("2026-05-20T00:00:00Z")
        ));
        when(pmsTrackRepository.findById("pms-track-001")).thenReturn(Optional.of(track));
        when(reccoBeatsClient.getAudioFeaturesForSpotifyTrackIds(List.of("spotify-track-001")))
            .thenReturn(Map.of("spotify-track-001", snapshot("spotify-track-001")));

        AudioFeatureCompletionWorkerService service = new AudioFeatureCompletionWorkerService(
            jobStore,
            pmsTrackRepository,
            emsTrackRepository,
            reccoBeatsClient
        );

        AudioFeatureCompletionWorkerService.ProcessCompletionResult result = service.processQueuedJobs("worker-001", 10);

        assertThat(result.claimedJobCount()).isEqualTo(1);
        assertThat(result.completedJobCount()).isEqualTo(1);
        assertThat(jobStore.findById(1L).status()).isEqualTo("completed");
        assertThat(track.getAudioFeatures().isComplete()).isTrue();
        assertThat(track.getAudioFeatures().getAudioFeatureSource()).isEqualTo("reccobeats_lookup");
        assertThat(track.getAudioFeatures().getTempo()).isEqualTo(120.0d);
        verify(pmsTrackRepository).save(track);
    }

    @Test
    void shouldMoveJobToRetryWaitWhenReccoBeatsFails() {
        InMemoryJobStore jobStore = new InMemoryJobStore();
        PmsUserTrackRepository pmsTrackRepository = mock(PmsUserTrackRepository.class);
        EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
        ReccoBeatsAudioFeaturesClient reccoBeatsClient = mock(ReccoBeatsAudioFeaturesClient.class);
        PmsUserTrackEntity track = new PmsUserTrackEntity(pmsTrack("pms-track-001", "spotify-track-001"));

        jobStore.addQueued(new AudioFeatureCompletionJobStore.StoredJob(
            1L,
            "pms_user_track",
            "pms-track-001",
            "user-001",
            100,
            "queued",
            "pms_import",
            0,
            null,
            null,
            null,
            null,
            Instant.parse("2026-05-20T00:00:00Z"),
            Instant.parse("2026-05-20T00:00:00Z")
        ));
        when(pmsTrackRepository.findById("pms-track-001")).thenReturn(Optional.of(track));
        when(reccoBeatsClient.getAudioFeaturesForSpotifyTrackIds(List.of("spotify-track-001")))
            .thenThrow(new IllegalStateException("rate limited"));

        AudioFeatureCompletionWorkerService service = new AudioFeatureCompletionWorkerService(
            jobStore,
            pmsTrackRepository,
            emsTrackRepository,
            reccoBeatsClient
        );

        AudioFeatureCompletionWorkerService.ProcessCompletionResult result = service.processQueuedJobs("worker-001", 10);

        assertThat(result.retryWaitJobCount()).isEqualTo(1);
        assertThat(jobStore.findById(1L).status()).isEqualTo("retry_wait");
        assertThat(jobStore.findById(1L).lastError()).contains("rate limited");
        assertThat(jobStore.findById(1L).nextRetryAt()).isNotNull();
    }

    @Test
    void shouldApplyLastFmTagInferenceWhenReccoBeatsHasNoMatch() {
        InMemoryJobStore jobStore = new InMemoryJobStore();
        PmsUserTrackRepository pmsTrackRepository = mock(PmsUserTrackRepository.class);
        EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
        ReccoBeatsAudioFeaturesClient reccoBeatsClient = mock(ReccoBeatsAudioFeaturesClient.class);
        LastFmAudioFeatureInferenceService lastFmInferenceService = mock(LastFmAudioFeatureInferenceService.class);
        PmsUserTrackEntity track = new PmsUserTrackEntity(pmsTrack("pms-track-001", "spotify-track-001"));

        jobStore.addQueued(new AudioFeatureCompletionJobStore.StoredJob(
            1L,
            "pms_user_track",
            "pms-track-001",
            "user-001",
            100,
            "queued",
            "pms_import",
            0,
            null,
            null,
            null,
            null,
            Instant.parse("2026-05-20T00:00:00Z"),
            Instant.parse("2026-05-20T00:00:00Z")
        ));
        when(pmsTrackRepository.findById("pms-track-001")).thenReturn(Optional.of(track));
        when(reccoBeatsClient.getAudioFeaturesForSpotifyTrackIds(List.of("spotify-track-001")))
            .thenReturn(Map.of());
        when(lastFmInferenceService.infer(any()))
            .thenReturn(Optional.of(new LastFmAudioFeatureInferenceService.InferredAudioFeatureSnapshot(
                "lastfm_track_tag_inferred",
                "tag_inferred",
                0.72d,
                "lastfm-tag-rules-v1",
                false,
                180_000,
                0.2d,
                0.75d,
                0.7d,
                0.1d,
                0.12d,
                0.05d,
                118.0d,
                0.6d,
                Instant.parse("2026-05-20T01:00:00Z")
            )));

        AudioFeatureCompletionWorkerService service = new AudioFeatureCompletionWorkerService(
            jobStore,
            pmsTrackRepository,
            emsTrackRepository,
            reccoBeatsClient,
            Optional.of(lastFmInferenceService)
        );

        AudioFeatureCompletionWorkerService.ProcessCompletionResult result = service.processQueuedJobs("worker-001", 10);

        assertThat(result.unresolvedJobCount()).isEqualTo(1);
        assertThat(jobStore.findById(1L).status()).isEqualTo("unresolved");
        assertThat(jobStore.findById(1L).lastError()).isEqualTo("lastfm_tag_inferred_partial_audio_features");
        assertThat(track.getAudioFeatures().getAudioFeatureSource()).isEqualTo("lastfm_track_tag_inferred");
        assertThat(track.getAudioFeatures().isAudioFeaturesFilled()).isFalse();
        assertThat(track.getAudioFeatures().getDanceability()).isEqualTo(0.75d);
        verify(pmsTrackRepository).save(track);
    }

    @Test
    void shouldCompletePmsTrackWithLlmSearchInferenceWhenProviderAndLastFmHaveNoResult() {
        InMemoryJobStore jobStore = new InMemoryJobStore();
        PmsUserTrackRepository pmsTrackRepository = mock(PmsUserTrackRepository.class);
        EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
        ReccoBeatsAudioFeaturesClient reccoBeatsClient = mock(ReccoBeatsAudioFeaturesClient.class);
        LastFmAudioFeatureInferenceService lastFmInferenceService = mock(LastFmAudioFeatureInferenceService.class);
        AudioFeatureLlmSearchInferenceService llmSearchInferenceService = mock(AudioFeatureLlmSearchInferenceService.class);
        PmsUserTrackEntity track = new PmsUserTrackEntity(pmsTrack("pms-track-001", "spotify-track-001"));

        jobStore.addQueued(new AudioFeatureCompletionJobStore.StoredJob(
            1L,
            "pms_user_track",
            "pms-track-001",
            "user-001",
            100,
            "queued",
            "pms_import",
            0,
            null,
            null,
            null,
            null,
            Instant.parse("2026-05-20T00:00:00Z"),
            Instant.parse("2026-05-20T00:00:00Z")
        ));
        when(pmsTrackRepository.findById("pms-track-001")).thenReturn(Optional.of(track));
        when(reccoBeatsClient.getAudioFeaturesForSpotifyTrackIds(List.of("spotify-track-001")))
            .thenReturn(Map.of());
        when(lastFmInferenceService.infer(any())).thenReturn(Optional.empty());
        when(llmSearchInferenceService.infer(any()))
            .thenReturn(Optional.of(new AudioFeatureLlmSearchInferenceService.InferredAudioFeatureSnapshot(
                "llm_search_inferred",
                "llm_search_inferred",
                0.82d,
                "audio-feature-llm-search-v1:test-audio-model",
                true,
                180_000,
                5,
                1,
                0.18d,
                0.74d,
                0.79d,
                0.03d,
                0.12d,
                -6.8d,
                0.05d,
                121.0d,
                0.62d,
                Instant.parse("2026-05-20T01:00:00Z")
            )));

        AudioFeatureCompletionWorkerService service = new AudioFeatureCompletionWorkerService(
            jobStore,
            pmsTrackRepository,
            emsTrackRepository,
            reccoBeatsClient,
            Optional.of(lastFmInferenceService),
            Optional.of(llmSearchInferenceService)
        );

        AudioFeatureCompletionWorkerService.ProcessCompletionResult result = service.processQueuedJobs("worker-001", 10);

        assertThat(result.completedJobCount()).isEqualTo(1);
        assertThat(jobStore.findById(1L).status()).isEqualTo("completed");
        assertThat(track.getAudioFeatures().getAudioFeatureSource()).isEqualTo("llm_search_inferred");
        assertThat(track.getAudioFeatures().isComplete()).isTrue();
        assertThat(track.getAudioFeatures().getMusicalKey()).isEqualTo(5);
        assertThat(track.getAudioFeatures().getLoudness()).isEqualTo(-6.8d);
        verify(pmsTrackRepository).save(track);
    }

    private PmsUserLibraryStore.LibraryTrackState pmsTrack(String trackId, String spotifyTrackId) {
        return new PmsUserLibraryStore.LibraryTrackState(
            trackId,
            spotifyTrackId,
            "Signal Track",
            "Signal Artist",
            "spotify",
            null,
            "Album",
            null,
            "https://open.spotify.com/track/" + spotifyTrackId,
            "spotify:track:" + spotifyTrackId,
            null,
            "USRC17607839",
            spotifyTrackId,
            "spotify:track:" + spotifyTrackId,
            null,
            null,
            "spotify",
            "native",
            1,
            false,
            new PmsTrackAudioFeatures(
                spotifyTrackId,
                "unavailable",
                false,
                null,
                "https://open.spotify.com/track/" + spotifyTrackId,
                "spotify:track:" + spotifyTrackId,
                "audio_features",
                180000,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.parse("2026-05-20T00:00:00Z")
            )
        );
    }

    private ReccoBeatsAudioFeaturesSnapshot snapshot(String spotifyTrackId) {
        return new ReccoBeatsAudioFeaturesSnapshot(
            spotifyTrackId,
            "reccobeats-track-001",
            "https://open.spotify.com/track/" + spotifyTrackId,
            "USRC17607839",
            0.2d,
            0.7d,
            0.8d,
            0.01d,
            5,
            0.1d,
            -6.0d,
            1,
            0.04d,
            120.0d,
            0.6d,
            Instant.parse("2026-05-20T01:00:00Z")
        );
    }

    private static class InMemoryJobStore implements AudioFeatureCompletionJobStore {
        private final List<StoredJob> jobs = new ArrayList<>();

        void addQueued(StoredJob job) {
            jobs.add(job);
        }

        StoredJob findById(Long jobId) {
            return jobs.stream()
                .filter(job -> job.jobId().equals(jobId))
                .findFirst()
                .orElseThrow();
        }

        @Override
        public EnqueueOutcome enqueueIfAbsent(Draft draft) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<StoredJob> findRecent(String status, int limit) {
            return jobs;
        }

        @Override
        public List<StoredJob> findUnresolvedForRequeue(String trackScope, String userId, String lastError, int limit) {
            return jobs.stream()
                .filter(job -> "unresolved".equals(job.status()))
                .filter(job -> trackScope == null || trackScope.equals(job.trackScope()))
                .filter(job -> userId == null || userId.equals(job.userId()))
                .filter(job -> lastError == null || lastError.equals(job.lastError()))
                .limit(Math.max(0, limit))
                .toList();
        }

        @Override
        public List<StoredJob> claimQueued(String workerId, int limit, Instant now) {
            StoredJob queued = jobs.stream()
                .filter(job -> "queued".equals(job.status()))
                .findFirst()
                .orElseThrow();
            replace(queued.jobId(), copy(queued, "running", queued.attemptCount() + 1, null, now, workerId, now));
            return List.of(findById(queued.jobId()));
        }

        @Override
        public void markCompleted(Long jobId, Instant now) {
            StoredJob job = findById(jobId);
            replace(jobId, copy(job, "completed", job.attemptCount(), null, null, null, now));
        }

        @Override
        public void markRetryWait(Long jobId, String lastError, Instant nextRetryAt, Instant now) {
            StoredJob job = findById(jobId);
            replace(jobId, copy(job, "retry_wait", job.attemptCount(), nextRetryAt, null, null, now, lastError));
        }

        @Override
        public void markUnresolved(Long jobId, String lastError, Instant now) {
            StoredJob job = findById(jobId);
            replace(jobId, copy(job, "unresolved", job.attemptCount(), null, null, null, now, lastError));
        }

        @Override
        public void markFailed(Long jobId, String lastError, Instant now) {
            StoredJob job = findById(jobId);
            replace(jobId, copy(job, "failed", job.attemptCount(), null, null, null, now, lastError));
        }

        private void replace(Long jobId, StoredJob replacement) {
            for (int index = 0; index < jobs.size(); index++) {
                if (jobs.get(index).jobId().equals(jobId)) {
                    jobs.set(index, replacement);
                    return;
                }
            }
        }

        private StoredJob copy(
            StoredJob job,
            String status,
            int attemptCount,
            Instant nextRetryAt,
            Instant lockedAt,
            String lockedBy,
            Instant updatedAt
        ) {
            return copy(job, status, attemptCount, nextRetryAt, lockedAt, lockedBy, updatedAt, job.lastError());
        }

        private StoredJob copy(
            StoredJob job,
            String status,
            int attemptCount,
            Instant nextRetryAt,
            Instant lockedAt,
            String lockedBy,
            Instant updatedAt,
            String lastError
        ) {
            return new StoredJob(
                job.jobId(),
                job.trackScope(),
                job.trackId(),
                job.userId(),
                job.priority(),
                status,
                job.requestedReason(),
                attemptCount,
                nextRetryAt,
                lockedAt,
                lockedBy,
                lastError,
                job.createdAt(),
                updatedAt
            );
        }
    }
}
