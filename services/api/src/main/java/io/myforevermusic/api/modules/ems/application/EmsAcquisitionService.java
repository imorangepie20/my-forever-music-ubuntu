package io.myforevermusic.api.modules.ems.application;

import io.myforevermusic.api.modules.ems.application.EmsAcquisitionSignalModel.EmsAcquisitionSignal;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionSignalModel.EmsAcquisitionSignalModelRequest;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionSignalModel.EmsAcquisitionSignalModelResponse;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService.EmsCollectionSearchPreviewResult;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsAcquisitionRunEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsAcquisitionRunRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsAcquisitionSeedEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsAcquisitionSeedRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsAcquisitionSignalEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsAcquisitionSignalRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@org.springframework.context.annotation.Profile("!local")
@Service
public class EmsAcquisitionService {

    private static final Logger log = LoggerFactory.getLogger(EmsAcquisitionService.class);

    private final EmsAcquisitionProperties properties;
    private final EmsEditorialSourceClient sourceClient;
    private final EmsAcquisitionSignalModel signalModel;
    private final EmsCollectionService collectionService;
    private final EmsAcquisitionRunRepository runRepository;
    private final EmsAcquisitionSignalRepository signalRepository;
    private final EmsAcquisitionSeedRepository seedRepository;

    public EmsAcquisitionService(
        EmsAcquisitionProperties properties,
        EmsEditorialSourceClient sourceClient,
        EmsAcquisitionSignalModel signalModel,
        EmsCollectionService collectionService,
        EmsAcquisitionRunRepository runRepository,
        EmsAcquisitionSignalRepository signalRepository,
        EmsAcquisitionSeedRepository seedRepository
    ) {
        this.properties = properties;
        this.sourceClient = sourceClient;
        this.signalModel = signalModel;
        this.collectionService = collectionService;
        this.runRepository = runRepository;
        this.signalRepository = signalRepository;
        this.seedRepository = seedRepository;
    }

    public EmsAcquisitionRunDetailSnapshot runScheduled() {
        EmsAcquisitionSourceSelection sourceSelection = resolveSourceSelection(null, properties.getSourcePreset());
        return runAcquisition(
            "scheduled",
            properties.getUserId(),
            properties.getPlatforms(),
            sourceSelection.sources(),
            sourceSelection.maxArticlesPerSource(),
            sourceSelection.maxSignalsPerRun(),
            sourceSelection.perSeedLimit()
        );
    }

    public EmsAcquisitionRunDetailSnapshot runNow(EmsAcquisitionRunCommand command) {
        EmsAcquisitionRunCommand safeCommand = command == null
            ? new EmsAcquisitionRunCommand(null, null, null, null, null, null, null)
            : command;
        EmsAcquisitionSourceSelection sourceSelection = resolveSourceSelection(
            safeCommand.sources(),
            safeCommand.sourcePreset()
        );
        return runAcquisition(
            "manual",
            firstNonBlank(safeCommand.userId(), properties.getUserId()),
            safeCommand.platforms() == null || safeCommand.platforms().isEmpty()
                ? properties.getPlatforms()
                : safeCommand.platforms(),
            sourceSelection.sources(),
            safeCommand.maxArticlesPerSource() == null
                ? sourceSelection.maxArticlesPerSource()
                : safeCommand.maxArticlesPerSource(),
            safeCommand.maxSignalsPerRun() == null
                ? sourceSelection.maxSignalsPerRun()
                : safeCommand.maxSignalsPerRun(),
            safeCommand.perSeedLimit() == null
                ? sourceSelection.perSeedLimit()
                : safeCommand.perSeedLimit()
        );
    }

    public EmsAcquisitionRunDetailSnapshot latestRun() {
        return runRepository.findFirstByOrderByStartedAtDesc()
            .map(this::toDetailSnapshot)
            .orElse(null);
    }

    public List<EmsAcquisitionRunSnapshot> listRuns() {
        return runRepository.findTop20ByOrderByStartedAtDesc()
            .stream()
            .map(EmsAcquisitionService::toRunSnapshot)
            .toList();
    }

