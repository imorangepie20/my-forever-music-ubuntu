package io.myforevermusic.api.modules.ems.presentation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionProperties;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionService;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionService.EmsAcquisitionRunCommand;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionService.EmsAcquisitionRunDetailSnapshot;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionService.EmsAcquisitionRunSnapshot;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionService.EmsAcquisitionSeedSnapshot;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionService.EmsAcquisitionSignalSnapshot;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionService.EmsAcquisitionSourcePresetSnapshot;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionService.EmsAcquisitionSourceQualitySnapshot;
import io.myforevermusic.api.modules.ems.application.EmsEditorialSource;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@org.springframework.context.annotation.Profile("!local")
@RestController
@RequestMapping("/api/v1/ems/acquisition")
public class EmsAcquisitionController {

    private final EmsAcquisitionService acquisitionService;

    public EmsAcquisitionController(EmsAcquisitionService acquisitionService) {
        this.acquisitionService = acquisitionService;
    }

    @Operation(summary = "Run EMS editorial acquisition immediately")
    @PostMapping("/run")
    public EmsAcquisitionRunResponse runAcquisition(@RequestBody(required = false) EmsAcquisitionRunRequest request) {
        EmsAcquisitionRunDetailSnapshot snapshot = acquisitionService.runNow(toCommand(request));
        return EmsAcquisitionRunResponse.from("api", snapshot);
    }

    @Operation(summary = "Get the latest EMS editorial acquisition run")
    @GetMapping("/status")
    public EmsAcquisitionRunResponse getStatus() {
        EmsAcquisitionRunDetailSnapshot snapshot = acquisitionService.latestRun();
        if (snapshot == null) {
            return new EmsAcquisitionRunResponse(
                "api",
                "not_run",
                Instant.now(),
                null,
                List.of(),
                List.of()
            );
        }
        return EmsAcquisitionRunResponse.from("api", snapshot);
    }

    @Operation(summary = "List recent EMS editorial acquisition runs")
    @GetMapping("/runs")
    public EmsAcquisitionRunsResponse listRuns() {
        return new EmsAcquisitionRunsResponse(
            "api",
            "ok",
            Instant.now(),
            acquisitionService.listRuns().stream().map(EmsAcquisitionRunItem::from).toList()
        );
    }

    @Operation(summary = "List configured EMS editorial acquisition source presets")
    @GetMapping("/source-presets")
    public EmsAcquisitionSourcePresetsResponse sourcePresets() {
        return new EmsAcquisitionSourcePresetsResponse(
            "api",
            "ok",
            Instant.now(),
            acquisitionService.listSourcePresets().stream()
                .map(EmsAcquisitionSourcePresetItem::from)
                .toList()
        );
    }

    @Operation(summary = "Summarize per-source signal count + average confidence over the recent window")
    @GetMapping("/source-quality")
    public EmsAcquisitionSourceQualityResponse sourceQuality(
        @RequestParam(value = "days", defaultValue = "14") int days
    ) {
        return new EmsAcquisitionSourceQualityResponse(
            "api",
            "ok",
            Instant.now(),
            Math.max(1, Math.min(90, days)),
            acquisitionService.summarizeSourceQuality(days).stream()
                .map(EmsAcquisitionSourceQualityItem::from)
                .toList()
        );
    }

    private static EmsAcquisitionRunCommand toCommand(EmsAcquisitionRunRequest request) {
        if (request == null) {
            return new EmsAcquisitionRunCommand(null, null, null, null, null, null, null);
        }
        return new EmsAcquisitionRunCommand(
            request.userId(),
            request.platforms(),
            request.sourcePreset(),
            request.sources() == null ? null : request.sources().stream().map(EmsAcquisitionController::toSource).toList(),
            request.maxArticlesPerSource(),
            request.maxSignalsPerRun(),
            request.perSeedLimit()
        );
    }

