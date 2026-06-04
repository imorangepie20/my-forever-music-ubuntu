package io.myforevermusic.api.modules.recommendation.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService;
import io.myforevermusic.api.modules.recommendation.presentation.RecommendationModelTrainingResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SasrecAutoTrainSchedulerTest {

    @Test
    void shouldTrainActiveTargetUserWithoutAdminUserAssertion() {
        RecommendationModelTrainingService trainingService = mock(RecommendationModelTrainingService.class);
        UserMusicEventStore eventStore = mock(UserMusicEventStore.class);
        SasrecAutoTrainLogStore trainLogStore = mock(SasrecAutoTrainLogStore.class);
        SasrecAutoTrainScheduler scheduler = new SasrecAutoTrainScheduler(
            trainingService,
            eventStore,
            trainLogStore,
            Optional.empty()
        );

        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "activeWindowHours", 168);
        ReflectionTestUtils.setField(scheduler, "maxActiveUsers", 2);
        ReflectionTestUtils.setField(scheduler, "minEventDelta", 50);
        ReflectionTestUtils.setField(scheduler, "eventLimit", 0);
        ReflectionTestUtils.setField(scheduler, "snapshotLimit", 0);
        ReflectionTestUtils.setField(scheduler, "maxContextLength", 16);
        ReflectionTestUtils.setField(scheduler, "k", 10);
        ReflectionTestUtils.setField(scheduler, "epochs", 3);
        ReflectionTestUtils.setField(scheduler, "hiddenSize", 16);
        ReflectionTestUtils.setField(scheduler, "learningRate", 0.02d);

        when(eventStore.findActiveUserIds(any(), eq(2))).thenReturn(List.of("regular-user"));
        when(trainLogStore.findLatestByUserId("regular-user")).thenReturn(Optional.empty());
        when(trainingService.autoTrainAndPromote(eq("regular-user"), isNull(), isNull(), any()))
            .thenReturn(sampleAutoTrainResult());
        when(eventStore.countEventsByUserIdAfter("regular-user", Instant.EPOCH)).thenReturn(42L);

        scheduler.run();

        verify(trainingService).autoTrainAndPromote(eq("regular-user"), isNull(), isNull(), any());
        verify(trainLogStore).save(argThat(draft ->
            draft.userId().equals("regular-user")
                && draft.eventCountAtTrain() == 42L
                && draft.datasetVersion().equals("recommendation-sequence-v1")
                && draft.datasetFingerprint().equals("sha256:regular")
                && draft.sequenceItemCountAtTrain() == 45L
                && draft.recommendationSnapshotCountAtTrain() == 3L
                && draft.modelVersion().equals("sasrec-mvp-regular")
                && draft.qualified()
                && !draft.promoted()
                && draft.metrics().hitRateAtK().equals(0.5d)
        ));
    }

    @Test
    void shouldRecordSchedulerFailureWhenActiveUserLookupFails() {
        RecommendationModelTrainingService trainingService = mock(RecommendationModelTrainingService.class);
        UserMusicEventStore eventStore = mock(UserMusicEventStore.class);
        SasrecAutoTrainLogStore trainLogStore = mock(SasrecAutoTrainLogStore.class);
        ApplicationErrorLogService errorLogService = mock(ApplicationErrorLogService.class);
        SasrecAutoTrainScheduler scheduler = new SasrecAutoTrainScheduler(
            trainingService,
            eventStore,
            trainLogStore,
            Optional.of(errorLogService)
        );
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "activeWindowHours", 168);
        ReflectionTestUtils.setField(scheduler, "maxActiveUsers", 2);
        when(eventStore.findActiveUserIds(any(), eq(2))).thenThrow(new IllegalStateException("event store down"));

        scheduler.run();

        verify(errorLogService).recordSchedulerFailure(
            eq("sasrec-auto-train"),
            argThat(message -> message.contains("failed to resolve active users")),
            any(IllegalStateException.class),
            isNull()
        );
    }

    private RecommendationModelTrainingService.AutoTrainResult sampleAutoTrainResult() {
        RecommendationModelTrainingResponse training = new RecommendationModelTrainingResponse(
            "sasrec-mvp-training",
            "ok",
            "regular-user",
            new RecommendationModelTrainingResponse.DatasetSummary(
                null,
                null,
                42,
                3,
                45,
                "recommendation-sequence-v1",
                "sha256:regular"
            ),
            "sasrec-mvp-regular",
            Map.of("train_example_count", 12),
            Map.of("hit_rate_at_k", 0.5d, "mrr_at_k", 0.25d, "ndcg_at_k", 0.4d),
            Map.of("hit_rate_at_k", 0.4d, "mrr_at_k", 0.2d, "ndcg_at_k", 0.35d),
            Map.of("hit_rate_at_k", 0.1d, "mrr_at_k", 0.05d, "ndcg_at_k", 0.05d),
            Map.of("qualified", true),
            Map.of("saved", false),
            List.of()
        );
        return new RecommendationModelTrainingService.AutoTrainResult(
            training,
            true,
            null,
            "qualified=true 이지만 artifact 가 저장되지 않아 promote 를 건너뛰었습니다."
        );
    }
}