    /**
     * 최근 lookbackDays 동안 source별로 적재된 signal 수, 평균 confidence, 마지막 signal 시각을 요약한다.
     * 운영자가 어떤 RSS source가 health한지 한눈에 보고, 산출량이 0인 source를 빠르게 식별할 수 있게 한다.
     */
    public List<EmsAcquisitionSourceQualitySnapshot> summarizeSourceQuality(int lookbackDays) {
        int safeDays = Math.max(1, Math.min(90, lookbackDays));
        Instant since = Instant.now().minus(java.time.Duration.ofDays(safeDays));
        return signalRepository.summarizeSourceQualitySince(since).stream()
            .map(row -> new EmsAcquisitionSourceQualitySnapshot(
                row.getSourceName(),
                row.getSignalCount(),
                row.getAvgConfidence() == null
                    ? 0.0d
                    : Math.round(row.getAvgConfidence() * 10000.0d) / 10000.0d,
                row.getLastSignalAt()
            ))
            .toList();
    }

    public List<EmsAcquisitionSourcePresetSnapshot> listSourcePresets() {
        List<EmsAcquisitionSourcePresetSnapshot> presets = new ArrayList<>();
        List<EmsEditorialSource> configured = configuredSources();
        if (!configured.isEmpty()) {
            presets.add(new EmsAcquisitionSourcePresetSnapshot(
                "configured",
                "Configured sources",
                "app.ems.acquisition.sources",
                clamp(properties.getMaxArticlesPerSource(), 1, 50),
                clamp(properties.getMaxSignalsPerRun(), 1, 200),
                clamp(properties.getPerSeedLimit(), 1, 50),
                configured
            ));
        }
        if (properties.getSourcePresets() != null) {
            for (EmsAcquisitionProperties.SourcePreset preset : properties.getSourcePresets()) {
                if (preset == null || !preset.isEnabled() || !hasText(preset.getId())) {
                    continue;
                }
                List<EmsEditorialSource> sources = cleanSources(sourceProperties(preset.getSources()));
                if (sources.isEmpty()) {
                    continue;
                }
                presets.add(new EmsAcquisitionSourcePresetSnapshot(
                    preset.getId().trim(),
                    firstNonBlank(preset.getName(), preset.getId().trim()),
                    preset.getDescription(),
                    clamp(preset.getMaxArticlesPerSource(), 1, 50),
                    clamp(preset.getMaxSignalsPerRun(), 1, 200),
                    clamp(preset.getPerSeedLimit(), 1, 50),
                    sources
                ));
            }
        }
        return presets;
    }

