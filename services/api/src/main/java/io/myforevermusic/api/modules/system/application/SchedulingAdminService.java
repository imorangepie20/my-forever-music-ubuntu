package io.myforevermusic.api.modules.system.application;

import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionService;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionService.EmsAcquisitionRunDetailSnapshot;
import io.myforevermusic.api.modules.ems.application.EmsAcquisitionService.EmsAcquisitionRunSnapshot;
import io.myforevermusic.api.modules.ems.application.EmsLooseTrackPlaylistScheduler;
import io.myforevermusic.api.modules.ems.application.EmsLooseTrackPlaylistScheduler.EmsLooseTrackPlaylistRun;
import io.myforevermusic.api.modules.ems.application.EmsPublicPlaylistDiscoveryScheduler;
import io.myforevermusic.api.modules.ems.application.EmsPublicPlaylistDiscoveryScheduler.EmsPublicPlaylistDiscoveryRun;
import io.myforevermusic.api.modules.ems.application.EmsTidalHomeTrackBackfillScheduler;
import io.myforevermusic.api.modules.ems.application.EmsTidalHomeTrackBackfillScheduler.EmsTidalHomeTrackBackfillStatus;
import io.myforevermusic.api.modules.ems.application.FloSpecialCurationScheduler;
import io.myforevermusic.api.modules.ems.application.FloSpecialCurationScheduler.FloSpecialUpdateRun;
import io.myforevermusic.api.modules.melon.application.MelonChartScraperScheduler;
import io.myforevermusic.api.modules.melon.application.MelonChartScraperScheduler.MelonChartScrapeRun;
import io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionScheduler;
import io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionScheduler.AudioFeatureCompletionRun;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SchedulingAdminService {

    private static final String ADMIN_EMAIL = "jowoosungtidal@gmail.com";
    private static final long ONE_DAY_MS = 86_400_000L;

    private final AuthAccountStore authAccountStore;
    private final Environment environment;
    private final Optional<EmsAcquisitionService> acquisitionService;
    private final Optional<EmsPublicPlaylistDiscoveryScheduler> discoveryScheduler;
    private final Optional<FloSpecialCurationScheduler> floSpecialScheduler;
    private final Optional<EmsLooseTrackPlaylistScheduler> looseTrackPlaylistScheduler;
    private final Optional<MelonChartScraperScheduler> melonChartScraperScheduler;
    private final Optional<AudioFeatureCompletionScheduler> audioFeatureCompletionScheduler;
    private final Optional<EmsTidalHomeTrackBackfillScheduler> tidalHomeTrackBackfillScheduler;

    public SchedulingAdminService(
        AuthAccountStore authAccountStore,
        Environment environment,
        Optional<EmsAcquisitionService> acquisitionService,
        Optional<EmsPublicPlaylistDiscoveryScheduler> discoveryScheduler,
        Optional<FloSpecialCurationScheduler> floSpecialScheduler,
        Optional<EmsLooseTrackPlaylistScheduler> looseTrackPlaylistScheduler,
        Optional<MelonChartScraperScheduler> melonChartScraperScheduler,
        Optional<AudioFeatureCompletionScheduler> audioFeatureCompletionScheduler,
        Optional<EmsTidalHomeTrackBackfillScheduler> tidalHomeTrackBackfillScheduler
    ) {
        this.authAccountStore = authAccountStore;
        this.environment = environment;
        this.acquisitionService = acquisitionService;
        this.discoveryScheduler = discoveryScheduler;
        this.floSpecialScheduler = floSpecialScheduler;
        this.looseTrackPlaylistScheduler = looseTrackPlaylistScheduler;
        this.melonChartScraperScheduler = melonChartScraperScheduler;
        this.audioFeatureCompletionScheduler = audioFeatureCompletionScheduler;
        this.tidalHomeTrackBackfillScheduler = tidalHomeTrackBackfillScheduler;
    }

    public SchedulingAdminReport summarize(String adminUserId) {
        assertAdmin(adminUserId);
        List<ScheduledServiceStatus> schedules = List.of(
            emsAcquisition(),
            emsPublicDiscovery(),
            emsFloSpecial(),
            emsLooseTrackPlaylists(),
            emsPoolWorker(),
            melonHot100Scrape(),
            sasrecAutoTrain(),
            audioFeatureCompletion(),
            metadataApplyAcceptedIsrcs()
        );
        String status = schedules.stream().anyMatch(schedule -> "blocked".equals(schedule.status()))
            ? "attention"
            : "ok";
        return new SchedulingAdminReport(
            status,
            Instant.now(),
            schedules,
            List.of(
                "EMS playlist curation is computed at read time; daily updates should refresh acquisition and discovery data.",
                "FLO Special updates run daily by default and do not require a user credential.",
                "Loose EMS tracks are materialized into synthetic playlists daily once enough tracks accumulate.",
                "EMS pool ingest worker should stay near real-time because it only drains queued searches.",
                "Melon Hot 100 refresh is operator-controlled and can run manually or by opt-in schedule.",
                "Audio feature completion, SASRec, and metadata schedulers remain opt-in until their admin properties are configured."
            ),
            tidalHomeTrackBackfillScheduler
                .map(EmsTidalHomeTrackBackfillScheduler::status)
                .orElse(null)
        );
    }

    private ScheduledServiceStatus emsAcquisition() {
        boolean enabled = booleanProperty("app.ems.acquisition.enabled", true);
        boolean configured = hasText(stringProperty("app.ems.acquisition.user-id", ""));
        long fixedDelayMs = longProperty("app.ems.acquisition.refresh-interval-ms", ONE_DAY_MS);
        long initialDelayMs = longProperty("app.ems.acquisition.initial-delay-ms", 60_000L);
        EmsAcquisitionRunSnapshot latestRun = acquisitionService
            .map(EmsAcquisitionService::latestRun)
            .map(EmsAcquisitionRunDetailSnapshot::run)
            .orElse(null);

        List<String> notes = new ArrayList<>();
        notes.add("Default cadence is daily for editorial RSS and model-derived seed expansion.");
        if (!configured) {
            notes.add("Set app.ems.acquisition.user-id or EMS_ACQUISITION_USER_ID before enabling scheduled runs.");
        }
        return new ScheduledServiceStatus(
            "ems-acquisition",
            "EMS",
            "EMS Acquisition",
            "scheduled",
            enabled,
            configured,
            scheduleStatus(enabled, configured),
            fixedDelayMs,
            initialDelayMs,
            cadenceLabel(fixedDelayMs),
            "Collect editorial signals and enqueue EMS pool searches.",
            "/ems/acquisition-admin",
            latestRun == null ? null : latestRun.status(),
            latestRun == null ? null : firstNonBlank(latestRun.message(), latestRun.lastError()),
            latestRun == null ? null : latestRun.startedAt(),
            latestRun == null ? null : latestRun.completedAt(),
            List.of(
                "app.ems.acquisition.enabled",
                "app.ems.acquisition.user-id",
                "app.ems.acquisition.refresh-interval-ms",
                "app.ems.acquisition.initial-delay-ms",
                "app.ems.acquisition.source-preset"
            ),
            notes
        );
    }

    private ScheduledServiceStatus emsPublicDiscovery() {
        boolean enabled = booleanProperty("app.ems.discovery.enabled", true);
        boolean configured = hasText(stringProperty("app.ems.discovery.user-id", ""));
        long fixedDelayMs = longProperty("app.ems.discovery.refresh-interval-ms", ONE_DAY_MS);
        long initialDelayMs = longProperty("app.ems.discovery.initial-delay-ms", 0L);
        EmsPublicPlaylistDiscoveryRun lastRun = discoveryScheduler
            .map(EmsPublicPlaylistDiscoveryScheduler::lastRun)
            .orElse(null);

        List<String> notes = new ArrayList<>();
        notes.add("Default cadence is daily for public playlist discovery source presets.");
        if (!configured) {
            notes.add("Set app.ems.discovery.user-id or EMS_DISCOVERY_USER_ID before enabling scheduled runs.");
        }
        return new ScheduledServiceStatus(
            "ems-public-discovery",
            "EMS",
            "EMS Public Discovery",
            "scheduled",
            enabled,
            configured,
            scheduleStatus(enabled, configured),
            fixedDelayMs,
            initialDelayMs,
            cadenceLabel(fixedDelayMs),
            "Collect public playlists from platform seed sources.",
            "/ems/acquisition-admin",
            lastRun == null ? null : lastRun.status(),
            lastRun == null ? null : lastRun.message(),
            lastRun == null ? null : lastRun.startedAt(),
            lastRun == null ? null : lastRun.completedAt(),
            List.of(
                "app.ems.discovery.enabled",
                "app.ems.discovery.user-id",
                "app.ems.discovery.refresh-interval-ms",
                "app.ems.discovery.initial-delay-ms",
                "app.ems.discovery.seed-queries"
            ),
            notes
        );
    }

    private ScheduledServiceStatus emsFloSpecial() {
        boolean enabled = booleanProperty("app.ems.flo-special.enabled", true);
        long fixedDelayMs = longProperty("app.ems.flo-special.refresh-interval-ms", ONE_DAY_MS);
        long initialDelayMs = longProperty("app.ems.flo-special.initial-delay-ms", 120_000L);
        FloSpecialUpdateRun lastRun = floSpecialScheduler
            .map(FloSpecialCurationScheduler::lastRun)
            .orElse(null);

        return new ScheduledServiceStatus(
            "ems-flo-special",
            "EMS",
            "EMS FLO Special",
            "scheduled",
            enabled,
            true,
            enabled ? "active" : "disabled",
            fixedDelayMs,
            initialDelayMs,
            cadenceLabel(fixedDelayMs),
            "Visit FLO special curations and persist playlist topics, playlists, and tracks into EMS.",
            "/ems",
            lastRun == null ? null : lastRun.status(),
            lastRun == null ? null : lastRun.message(),
            lastRun == null ? null : lastRun.startedAt(),
            lastRun == null ? null : lastRun.completedAt(),
            List.of(
                "app.ems.flo-special.enabled",
                "app.ems.flo-special.refresh-interval-ms",
                "app.ems.flo-special.initial-delay-ms",
                "app.ems.flo-special.display-limit"
            ),
            List.of(
                "Default cadence is daily.",
                "FLO playlists are stored as EMS source_platform=flo and playback resolves at play time through the user's selected provider."
            )
        );
    }

    private ScheduledServiceStatus emsLooseTrackPlaylists() {
        boolean enabled = booleanProperty("app.ems.loose-track-playlists.enabled", true);
        long fixedDelayMs = longProperty("app.ems.loose-track-playlists.refresh-interval-ms", ONE_DAY_MS);
        long initialDelayMs = longProperty("app.ems.loose-track-playlists.initial-delay-ms", 300_000L);
        long minTrackCount = longProperty("app.ems.loose-track-playlists.min-track-count", 40L);
        EmsLooseTrackPlaylistRun lastRun = looseTrackPlaylistScheduler
            .map(EmsLooseTrackPlaylistScheduler::lastRun)
            .orElse(null);

        return new ScheduledServiceStatus(
            "ems-loose-track-playlists",
            "EMS",
            "EMS Loose Track Playlists",
            "scheduled",
            enabled,
            true,
            enabled ? "active" : "disabled",
            fixedDelayMs,
            initialDelayMs,
            cadenceLabel(fixedDelayMs),
            "Materialize EMS tracks without source playlists into synthetic recommendation playlists.",
            "/ems",
            lastRun == null ? null : lastRun.status(),
            lastRun == null ? null : lastRun.message(),
            lastRun == null ? null : lastRun.startedAt(),
            lastRun == null ? null : lastRun.completedAt(),
            List.of(
                "app.ems.loose-track-playlists.enabled",
                "app.ems.loose-track-playlists.refresh-interval-ms",
                "app.ems.loose-track-playlists.initial-delay-ms",
                "app.ems.loose-track-playlists.track-limit",
                "app.ems.loose-track-playlists.tracks-per-playlist",
                "app.ems.loose-track-playlists.min-track-count"
            ),
            List.of(
                "Default cadence is daily.",
                "Runs only when at least %d unassigned EMS track(s) exist.".formatted(minTrackCount)
            )
        );
    }

    private ScheduledServiceStatus emsPoolWorker() {
        long fixedDelayMs = longProperty("app.ems.pool.worker.fixed-delay-ms", 10_000L);
        return new ScheduledServiceStatus(
            "ems-pool-worker",
            "EMS",
            "EMS Pool Worker",
            "worker",
            true,
            true,
            "active",
            fixedDelayMs,
            null,
            cadenceLabel(fixedDelayMs),
            "Process queued EMS pool ingest runs.",
            "/ems/pool-admin",
            null,
            null,
            null,
            null,
            List.of("app.ems.pool.worker.fixed-delay-ms"),
            List.of("This worker should remain short-delay because acquisition and discovery only enqueue work.")
        );
    }

    private ScheduledServiceStatus melonHot100Scrape() {
        boolean enabled = booleanProperty("app.melon.scrape.enabled", false);
        long fixedDelayMs = longProperty("app.melon.scrape.fixed-delay-ms", ONE_DAY_MS);
        long initialDelayMs = longProperty("app.melon.scrape.initial-delay-ms", 300_000L);
        MelonChartScrapeRun lastRun = melonChartScraperScheduler
            .map(MelonChartScraperScheduler::lastRun)
            .orElse(null);

        return new ScheduledServiceStatus(
            "melon-hot-100-scrape",
            "Content",
            "Melon Hot 100",
            "scheduled",
            enabled,
            true,
            enabled ? "active" : "disabled",
            fixedDelayMs,
            initialDelayMs,
            cadenceLabel(fixedDelayMs),
            "Refresh the stored Melon Hot 100 chart and materialize it into EMS.",
            "/admin/schedules",
            lastRun == null ? null : lastRun.status(),
            lastRun == null ? null : lastRun.message(),
            lastRun == null ? null : lastRun.startedAt(),
            lastRun == null ? null : lastRun.completedAt(),
            List.of(
                "app.melon.scrape.enabled",
                "app.melon.scrape.fixed-delay-ms",
                "app.melon.scrape.initial-delay-ms"
            ),
            List.of(
                "Default disabled; enable MELON_SCRAPE_ENABLED=true for scheduled refresh.",
                "Manual refresh is available from this admin screen only."
            )
        );
    }

    private ScheduledServiceStatus sasrecAutoTrain() {
        boolean enabled = booleanProperty("app.recommendation.sasrec.auto-train.enabled", false);
        long fixedDelayMs = longProperty("app.recommendation.sasrec.auto-train.fixed-delay-ms", ONE_DAY_MS);
        long initialDelayMs = longProperty("app.recommendation.sasrec.auto-train.initial-delay-ms", 600_000L);
        boolean targetUserConfigured = hasText(stringProperty("app.recommendation.sasrec.auto-train.user-id", ""));
        return new ScheduledServiceStatus(
            "sasrec-auto-train",
            "Recommendation",
            "SASRec Auto Train",
            "scheduled",
            enabled,
            true,
            enabled ? "active" : "disabled",
            fixedDelayMs,
            initialDelayMs,
            cadenceLabel(fixedDelayMs),
            "Train and promote SASRec models when enough new user events arrive.",
            "/recommendations/sasrec-admin",
            null,
            null,
            null,
            null,
            List.of(
                "app.recommendation.sasrec.auto-train.enabled",
                "app.recommendation.sasrec.auto-train.user-id",
                "app.recommendation.sasrec.auto-train.fixed-delay-ms",
                "app.recommendation.sasrec.auto-train.min-event-delta"
            ),
            targetUserConfigured
                ? List.of("Runs for the configured target user.")
                : List.of("No target user is pinned; active users are resolved from recent music events.")
        );
    }

    private ScheduledServiceStatus audioFeatureCompletion() {
        boolean enabled = booleanProperty("app.audio-features.completion.scheduler.enabled", false);
        long fixedDelayMs = longProperty("app.audio-features.completion.scheduler.fixed-delay-ms", 300_000L);
        long initialDelayMs = longProperty("app.audio-features.completion.scheduler.initial-delay-ms", 60_000L);
        AudioFeatureCompletionRun lastRun = audioFeatureCompletionScheduler
            .map(AudioFeatureCompletionScheduler::lastRun)
            .orElse(null);
        return new ScheduledServiceStatus(
            "audio-feature-completion",
            "Recommendation",
            "Audio Feature Completion",
            "scheduled",
            enabled,
            true,
            enabled ? "active" : "disabled",
            fixedDelayMs,
            initialDelayMs,
            cadenceLabel(fixedDelayMs),
            "Process queued PMS/EMS tracks with missing provider-neutral audio features.",
            "/recommendations/feature-coverage",
            lastRun == null ? null : lastRun.status(),
            lastRun == null ? null : lastRun.message(),
            lastRun == null ? null : lastRun.startedAt(),
            lastRun == null ? null : lastRun.completedAt(),
            List.of(
                "app.audio-features.completion.scheduler.enabled",
                "app.audio-features.completion.scheduler.fixed-delay-ms",
                "app.audio-features.completion.scheduler.initial-delay-ms",
                "app.audio-features.completion.scheduler.batch-limit",
                "app.audio-features.completion.scheduler.worker-id"
            ),
            List.of(
                "Default disabled until database profile and ReccoBeats credentials are ready.",
                "Jobs are enqueued automatically from PMS import and EMS collection when audio features are incomplete."
            )
        );
    }

    private ScheduledServiceStatus metadataApplyAcceptedIsrcs() {
        boolean enabled = booleanProperty("app.recommendation.metadata.apply-accepted-isrcs.enabled", false);
        boolean configured = !enabled || hasText(stringProperty(
            "app.recommendation.metadata.apply-accepted-isrcs.admin-user-id",
            ""
        ));
        long fixedDelayMs = longProperty("app.recommendation.metadata.apply-accepted-isrcs.fixed-delay-ms", 3_600_000L);
        long initialDelayMs = longProperty("app.recommendation.metadata.apply-accepted-isrcs.initial-delay-ms", 300_000L);
        List<String> notes = new ArrayList<>();
        notes.add("Only accepted ISRC candidates are applied.");
        if (enabled && !configured) {
            notes.add("Set app.recommendation.metadata.apply-accepted-isrcs.admin-user-id before enabling.");
        }
        return new ScheduledServiceStatus(
            "metadata-apply-accepted-isrcs",
            "Recommendation",
            "Metadata Accepted ISRC Apply",
            "scheduled",
            enabled,
            configured,
            scheduleStatus(enabled, configured),
            fixedDelayMs,
            initialDelayMs,
            cadenceLabel(fixedDelayMs),
            "Apply accepted metadata-normalization ISRC candidates to EMS tracks.",
            "/recommendations/metadata-admin",
            null,
            null,
            null,
            null,
            List.of(
                "app.recommendation.metadata.apply-accepted-isrcs.enabled",
                "app.recommendation.metadata.apply-accepted-isrcs.admin-user-id",
                "app.recommendation.metadata.apply-accepted-isrcs.fixed-delay-ms",
                "app.recommendation.metadata.apply-accepted-isrcs.batch-limit"
            ),
            notes
        );
    }

    private void assertAdmin(String userId) {
        String normalizedEmail = authAccountStore.findByUserId(userId)
            .map(account -> account.normalizedEmail())
            .orElse("");
        if (!ADMIN_EMAIL.equals(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Scheduling admin access is restricted.");
        }
    }

    private String scheduleStatus(boolean enabled, boolean configured) {
        if (!enabled) {
            return "disabled";
        }
        return configured ? "active" : "blocked";
    }

    private String cadenceLabel(Long fixedDelayMs) {
        if (fixedDelayMs == null) {
            return "manual";
        }
        if (fixedDelayMs % ONE_DAY_MS == 0L) {
            long days = fixedDelayMs / ONE_DAY_MS;
            return days == 1L ? "daily" : "every %d days".formatted(days);
        }
        if (fixedDelayMs % 3_600_000L == 0L) {
            long hours = fixedDelayMs / 3_600_000L;
            return hours == 1L ? "hourly" : "every %d hours".formatted(hours);
        }
        if (fixedDelayMs % 60_000L == 0L) {
            long minutes = fixedDelayMs / 60_000L;
            return minutes == 1L ? "every minute" : "every %d minutes".formatted(minutes);
        }
        if (fixedDelayMs % 1_000L == 0L) {
            long seconds = fixedDelayMs / 1_000L;
            return "every %d seconds".formatted(seconds);
        }
        return "every %d ms".formatted(fixedDelayMs);
    }

    private boolean booleanProperty(String key, boolean defaultValue) {
        return environment.getProperty(key, Boolean.class, defaultValue);
    }

    private long longProperty(String key, long defaultValue) {
        return environment.getProperty(key, Long.class, defaultValue);
    }

    private String stringProperty(String key, String defaultValue) {
        return environment.getProperty(key, defaultValue);
    }

    private static String firstNonBlank(String first, String second) {
        return hasText(first) ? first : second;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record SchedulingAdminReport(
        String status,
        Instant generatedAt,
        List<ScheduledServiceStatus> schedules,
        List<String> recommendations,
        EmsTidalHomeTrackBackfillStatus tidalHomeBackfill
    ) {}

    public record ScheduledServiceStatus(
        String id,
        String domain,
        String name,
        String mode,
        boolean enabled,
        boolean configured,
        String status,
        Long fixedDelayMs,
        Long initialDelayMs,
        String cadenceLabel,
        String purpose,
        String managementPath,
        String lastStatus,
        String lastMessage,
        Instant lastStartedAt,
        Instant lastCompletedAt,
        List<String> configKeys,
        List<String> notes
    ) {}
}
