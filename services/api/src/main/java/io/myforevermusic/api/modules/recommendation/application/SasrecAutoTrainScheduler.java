package io.myforevermusic.api.modules.recommendation.application;

import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService;
import io.myforevermusic.api.modules.recommendation.infrastructure.ai.AiSasrecTrainingClient;
import io.myforevermusic.api.modules.recommendation.presentation.RecommendationModelTrainingResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주기적으로 SASRec MVP 모델을 자동 학습/promote 한다.
 *
 * 기본 disabled. enable 하려면:
 *   app.recommendation.sasrec.auto-train.enabled=true
 *
 * 동작 모드:
 * - app.recommendation.sasrec.auto-train.user-id 가 지정되면 그 user 한 명만 학습 대상이다.
 * - 비어 있으면 최근 active-window-hours(기본 168h=7일) 이내 user_music_event 가 있는
 *   사용자(최대 max-active-users 명)를 자동 추출해 각자 학습한다.
 *
 * Drift 감지:
 * - 시간 기반: fixed-delay-ms 주기로 tick 발생 (기본 24시간)
 * - 이벤트 수 기반: 마지막 학습 이후 새 event 수가 min-event-delta(기본 50) 이상일 때만
 *   학습. 학습 이력이 없는 사용자는 첫 학습이 매번 통과한다.
 *
 * 학습 이력은 {@link SasrecAutoTrainLogStore} 에 영속 저장된다 (local 프로필은 in-memory).
 * 서버 재시작 후에도 직전 학습 이후 새 event 수만큼만 다시 학습 결정한다.
 */
@Component
public class SasrecAutoTrainScheduler {

    private static final Logger log = LoggerFactory.getLogger(SasrecAutoTrainScheduler.class);

    private final RecommendationModelTrainingService trainingService;
    private final UserMusicEventStore eventStore;
    private final SasrecAutoTrainLogStore trainLogStore;
    private final Optional<ApplicationErrorLogService> errorLogService;

    @Value("${app.recommendation.sasrec.auto-train.enabled:false}")
    private boolean enabled;

    @Value("${app.recommendation.sasrec.auto-train.user-id:}")
    private String userId;

    @Value("${app.recommendation.sasrec.auto-train.active-window-hours:168}")
    private int activeWindowHours;

    @Value("${app.recommendation.sasrec.auto-train.max-active-users:10}")
    private int maxActiveUsers;

    @Value("${app.recommendation.sasrec.auto-train.min-event-delta:50}")
    private int minEventDelta;

    @Value("${app.recommendation.sasrec.auto-train.event-limit:0}")
    private int eventLimit;

    @Value("${app.recommendation.sasrec.auto-train.snapshot-limit:0}")
    private int snapshotLimit;

    @Value("${app.recommendation.sasrec.auto-train.max-context-length:32}")
    private int maxContextLength;

    @Value("${app.recommendation.sasrec.auto-train.k:10}")
    private int k;

    @Value("${app.recommendation.sasrec.auto-train.epochs:30}")
    private int epochs;

    @Value("${app.recommendation.sasrec.auto-train.hidden-size:32}")
    private int hiddenSize;

    @Value("${app.recommendation.sasrec.auto-train.learning-rate:0.01}")
    private double learningRate;

    public SasrecAutoTrainScheduler(
        RecommendationModelTrainingService trainingService,
        UserMusicEventStore eventStore,
        SasrecAutoTrainLogStore trainLogStore,
        Optional<ApplicationErrorLogService> errorLogService
    ) {
        this.trainingService = trainingService;
        this.eventStore = eventStore;
        this.trainLogStore = trainLogStore;
        this.errorLogService = errorLogService;
    }

    @Scheduled(
        fixedDelayString = "${app.recommendation.sasrec.auto-train.fixed-delay-ms:86400000}",
        initialDelayString = "${app.recommendation.sasrec.auto-train.initial-delay-ms:600000}"
    )
    public void run() {
        if (!enabled) {
            return;
        }
        Instant now = Instant.now();
        List<String> targets = resolveTargetUserIds(now);
        if (targets.isEmpty()) {
            log.info("SASRec auto-train tick produced 0 target users. Skipping.");
            return;
        }
        AiSasrecTrainingClient.SasrecTrainingOptions options = buildTrainingOptions();
        for (String targetUserId : targets) {
            try {
                if (!shouldTrain(targetUserId)) {
                    continue;
                }
                RecommendationModelTrainingService.AutoTrainResult result = trainingService.autoTrainAndPromote(
                    targetUserId,
                    eventLimit > 0 ? eventLimit : null,
                    snapshotLimit > 0 ? snapshotLimit : null,
                    options
                );
                long eventCountAtTrain = countEventsForUser(targetUserId);
                boolean promoted = result.promoteResult() != null;
                String modelVersion = result.training() == null ? null : result.training().modelVersion();
                RecommendationModelTrainingResponse.DatasetSummary datasetSummary =
                    result.training() == null ? null : result.training().datasetSummary();
                trainLogStore.save(new SasrecAutoTrainLogStore.Draft(
                    targetUserId,
                    now,
                    eventCountAtTrain,
                    datasetSummary == null ? null : datasetSummary.datasetVersion(),
                    datasetSummary == null ? null : datasetSummary.datasetFingerprint(),
                    asLong(datasetSummary == null ? null : datasetSummary.sequenceItemCount()),
                    asLong(datasetSummary == null ? null : datasetSummary.recommendationSnapshotCount()),
                    modelVersion,
                    result.qualified(),
                    promoted,
                    result.summary(),
                    extractMetrics(result.training())
                ));
                log.info(
                    "SASRec auto-train tick user={} qualified={} promoted={} model={} summary={}",
                    targetUserId,
                    result.qualified(),
                    promoted,
                    modelVersion,
                    result.summary()
                );
            } catch (Exception ex) {
                String message = "SASRec auto-train scheduler failed for user=%s: %s".formatted(
                    targetUserId,
                    ex.getMessage()
                );
                log.warn(message);
                recordSchedulerFailure(message, ex);
            }
        }
    }