    private EmsAcquisitionRunDetailSnapshot runAcquisition(
        String trigger,
        String userId,
        List<String> requestedPlatforms,
        List<EmsEditorialSource> requestedSources,
        int requestedMaxArticlesPerSource,
        int requestedMaxSignalsPerRun,
        int requestedPerSeedLimit
    ) {
        Instant startedAt = Instant.now();
        EmsAcquisitionRunEntity run = runRepository.save(new EmsAcquisitionRunEntity(
            truncate(trigger, 50),
            truncate(firstNonBlank(userId, ""), 100),
            startedAt
        ));

        if (!properties.isEnabled()) {
            run.markSkipped("EMS acquisition is disabled by app.ems.acquisition.enabled.", Instant.now());
            runRepository.save(run);
            return toDetailSnapshot(run);
        }
        if (!hasText(userId)) {
            run.markSkipped("EMS acquisition skipped: app.ems.acquisition.user-id is not configured.", Instant.now());
            runRepository.save(run);
            return toDetailSnapshot(run);
        }

        List<String> platforms = cleanValues(requestedPlatforms);
        List<EmsEditorialSource> sources = cleanSources(requestedSources);
        if (platforms.isEmpty() || sources.isEmpty()) {
            run.markSkipped("EMS acquisition skipped: platforms or editorial sources are empty.", Instant.now());
            runRepository.save(run);
            return toDetailSnapshot(run);
        }

        int maxArticlesPerSource = clamp(requestedMaxArticlesPerSource, 1, 50);
        int maxSignalsPerRun = clamp(requestedMaxSignalsPerRun, 1, 200);
        int perSeedLimit = clamp(requestedPerSeedLimit, 1, 50);
        int articleCount = 0;
        int skippedArticleCount = 0;
        int failedSourceCount = 0;
        List<String> failureMessages = new ArrayList<>();
        List<SavedAcquisitionSignal> savedSignals = new ArrayList<>();

        for (EmsEditorialSource source : sources) {
            if (savedSignals.size() >= maxSignalsPerRun) {
                break;
            }
            try {
                List<EmsEditorialArticle> articles = sourceClient.fetch(source, maxArticlesPerSource);
                articleCount += articles.size();
                List<EmsEditorialArticle> freshArticles = articles.stream()
                    .filter(article -> !hasText(article.articleUrl())
                        || !signalRepository.existsByArticleUrl(article.articleUrl().trim()))
                    .toList();
                skippedArticleCount += articles.size() - freshArticles.size();
                if (freshArticles.isEmpty()) {
                    continue;
                }
                EmsAcquisitionSignalModelResponse modelResponse = signalModel.extractSignals(
                    new EmsAcquisitionSignalModelRequest(
                        source.name(),
                        source.url(),
                        source.weight(),
                        freshArticles,
                        maxSignalsPerRun - savedSignals.size()
                    )
                );
                for (EmsAcquisitionSignal signal : modelResponse.signals()) {
                    if (savedSignals.size() >= maxSignalsPerRun) {
                        break;
                    }
                    String query = normalizeRequired(signal.query(), 200);
                    if (!hasText(query)) {
                        continue;
                    }
                    EmsAcquisitionSignalEntity savedSignal = signalRepository.save(new EmsAcquisitionSignalEntity(
                        run,
                        truncate(source.name(), 160),
                        truncate(source.url(), 500),
                        truncate(signal.articleUrl(), 500),
                        truncate(signal.articleTitle(), 300),
                        truncate(firstNonBlank(signal.signalType(), "playlist_query"), 50),
                        query,
                        confidence(signal.confidenceScore(), source.weight()),
                        truncate(signal.rationale(), 500),
                        Instant.now()
                    ));
                    savedSignals.add(new SavedAcquisitionSignal(savedSignal, seedQueries(signal)));
                }
            } catch (RuntimeException exception) {
                failedSourceCount++;
                String message = source.name() + ": " + errorMessage(exception);
                failureMessages.add(message);
                log.warn("EMS acquisition source failed: {}", message);
            }
        }

        if (savedSignals.isEmpty() && failedSourceCount > 0) {
            String error = truncate("EMS acquisition produced no signals. " + failureMessages.get(0), 1000);
            run.updateProgress(sources.size(), articleCount, skippedArticleCount, 0, 0, 0, 0, failedSourceCount, 0, Instant.now());
            run.markFailed(error, Instant.now());
            runRepository.save(run);
            return toDetailSnapshot(run);
        }

        int seedCount = 0;
        int skippedSeedCount = 0;
        int poolRunCount = 0;
        int failedSeedCount = 0;
        Set<String> seenSeeds = new LinkedHashSet<>();

        for (SavedAcquisitionSignal savedSignal : savedSignals) {
            for (String platform : platforms) {
                for (String query : savedSignal.seedQueries()) {
                    String seedKey = platform.toLowerCase(Locale.ROOT) + ":" + query.toLowerCase(Locale.ROOT);
                    if (!seenSeeds.add(seedKey)) {
                        skippedSeedCount++;
                        continue;
                    }
                    if (seedRepository.existsActiveByPlatformIdAndQuery(platform, query)) {
                        skippedSeedCount++;
                        continue;
                    }
                    EmsAcquisitionSeedEntity seed = seedRepository.save(new EmsAcquisitionSeedEntity(
                        run,
                        savedSignal.entity(),
                        truncate(platform, 50),
                        truncate(query, 200),
                        Instant.now()
                    ));
                    seedCount++;
                    try {
                        EmsCollectionSearchPreviewResult result = collectionService.queueAcquisitionSearchPool(
                            userId,
                            platform,
                            query,
                            perSeedLimit
                        );
                        seed.markCompleted(
                            result.poolRunId(),
                            result.resultPlaylistCount(),
                            result.resultTrackCount(),
                            Instant.now()
                        );
                        poolRunCount++;
                    } catch (RuntimeException exception) {
                        failedSeedCount++;
                        seed.markFailed(truncate(errorMessage(exception), 1000), Instant.now());
                        log.warn(
                            "EMS acquisition seed failed for platform={} query='{}': {}",
                            platform,
                            query,
                            errorMessage(exception)
                        );
                    }
                    seedRepository.save(seed);
                }
            }
        }

        Instant completedAt = Instant.now();
        run.updateProgress(
            sources.size(),
            articleCount,
            skippedArticleCount,
            savedSignals.size(),
            seedCount,
            skippedSeedCount,
            poolRunCount,
            failedSourceCount,
            failedSeedCount,
            completedAt
        );
        run.markCompleted(
            "EMS acquisition completed: signals=%d seeds=%d pool_runs=%d skipped_articles=%d skipped_seeds=%d.".formatted(
                savedSignals.size(),
                seedCount,
                poolRunCount,
                skippedArticleCount,
                skippedSeedCount
            ),
            completedAt
        );
        runRepository.save(run);
        return toDetailSnapshot(run);
    }

