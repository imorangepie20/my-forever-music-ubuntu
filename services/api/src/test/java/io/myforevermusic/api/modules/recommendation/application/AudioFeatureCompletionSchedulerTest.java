package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AudioFeatureCompletionSchedulerTest {

    @Test
    void shouldSkipWhenDisabled() {
        AudioFeatureCompletionWorkerService worker = mock(AudioFeatureCompletionWorkerService.class);
        AudioFeatureCompletionScheduler scheduler = new AudioFeatureCompletionScheduler(Optional.of(worker));
        ReflectionTestUtils.setField(scheduler, "enabled", false);

        scheduler.run();

        verifyNoInteractions(worker);
        assertThat(scheduler.lastRun()).isNull();
    }

    @Test
    void shouldSkipWhenWorkerIsUnavailable() {
        AudioFeatureCompletionScheduler scheduler = new AudioFeatureCompletionScheduler(Optional.empty());
        ReflectionTestUtils.setField(scheduler, "enabled", true);

        assertThatCode(scheduler::run).doesNotThrowAnyException();

        AudioFeatureCompletionScheduler.AudioFeatureCompletionRun lastRun = scheduler.lastRun();
        assertThat(lastRun).isNotNull();
        assertThat(lastRun.status()).isEqualTo("skipped");
        assertThat(lastRun.message()).contains("worker is not available");
    }

    @Test
    void shouldProcessQueuedJobsWhenEnabled() {
        AudioFeatureCompletionWorkerService worker = mock(AudioFeatureCompletionWorkerService.class);
        AudioFeatureCompletionScheduler scheduler = new AudioFeatureCompletionScheduler(Optional.of(worker));
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "workerId", "audio-feature-completion-scheduler");
        ReflectionTestUtils.setField(scheduler, "batchLimit", 25);
        when(worker.processQueuedJobs("audio-feature-completion-scheduler", 25))
            .thenReturn(new AudioFeatureCompletionWorkerService.ProcessCompletionResult(3, 2, 1, 0, 0));

        scheduler.run();

        verify(worker).processQueuedJobs("audio-feature-completion-scheduler", 25);
        AudioFeatureCompletionScheduler.AudioFeatureCompletionRun lastRun = scheduler.lastRun();
        assertThat(lastRun.status()).isEqualTo("completed");
        assertThat(lastRun.message()).contains("claimed=3", "completed=2", "retry_wait=1");
        assertThat(lastRun.startedAt()).isNotNull();
        assertThat(lastRun.completedAt()).isNotNull();
    }
}