    private static EmsAcquisitionProperties.Source toSource(EmsAcquisitionSourceRequest request) {
        EmsAcquisitionProperties.Source source = new EmsAcquisitionProperties.Source();
        source.setEnabled(true);
        source.setName(request.name());
        source.setType(request.type());
        source.setUrl(request.url());
        source.setWeight(request.weight() == null ? 1.0d : request.weight());
        return source;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsAcquisitionRunRequest(
        String userId,
        List<String> platforms,
        String sourcePreset,
        List<EmsAcquisitionSourceRequest> sources,
        Integer maxArticlesPerSource,
        Integer maxSignalsPerRun,
        Integer perSeedLimit
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsAcquisitionSourceRequest(
        String name,
        String type,
        String url,
        Double weight
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsAcquisitionRunResponse(
        String service,
        String status,
        Instant generatedAt,
        EmsAcquisitionRunItem run,
        List<EmsAcquisitionSignalItem> signals,
        List<EmsAcquisitionSeedItem> seeds
    ) {
        static EmsAcquisitionRunResponse from(String service, EmsAcquisitionRunDetailSnapshot snapshot) {
            return new EmsAcquisitionRunResponse(
                service,
                snapshot.run().status(),
                Instant.now(),
                EmsAcquisitionRunItem.from(snapshot.run()),
                snapshot.signals().stream().map(EmsAcquisitionSignalItem::from).toList(),
                snapshot.seeds().stream().map(EmsAcquisitionSeedItem::from).toList()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsAcquisitionRunsResponse(
        String service,
        String status,
        Instant generatedAt,
        List<EmsAcquisitionRunItem> runs
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsAcquisitionSourcePresetsResponse(
        String service,
        String status,
        Instant generatedAt,
        List<EmsAcquisitionSourcePresetItem> presets
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsAcquisitionSourcePresetItem(
        String id,
        String name,
        String description,
        int sourceCount,
        int maxArticlesPerSource,
        int maxSignalsPerRun,
        int perSeedLimit,
        List<EmsAcquisitionSourceItem> sources
    ) {
        static EmsAcquisitionSourcePresetItem from(EmsAcquisitionSourcePresetSnapshot snapshot) {
            return new EmsAcquisitionSourcePresetItem(
                snapshot.id(),
                snapshot.name(),
                snapshot.description(),
                snapshot.sources().size(),
                snapshot.maxArticlesPerSource(),
                snapshot.maxSignalsPerRun(),
                snapshot.perSeedLimit(),
                snapshot.sources().stream().map(EmsAcquisitionSourceItem::from).toList()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsAcquisitionSourceItem(
        String name,
        String type,
        String url,
        double weight
    ) {
        static EmsAcquisitionSourceItem from(EmsEditorialSource source) {
            return new EmsAcquisitionSourceItem(
                source.name(),
                source.type(),
                source.url(),
                source.weight()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsAcquisitionSourceQualityResponse(
        String service,
        String status,
        Instant generatedAt,
        int lookbackDays,
        List<EmsAcquisitionSourceQualityItem> sources
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsAcquisitionSourceQualityItem(
        String sourceName,
        long signalCount,
        double avgConfidence,
        Instant lastSignalAt
    ) {
        static EmsAcquisitionSourceQualityItem from(EmsAcquisitionSourceQualitySnapshot snapshot) {
            return new EmsAcquisitionSourceQualityItem(
                snapshot.sourceName(),
                snapshot.signalCount(),
                snapshot.avgConfidence(),
                snapshot.lastSignalAt()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsAcquisitionRunItem(
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
        static EmsAcquisitionRunItem from(EmsAcquisitionRunSnapshot snapshot) {
            return new EmsAcquisitionRunItem(
                snapshot.id(),
                snapshot.triggerType(),
                snapshot.requestedByUserId(),
                snapshot.status(),
                snapshot.sourceCount(),
                snapshot.articleCount(),
                snapshot.skippedArticleCount(),
                snapshot.signalCount(),
                snapshot.seedCount(),
                snapshot.skippedSeedCount(),
                snapshot.poolRunCount(),
                snapshot.failedSourceCount(),
                snapshot.failedSeedCount(),
                snapshot.message(),
                snapshot.lastError(),
                snapshot.startedAt(),
                snapshot.completedAt(),
                snapshot.updatedAt()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsAcquisitionSignalItem(
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
        static EmsAcquisitionSignalItem from(EmsAcquisitionSignalSnapshot snapshot) {
            return new EmsAcquisitionSignalItem(
                snapshot.id(),
                snapshot.sourceName(),
                snapshot.sourceUrl(),
                snapshot.articleUrl(),
                snapshot.articleTitle(),
                snapshot.signalType(),
                snapshot.query(),
                snapshot.confidenceScore(),
                snapshot.rationale(),
                snapshot.status(),
                snapshot.createdAt()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsAcquisitionSeedItem(
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
        static EmsAcquisitionSeedItem from(EmsAcquisitionSeedSnapshot snapshot) {
            return new EmsAcquisitionSeedItem(
                snapshot.id(),
                snapshot.signalId(),
                snapshot.platformId(),
                snapshot.query(),
                snapshot.status(),
                snapshot.poolRunId(),
                snapshot.resultPlaylistCount(),
                snapshot.resultTrackCount(),
                snapshot.lastError(),
                snapshot.createdAt(),
                snapshot.updatedAt()
            );
        }
    }
}