    private static List<String> seedQueries(EmsAcquisitionSignal signal) {
        List<String> queries = new ArrayList<>();
        String primaryQuery = normalizeRequired(signal.query(), 200);
        if (hasText(primaryQuery)) {
            queries.add(primaryQuery);
        }
        if (signal.queryVariants() != null) {
            for (String variant : signal.queryVariants()) {
                String query = normalizeRequired(variant, 200);
                if (hasText(query)) {
                    queries.add(query);
                }
                if (queries.size() >= 4) {
                    break;
                }
            }
        }
        return queries.stream()
            .filter(EmsAcquisitionService::hasText)
            .map(String::trim)
            .distinct()
            .toList();
    }

    private record SavedAcquisitionSignal(
        EmsAcquisitionSignalEntity entity,
        List<String> seedQueries
    ) {
    }

    private EmsAcquisitionRunDetailSnapshot toDetailSnapshot(EmsAcquisitionRunEntity run) {
        if (run.getId() == null) {
            return new EmsAcquisitionRunDetailSnapshot(toRunSnapshot(run), List.of(), List.of());
        }
        return new EmsAcquisitionRunDetailSnapshot(
            toRunSnapshot(run),
            signalRepository.findTop50ByRunIdOrderByIdAsc(run.getId()).stream()
                .map(EmsAcquisitionService::toSignalSnapshot)
                .toList(),
            seedRepository.findTop100ByRunIdOrderByIdAsc(run.getId()).stream()
                .map(EmsAcquisitionService::toSeedSnapshot)
                .toList()
        );
    }

    private List<EmsEditorialSource> configuredSources() {
        return cleanSources(sourceProperties(properties.getSources()));
    }

    private EmsEditorialSource toSource(EmsAcquisitionProperties.Source source) {
        return new EmsEditorialSource(
            firstNonBlank(source.getName(), source.getUrl()),
            firstNonBlank(source.getType(), "rss"),
            source.getUrl(),
            source.getWeight()
        );
    }

    private List<EmsEditorialSource> cleanSources(List<EmsEditorialSource> sources) {
        if (sources == null) {
            return List.of();
        }
        return sources.stream()
            .filter(source -> source != null && hasText(source.url()))
            .map(source -> new EmsEditorialSource(
                truncate(firstNonBlank(source.name(), source.url()), 160),
                truncate(firstNonBlank(source.type(), "rss"), 50),
                truncate(source.url(), 500),
                source.weight() <= 0.0d ? 1.0d : source.weight()
            ))
            .toList();
    }

    private List<EmsEditorialSource> sourceProperties(List<EmsAcquisitionProperties.Source> sources) {
        if (sources == null) {
            return List.of();
        }
        return sources.stream()
            .filter(source -> source != null && source.isEnabled())
            .map(this::toSource)
            .toList();
    }