    private List<String> resolveTargetUserIds(Instant now) {
        if (userId != null && !userId.isBlank()) {
            return List.of(userId);
        }
        Instant since = now.minus(Duration.ofHours(Math.max(1, activeWindowHours)));
        try {
            return eventStore.findActiveUserIds(since, Math.max(1, maxActiveUsers));
        } catch (Exception ex) {
            String message = "SASRec auto-train failed to resolve active users: %s".formatted(ex.getMessage());
            log.warn(message);
            recordSchedulerFailure(message, ex);
            return List.of();
        }
    }

    private boolean shouldTrain(String targetUserId) {
        Optional<SasrecAutoTrainLogStore.Entry> latest;
        try {
            latest = trainLogStore.findLatestByUserId(targetUserId);
        } catch (Exception ex) {
            String message = "SASRec auto-train log lookup failed for user=%s: %s".formatted(
                targetUserId,
                ex.getMessage()
            );
            log.warn(message);
            recordSchedulerFailure(message, ex);
            return false;
        }
        if (latest.isEmpty()) {
            return true;
        }
        Instant trainedAt = latest.get().trainedAt();
        try {
            long delta = eventStore.countEventsByUserIdAfter(targetUserId, trainedAt);
            if (delta >= Math.max(1, minEventDelta)) {
                return true;
            }
            log.info(
                "SASRec auto-train skip user={} (delta={} < threshold={}, last_trained_at={})",
                targetUserId,
                delta,
                minEventDelta,
                trainedAt
            );
            return false;
        } catch (Exception ex) {
            String message = "SASRec auto-train drift check failed for user=%s: %s".formatted(
                targetUserId,
                ex.getMessage()
            );
            log.warn(message);
            recordSchedulerFailure(message, ex);
            return false;
        }
    }

    private long countEventsForUser(String targetUserId) {
        try {
            return eventStore.countEventsByUserIdAfter(targetUserId, Instant.EPOCH);
        } catch (Exception ex) {
            String message = "SASRec auto-train failed to count events for user=%s: %s".formatted(
                targetUserId,
                ex.getMessage()
            );
            log.warn(message);
            recordSchedulerFailure(message, ex);
            return 0L;
        }
    }

    private void recordSchedulerFailure(String message, Throwable exception) {
        errorLogService.ifPresent(service -> service.recordSchedulerFailure(
            "sasrec-auto-train",
            message,
            exception,
            null
        ));
    }

    private SasrecAutoTrainLogStore.MetricSnapshot extractMetrics(RecommendationModelTrainingResponse training) {
        if (training == null) {
            return SasrecAutoTrainLogStore.MetricSnapshot.empty();
        }
        return new SasrecAutoTrainLogStore.MetricSnapshot(
            asDouble(training.metrics(), "hit_rate_at_k"),
            asDouble(training.metrics(), "mrr_at_k"),
            asDouble(training.metrics(), "ndcg_at_k"),
            asDouble(training.baselineMetrics(), "hit_rate_at_k"),
            asDouble(training.baselineMetrics(), "mrr_at_k"),
            asDouble(training.baselineMetrics(), "ndcg_at_k"),
            asDouble(training.metricDelta(), "hit_rate_at_k"),
            asDouble(training.metricDelta(), "mrr_at_k"),
            asDouble(training.metricDelta(), "ndcg_at_k")
        );
    }

    private long asLong(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    private Double asDouble(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private AiSasrecTrainingClient.SasrecTrainingOptions buildTrainingOptions() {
        return new AiSasrecTrainingClient.SasrecTrainingOptions(
            maxContextLength,
            k,
            epochs,
            hiddenSize,
            learningRate,
            true
        );
    }
}
