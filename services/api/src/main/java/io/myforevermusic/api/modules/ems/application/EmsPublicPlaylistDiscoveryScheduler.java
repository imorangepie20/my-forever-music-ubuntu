package io.myforevermusic.api.modules.ems.application;

import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService.EmsCollectionSearchResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@org.springframework.context.annotation.Profile("!local")
@Component
public class EmsPublicPlaylistDiscoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmsPublicPlaylistDiscoveryScheduler.class);

    private final EmsCollectionService emsCollectionService;
    private final EmsDiscoveryProperties properties;
    private final Optional<ApplicationErrorLogService> errorLogService;
    private final AtomicReference<EmsPublicPlaylistDiscoveryRun> lastRun = new AtomicReference<>();

    public EmsPublicPlaylistDiscoveryScheduler(
        EmsCollectionService emsCollectionService,
        EmsDiscoveryProperties properties,
        Optional<ApplicationErrorLogService> errorLogService
    ) {
        this.emsCollectionService = emsCollectionService;
        this.properties = properties;
        this.errorLogService = errorLogService;
    }

    @Scheduled(
        initialDelayString = "${app.ems.discovery.initial-delay-ms:0}",
        fixedDelayString = "${app.ems.discovery.refresh-interval-ms:86400000}"
    )
    public void collectPublicPlaylists() {
        runDiscovery(
            "scheduled",
            properties.getUserId(),
            properties.getPlatforms(),
            properties.getSeedQueries(),
            properties.getPerQueryLimit()
        );
    }

    public EmsPublicPlaylistDiscoveryRun runNow(
        String userIdOverride,
        List<String> platformOverrides,
        List<String> seedQueryOverrides,
        Integer perQueryLimitOverride
    ) {
        String userId = userIdOverride == null || userIdOverride.isBlank()
            ? properties.getUserId()
            : userIdOverride;
        List<String> platforms = platformOverrides == null || platformOverrides.isEmpty()
            ? properties.getPlatforms()
            : platformOverrides;
        List<String> seedQueries = seedQueryOverrides == null || seedQueryOverrides.isEmpty()
            ? properties.getSeedQueries()
            : seedQueryOverrides;
        int perQueryLimit = perQueryLimitOverride == null
            ? properties.getPerQueryLimit()
            : perQueryLimitOverride;

        return runDiscovery("manual", userId, platforms, seedQueries, perQueryLimit);
    }

    public EmsPublicPlaylistDiscoveryRun lastRun() {
        return lastRun.get();
    }

    private EmsPublicPlaylistDiscoveryRun runDiscovery(
        String trigger,
        String userId,
        List<String> requestedPlatforms,
        List<String> requestedSeedQueries,
        int requestedPerQueryLimit
    ) {
        Instant startedAt = Instant.now();
        if (!properties.isEnabled()) {
            EmsPublicPlaylistDiscoveryRun run = skippedRun(
                trigger,
                startedAt,
                "EMS public playlist discovery is disabled by app.ems.discovery.enabled."
            );
            lastRun.set(run);
            return run;
        }
        // A discovery user is only required for credential-backed sources (Spotify, TIDAL
        // search). Credential-free sources (TIDAL public home pages) still run without one.
        boolean hasDiscoveryUser = userId != null && !userId.isBlank();

        List<String> platforms = cleanValues(requestedPlatforms);
        List<String> seedQueries = cleanValues(requestedSeedQueries);
        if (platforms.isEmpty() || seedQueries.isEmpty()) {
            EmsPublicPlaylistDiscoveryRun run = skippedRun(
                trigger,
                startedAt,
                "EMS public playlist discovery skipped: platforms or seed queries are empty."
            );
            log.info(run.message());
            lastRun.set(run);
            return run;
        }

        int limit = Math.min(Math.max(requestedPerQueryLimit, 1), 10);
        int collectedPlaylists = 0;
        int collectedTracks = 0;
        List<EmsPublicPlaylistDiscoveryFailure> failures = new ArrayList<>();

        int skippedNoUser = 0;
        for (String platformId : platforms) {
            for (String query : seedQueriesForPlatform(seedQueries, platformId)) {
                String sourceId = sourceIdWithoutPlatformPrefix(query);
                if (!hasDiscoveryUser && !emsCollectionService.isCredentialFreeSource(platformId, sourceId)) {
                    skippedNoUser++;
                    log.info(
                        "EMS discovery skipping credential-required seed platform={} query='{}' (app.ems.discovery.user-id not set).",
                        platformId,
                        query
                    );
                    continue;
                }
                try {
                    EmsCollectionSearchResult result = emsCollectionService.collectPublicPlaylistPool(
                        userId,
                        platformId,
                        sourceId,
                        limit
                    );
                    collectedPlaylists += result.collectedPlaylistCount();
                    collectedTracks += result.collectedTrackCount();
                } catch (RuntimeException exception) {
                    failures.add(new EmsPublicPlaylistDiscoveryFailure(platformId, query, exception.getMessage()));
                    log.warn(
                        "EMS public playlist discovery failed for platform={} query='{}': {}",
                        platformId,
                        query,
                        exception.getMessage()
                    );
                    errorLogService.ifPresent(service -> service.recordSchedulerFailure(
                        "ems-public-discovery",
                        "EMS public playlist discovery failed for platform=%s query='%s': %s".formatted(
                            platformId,
                            query,
                            exception.getMessage()
                        ),
                        exception,
                        null
                    ));
                }
            }
        }

        String status = failures.isEmpty() ? "completed" : "completed_with_failures";
        EmsPublicPlaylistDiscoveryRun run = new EmsPublicPlaylistDiscoveryRun(
            trigger,
            status,
            startedAt,
            Instant.now(),
            platforms,
            seedQueries,
            limit,
            collectedPlaylists,
            collectedTracks,
            List.copyOf(failures),
            "EMS public playlist discovery %s.".formatted(status)
        );
        lastRun.set(run);
        log.info(
            "EMS public playlist discovery {}: platforms={} queries={} playlists={} tracks={} failures={} skipped_no_user={}",
            status,
            platforms.size(),
            seedQueries.size(),
            collectedPlaylists,
            collectedTracks,
            failures.size(),
            skippedNoUser
        );
        return run;
    }

    private static List<String> cleanValues(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }

    private static List<String> seedQueriesForPlatform(List<String> seedQueries, String platformId) {
        return seedQueries.stream()
            .filter(query -> platformPrefix(query) == null || platformPrefix(query).equals(platformId))
            .toList();
    }

    private static String sourceIdWithoutPlatformPrefix(String value) {
        String prefix = platformPrefix(value);
        if (prefix == null) {
            return value;
        }
        return value.substring(prefix.length() + 1);
    }

    private static String platformPrefix(String value) {
        if (value == null) {
            return null;
        }
        int separator = value.indexOf(':');
        if (separator <= 0) {
            return null;
        }
        String prefix = value.substring(0, separator);
        return ("tidal".equals(prefix) || "spotify".equals(prefix)) ? prefix : null;
    }

    private static EmsPublicPlaylistDiscoveryRun skippedRun(String trigger, Instant startedAt, String message) {
        return new EmsPublicPlaylistDiscoveryRun(
            trigger,
            "skipped",
            startedAt,
            Instant.now(),
            List.of(),
            List.of(),
            0,
            0,
            0,
            List.of(),
            message
        );
    }

    public record EmsPublicPlaylistDiscoveryRun(
        String trigger,
        String status,
        Instant startedAt,
        Instant completedAt,
        List<String> platforms,
        List<String> seedQueries,
        int perQueryLimit,
        int collectedPlaylistCount,
        int collectedTrackCount,
        List<EmsPublicPlaylistDiscoveryFailure> failures,
        String message
    ) {}

    public record EmsPublicPlaylistDiscoveryFailure(
        String platformId,
        String query,
        String message
    ) {}
}
