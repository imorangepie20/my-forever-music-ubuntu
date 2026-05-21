package io.myforevermusic.api.modules.recommendation.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionJobStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class JpaAudioFeatureCompletionJobStoreTest {

    @Test
    void shouldSaveJobWhenSameScopeTrackAndReasonDoesNotExist() {
        AudioFeatureCompletionJobRepository repository = mock(AudioFeatureCompletionJobRepository.class);
        JpaAudioFeatureCompletionJobStore store = new JpaAudioFeatureCompletionJobStore(repository);
        AudioFeatureCompletionJobStore.Draft draft = draft();

        when(repository.findByTrackScopeAndTrackIdAndRequestedReason(
            draft.trackScope(),
            draft.trackId(),
            draft.requestedReason()
        )).thenReturn(Optional.empty());
        when(repository.save(any(AudioFeatureCompletionJobEntity.class))).thenAnswer(invocation -> {
            AudioFeatureCompletionJobEntity entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "jobId", 10L);
            return entity;
        });

        AudioFeatureCompletionJobStore.EnqueueOutcome outcome = store.enqueueIfAbsent(draft);

        assertThat(outcome.inserted()).isTrue();
        assertThat(outcome.job().jobId()).isEqualTo(10L);
        assertThat(outcome.job().status()).isEqualTo("queued");
        verify(repository).save(any(AudioFeatureCompletionJobEntity.class));
    }

    @Test
    void shouldReturnExistingJobWhenSameScopeTrackAndReasonAlreadyExists() {
        AudioFeatureCompletionJobRepository repository = mock(AudioFeatureCompletionJobRepository.class);
        JpaAudioFeatureCompletionJobStore store = new JpaAudioFeatureCompletionJobStore(repository);
        AudioFeatureCompletionJobStore.Draft draft = draft();
        AudioFeatureCompletionJobEntity existing = new AudioFeatureCompletionJobEntity(draft);
        ReflectionTestUtils.setField(existing, "jobId", 11L);

        when(repository.findByTrackScopeAndTrackIdAndRequestedReason(
            draft.trackScope(),
            draft.trackId(),
            draft.requestedReason()
        )).thenReturn(Optional.of(existing));

        AudioFeatureCompletionJobStore.EnqueueOutcome outcome = store.enqueueIfAbsent(draft);

        assertThat(outcome.inserted()).isFalse();
        assertThat(outcome.job().jobId()).isEqualTo(11L);
        verify(repository, never()).save(any(AudioFeatureCompletionJobEntity.class));
    }

    @Test
    void shouldClaimQueuedJobsForWorker() {
        AudioFeatureCompletionJobRepository repository = mock(AudioFeatureCompletionJobRepository.class);
        JpaAudioFeatureCompletionJobStore store = new JpaAudioFeatureCompletionJobStore(repository);
        AudioFeatureCompletionJobEntity entity = new AudioFeatureCompletionJobEntity(draft());
        ReflectionTestUtils.setField(entity, "jobId", 12L);
        Instant now = Instant.parse("2026-05-20T01:00:00Z");

        when(repository.findClaimable(now, Pageable.ofSize(2))).thenReturn(List.of(entity));
        when(repository.saveAll(List.of(entity))).thenReturn(List.of(entity));

        List<AudioFeatureCompletionJobStore.StoredJob> jobs = store.claimQueued("worker-001", 2, now);

        assertThat(jobs).hasSize(1);
        assertThat(jobs.getFirst().status()).isEqualTo("running");
        assertThat(jobs.getFirst().attemptCount()).isEqualTo(1);
        assertThat(jobs.getFirst().lockedBy()).isEqualTo("worker-001");
        assertThat(jobs.getFirst().lockedAt()).isEqualTo(now);
    }

    @Test
    void shouldFindUnresolvedForRequeueWithFirstPageQueryWhenCursorIsMissing() {
        AudioFeatureCompletionJobRepository repository = mock(AudioFeatureCompletionJobRepository.class);
        JpaAudioFeatureCompletionJobStore store = new JpaAudioFeatureCompletionJobStore(repository);
        AudioFeatureCompletionJobEntity entity = new AudioFeatureCompletionJobEntity(draft());
        ReflectionTestUtils.setField(entity, "jobId", 13L);

        when(repository.findUnresolvedFirstPageForRequeue(
            "pms_user_track",
            "user-001",
            "reccobeats_no_match",
            Pageable.ofSize(10)
        )).thenReturn(List.of(entity));

        List<AudioFeatureCompletionJobStore.StoredJob> jobs = store.findUnresolvedForRequeue(
            "pms_user_track",
            "user-001",
            "reccobeats_no_match",
            null,
            null,
            10
        );

        assertThat(jobs).hasSize(1);
        assertThat(jobs.getFirst().jobId()).isEqualTo(13L);
        verify(repository, never()).findUnresolvedAfterCursorForRequeue(
            any(),
            any(),
            any(),
            any(),
            any(),
            any()
        );
    }

    @Test
    void shouldFindUnresolvedForRequeueWithCursorQueryWhenCursorExists() {
        AudioFeatureCompletionJobRepository repository = mock(AudioFeatureCompletionJobRepository.class);
        JpaAudioFeatureCompletionJobStore store = new JpaAudioFeatureCompletionJobStore(repository);
        AudioFeatureCompletionJobEntity entity = new AudioFeatureCompletionJobEntity(draft());
        ReflectionTestUtils.setField(entity, "jobId", 14L);
        Instant cursorUpdatedAt = Instant.parse("2026-05-20T02:00:00Z");

        when(repository.findUnresolvedAfterCursorForRequeue(
            "pms_user_track",
            "user-001",
            "reccobeats_no_match",
            cursorUpdatedAt,
            14L,
            Pageable.ofSize(10)
        )).thenReturn(List.of(entity));

        List<AudioFeatureCompletionJobStore.StoredJob> jobs = store.findUnresolvedForRequeue(
            "pms_user_track",
            "user-001",
            "reccobeats_no_match",
            cursorUpdatedAt,
            14L,
            10
        );

        assertThat(jobs).hasSize(1);
        assertThat(jobs.getFirst().jobId()).isEqualTo(14L);
        verify(repository, never()).findUnresolvedFirstPageForRequeue(
            any(),
            any(),
            any(),
            any()
        );
    }

    private AudioFeatureCompletionJobStore.Draft draft() {
        return new AudioFeatureCompletionJobStore.Draft(
            "pms_user_track",
            "track-001",
            "user-001",
            100,
            "pms_import",
            Instant.parse("2026-05-20T00:00:00Z")
        );
    }
}