    private EmsAcquisitionSourceSelection resolveSourceSelection(
        List<EmsAcquisitionProperties.Source> explicitSources,
        String sourcePreset
    ) {
        if (explicitSources != null && explicitSources.stream().anyMatch(source -> source != null && source.isEnabled())) {
            return new EmsAcquisitionSourceSelection(
                cleanSources(sourceProperties(explicitSources)),
                properties.getMaxArticlesPerSource(),
                properties.getMaxSignalsPerRun(),
                properties.getPerSeedLimit()
            );
        }
        String presetId = normalizeRequired(sourcePreset, 80);
        if (hasText(presetId)) {
            return listSourcePresets().stream()
                .filter(preset -> preset.id().equalsIgnoreCase(presetId))
                .findFirst()
                .map(preset -> new EmsAcquisitionSourceSelection(
                    preset.sources(),
                    preset.maxArticlesPerSource(),
                    preset.maxSignalsPerRun(),
                    preset.perSeedLimit()
                ))
                .orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unknown EMS acquisition source preset: " + presetId
                ));
        }
        return new EmsAcquisitionSourceSelection(
            configuredSources(),
            properties.getMaxArticlesPerSource(),
            properties.getMaxSignalsPerRun(),
            properties.getPerSeedLimit()
        );
    }

    private static List<String> cleanValues(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .filter(EmsAcquisitionService::hasText)
            .map(String::trim)
            .distinct()
            .toList();
    }

    private static EmsAcquisitionRunSnapshot toRunSnapshot(EmsAcquisitionRunEntity run) {
        return new EmsAcquisitionRunSnapshot(
            run.getId(),
            run.getTriggerType(),
            run.getRequestedByUserId(),
            run.getStatus(),
            run.getSourceCount(),
            run.getArticleCount(),
            run.getSkippedArticleCount(),
            run.getSignalCount(),
            run.getSeedCount(),
            run.getSkippedSeedCount(),
            run.getPoolRunCount(),
            run.getFailedSourceCount(),
            run.getFailedSeedCount(),
            run.getMessage(),
            run.getLastError(),
            run.getStartedAt(),
            run.getCompletedAt(),
            run.getUpdatedAt()
        );
    }

    private static EmsAcquisitionSignalSnapshot toSignalSnapshot(EmsAcquisitionSignalEntity signal) {
        return new EmsAcquisitionSignalSnapshot(
            signal.getId(),
            signal.getSourceName(),
            signal.getSourceUrl(),
            signal.getArticleUrl(),
            signal.getArticleTitle(),
            signal.getSignalType(),
            signal.getQuery(),
            signal.getConfidenceScore().doubleValue(),
            signal.getRationale(),
            signal.getStatus(),
            signal.getCreatedAt()
        );
    }

    private static EmsAcquisitionSeedSnapshot toSeedSnapshot(EmsAcquisitionSeedEntity seed) {
        return new EmsAcquisitionSeedSnapshot(
            seed.getId(),
            seed.getSignal() == null ? null : seed.getSignal().getId(),
            seed.getPlatformId(),
            seed.getQuery(),
            seed.getStatus(),
            seed.getEmsPoolIngestRunId(),
            seed.getResultPlaylistCount(),
            seed.getResultTrackCount(),
            seed.getLastError(),
            seed.getCreatedAt(),
            seed.getUpdatedAt()
        );
    }

    private static String errorMessage(RuntimeException exception) {
        if (exception instanceof ResponseStatusException responseStatusException
            && hasText(responseStatusException.getReason())) {
            return responseStatusException.getReason();
        }
        return hasText(exception.getMessage())
            ? exception.getMessage()
            : exception.getClass().getSimpleName();
    }

    private static BigDecimal confidence(double modelConfidence, double sourceWeight) {
        double clamped = Math.min(Math.max(modelConfidence, 0.0d), 1.0d);
        double weighted = Math.min(clamped * Math.max(sourceWeight, 0.1d), 1.0d);
        return BigDecimal.valueOf(weighted).setScale(4, RoundingMode.HALF_UP);
    }

    private static String normalizeRequired(String value, int maxLength) {
        return hasText(value) ? truncate(value.trim(), maxLength) : "";
    }

    private static String firstNonBlank(String first, String second) {
        return hasText(first) ? first : hasText(second) ? second : "";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record EmsAcquisitionRunCommand(
        String userId,
        List<String> platforms,
        String sourcePreset,
        List<EmsAcquisitionProperties.Source> sources,
        Integer maxArticlesPerSource,
        Integer maxSignalsPerRun,
        Integer perSeedLimit
    ) {
    }

    public record EmsAcquisitionRunDetailSnapshot(
        EmsAcquisitionRunSnapshot run,
        List<EmsAcquisitionSignalSnapshot> signals,
        List<EmsAcquisitionSeedSnapshot> seeds
    ) {
    }

    public record EmsAcquisitionRunSnapshot(
        Long id,
        String triggerType,
        String requestedByUserId,
        String status,
        int sourceCount,
        int articleCount,
        int skippedArticleCount,
        int signalCount,
        int seedCount,
        int skippedSeedCount,
        int poolRunCount,
        int failedSourceCount,
        int failedSeedCount,
        String message,
        String lastError,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt
    ) {
    }

    public record EmsAcquisitionSignalSnapshot(
        Long id,
        String sourceName,
        String sourceUrl,
        String articleUrl,
        String articleTitle,
        String signalType,
        String query,
        double confidenceScore,
        String rationale,
        String status,
        Instant createdAt
    ) {
    }

    public record EmsAcquisitionSeedSnapshot(
        Long id,
        Long signalId,
        String platformId,
        String query,
        String status,
        Long poolRunId,
        int resultPlaylistCount,
        int resultTrackCount,
        String lastError,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record EmsAcquisitionSourceQualitySnapshot(
        String sourceName,
        long signalCount,
        double avgConfidence,
        Instant lastSignalAt
    ) {
    }

    public record EmsAcquisitionSourcePresetSnapshot(
        String id,
        String name,
        String description,
        int maxArticlesPerSource,
        int maxSignalsPerRun,
        int perSeedLimit,
        List<EmsEditorialSource> sources
    ) {
    }

    private record EmsAcquisitionSourceSelection(
        List<EmsEditorialSource> sources,
        int maxArticlesPerSource,
        int maxSignalsPerRun,
        int perSeedLimit
    ) {
    }
}
