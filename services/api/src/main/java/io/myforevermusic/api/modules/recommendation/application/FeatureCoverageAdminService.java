package io.myforevermusic.api.modules.recommendation.application;

import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsAcquisitionRunEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsAcquisitionRunRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackRepository;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore.LibraryPlaylistState;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore.LibraryTrackState;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FeatureCoverageAdminService {

    private static final String ADMIN_EMAIL = "jowoosungtidal@gmail.com";
    private static final int RECENT_SNAPSHOT_LIMIT = 1000;

    private final AuthAccountStore authAccountStore;
    private final PmsUserLibraryStore pmsUserLibraryStore;
    private final UserMusicEventStore eventStore;
    private final RecommendationSnapshotStore snapshotStore;
    private final Optional<EmsCollectedTrackRepository> emsTrackRepository;
    private final Optional<EmsAcquisitionRunRepository> emsAcquisitionRunRepository;
    private final Optional<AudioFeatureCompletionJobStore> audioFeatureCompletionJobStore;
    private final DriftSignalEvaluator driftSignalEvaluator;

    @Value("${app.recommendation.drift.audio-stale-days:90}")
    private long audioStaleDays = 90L;

    public FeatureCoverageAdminService(
        AuthAccountStore authAccountStore,
        PmsUserLibraryStore pmsUserLibraryStore,
        UserMusicEventStore eventStore,
        RecommendationSnapshotStore snapshotStore,
        Optional<EmsCollectedTrackRepository> emsTrackRepository,
        Optional<EmsAcquisitionRunRepository> emsAcquisitionRunRepository,
        Optional<AudioFeatureCompletionJobStore> audioFeatureCompletionJobStore,
        DriftSignalEvaluator driftSignalEvaluator
    ) {
        this.authAccountStore = authAccountStore;
        this.pmsUserLibraryStore = pmsUserLibraryStore;
        this.eventStore = eventStore;
        this.snapshotStore = snapshotStore;
        this.emsTrackRepository = emsTrackRepository;
        this.emsAcquisitionRunRepository = emsAcquisitionRunRepository;
        this.audioFeatureCompletionJobStore = audioFeatureCompletionJobStore;
        this.driftSignalEvaluator = driftSignalEvaluator;
    }

    public FeatureCoverageReport summarize(String adminUserId, String targetUserId) {
        assertAdmin(adminUserId);
        String resolvedTargetUserId = targetUserId == null || targetUserId.isBlank()
            ? adminUserId
            : targetUserId.trim();

        Instant generatedAt = Instant.now();
        Instant staleCutoff = generatedAt.minus(Math.max(1L, audioStaleDays), ChronoUnit.DAYS);
        PmsLibraryCoverage pmsCoverage = summarizePmsLibrary(resolvedTargetUserId, staleCutoff);
        EmsPoolCoverage emsCoverage = summarizeEmsPool(staleCutoff);
        EmsAcquisitionCoverage acquisitionCoverage = summarizeAcquisition();
        AudioFeatureCompletionCoverage audioFeatureCompletionCoverage = summarizeAudioFeatureCompletion();
        LearningDataCoverage learningCoverage = new LearningDataCoverage(
            eventStore.countEventsByUserIdAfter(resolvedTargetUserId, Instant.EPOCH),
            snapshotStore.findRecentByUserId(resolvedTargetUserId, RECENT_SNAPSHOT_LIMIT).size(),
            RECENT_SNAPSHOT_LIMIT
        );

        List<String> warnings = new ArrayList<>();
        warnings.addAll(emsCoverage.warnings());
        warnings.addAll(acquisitionCoverage.warnings());
        warnings.addAll(audioFeatureCompletionCoverage.warnings());

        FeatureCoverageReport draft = new FeatureCoverageReport(
            resolvedTargetUserId,
            generatedAt,
            warnings.isEmpty() ? "ok" : "degraded",
            pmsCoverage,
            emsCoverage,
            acquisitionCoverage,
            audioFeatureCompletionCoverage,
            learningCoverage,
            warnings,
            List.of()
        );
        List<DriftSignalEvaluator.DriftSignal> driftSignals = driftSignalEvaluator.evaluate(draft);
        String status = warnings.isEmpty() && driftSignals.isEmpty() ? "ok" : "degraded";
        return new FeatureCoverageReport(
            draft.targetUserId(),
            draft.generatedAt(),
            status,
            draft.pmsLibrary(),
            draft.emsPool(),
            draft.emsAcquisition(),
            draft.audioFeatureCompletion(),
            draft.learningData(),
            draft.warnings(),
            driftSignals
        );
    }

    private PmsLibraryCoverage summarizePmsLibrary(String userId, Instant staleCutoff) {
        List<LibraryPlaylistState> playlists = pmsUserLibraryStore.findPlaylists(userId);
        long trackCount = 0L;
        long audioFeatureFilledCount = 0L;
        long staleAudioFeatureCount = 0L;
        long isrcCount = 0L;
        long playbackTargetAvailableCount = 0L;
        Instant latestAudioResolvedAt = null;
        Map<String, AudioFeatureSourceClassAccumulator> sourceClasses = new LinkedHashMap<>();

        for (LibraryPlaylistState playlist : playlists) {
            if (playlist.tracks() == null) {
                continue;
            }
            for (LibraryTrackState track : playlist.tracks()) {
                if (track == null) {
                    continue;
                }
                trackCount++;
                String sourceClass = audioFeatureSourceClass(
                    track.audioFeatures() == null ? null : track.audioFeatures().getAudioFeatureSource()
                );
                boolean audioFeaturesComplete = track.audioFeatures() != null && track.audioFeatures().isComplete();
                Instant resolvedAt = audioFeaturesComplete ? track.audioFeatures().getResolvedAt() : null;
                addSourceClassCoverage(
                    sourceClasses,
                    sourceClass,
                    1L,
                    audioFeaturesComplete ? 1L : 0L,
                    resolvedAt,
                    staleCutoff
                );
                if (audioFeaturesComplete) {
                    audioFeatureFilledCount++;
                    if (resolvedAt != null && resolvedAt.isBefore(staleCutoff)) {
                        staleAudioFeatureCount++;
                    }
                    latestAudioResolvedAt = latest(latestAudioResolvedAt, resolvedAt);
                }
                if (hasText(track.isrc())) {
                    isrcCount++;
                }
                if (isPlaybackTargetAvailable(track)) {
                    playbackTargetAvailableCount++;
                }
            }
        }

        return new PmsLibraryCoverage(
            playlists.size(),
            trackCount,
            audioFeatureFilledCount,
            ratio(audioFeatureFilledCount, trackCount),
            staleAudioFeatureCount,
            ratio(staleAudioFeatureCount, audioFeatureFilledCount),
            latestAudioResolvedAt,
            toSourceClassCoverage(sourceClasses),
            isrcCount,
            ratio(isrcCount, trackCount),
            playbackTargetAvailableCount,
            ratio(playbackTargetAvailableCount, trackCount)
        );
    }

    private EmsPoolCoverage summarizeEmsPool(Instant staleCutoff) {
        if (emsTrackRepository.isEmpty()) {
            return new EmsPoolCoverage(0L, 0L, 0.0d, 0L, 0.0d, null, List.of(), 0L, 0.0d, 0L, 0.0d, List.of(), List.of(
                "EMS coverage is unavailable because the collected track repository is not configured in this profile."
            ));
        }

        List<EmsSourceCoverage> sources = emsTrackRepository.get().summarizeFeatureCoverageBySourcePlatform(staleCutoff).stream()
            .map(row -> {
                long trackCount = value(row.getTrackCount());
                long audioFeatureFilledCount = value(row.getAudioFeatureFilledCount());
                long staleAudioFeatureCount = value(row.getStaleAudioFeatureCount());
                long isrcCount = value(row.getIsrcCount());
                long canonicalTrackCount = value(row.getCanonicalTrackCount());
                return new EmsSourceCoverage(
                    hasText(row.getSourcePlatform()) ? row.getSourcePlatform() : "unknown",
                    trackCount,
                    audioFeatureFilledCount,
                    ratio(audioFeatureFilledCount, trackCount),
                    staleAudioFeatureCount,
                    ratio(staleAudioFeatureCount, audioFeatureFilledCount),
                    row.getLatestAudioResolvedAt(),
                    isrcCount,
                    ratio(isrcCount, trackCount),
                    canonicalTrackCount,
                    ratio(canonicalTrackCount, trackCount)
                );
            })
            .toList();

        long trackCount = sources.stream().mapToLong(EmsSourceCoverage::trackCount).sum();
        long audioFeatureFilledCount = sources.stream().mapToLong(EmsSourceCoverage::audioFeatureFilledCount).sum();
        long staleAudioFeatureCount = sources.stream().mapToLong(EmsSourceCoverage::staleAudioFeatureCount).sum();
        Instant latestAudioResolvedAt = sources.stream()
            .map(EmsSourceCoverage::latestAudioResolvedAt)
            .filter(value -> value != null)
            .max(Comparator.naturalOrder())
            .orElse(null);
        long isrcCount = sources.stream().mapToLong(EmsSourceCoverage::isrcCount).sum();
        long canonicalTrackCount = sources.stream().mapToLong(EmsSourceCoverage::canonicalTrackCount).sum();
        List<AudioFeatureSourceClassCoverage> sourceClasses = summarizeEmsAudioFeatureSourceClasses(staleCutoff);

        return new EmsPoolCoverage(
            trackCount,
            audioFeatureFilledCount,
            ratio(audioFeatureFilledCount, trackCount),
            staleAudioFeatureCount,
            ratio(staleAudioFeatureCount, audioFeatureFilledCount),
            latestAudioResolvedAt,
            sourceClasses,
            isrcCount,
            ratio(isrcCount, trackCount),
            canonicalTrackCount,
            ratio(canonicalTrackCount, trackCount),
            sources,
            List.of()
        );
    }

    private List<AudioFeatureSourceClassCoverage> summarizeEmsAudioFeatureSourceClasses(Instant staleCutoff) {
        Map<String, AudioFeatureSourceClassAccumulator> sourceClasses = new LinkedHashMap<>();
        emsTrackRepository.get().summarizeFeatureCoverageByAudioFeatureSource(staleCutoff).forEach(row ->
            addSourceClassCoverage(
                sourceClasses,
                audioFeatureSourceClass(row.getAudioFeatureSource()),
                value(row.getTrackCount()),
                value(row.getAudioFeatureFilledCount()),
                row.getLatestAudioResolvedAt(),
                value(row.getStaleAudioFeatureCount())
            )
        );
        return toSourceClassCoverage(sourceClasses);
    }

    private EmsAcquisitionCoverage summarizeAcquisition() {
        if (emsAcquisitionRunRepository.isEmpty()) {
            return new EmsAcquisitionCoverage(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0.0d, List.of(
                "EMS acquisition coverage is unavailable because the acquisition run repository is not configured in this profile."
            ));
        }

        List<EmsAcquisitionRunEntity> runs = emsAcquisitionRunRepository.get().findTop20ByOrderByStartedAtDesc();
        long articleCount = runs.stream().mapToLong(EmsAcquisitionRunEntity::getArticleCount).sum();
        long skippedArticleCount = runs.stream().mapToLong(EmsAcquisitionRunEntity::getSkippedArticleCount).sum();
        long seedCount = runs.stream().mapToLong(EmsAcquisitionRunEntity::getSeedCount).sum();
        long skippedSeedCount = runs.stream().mapToLong(EmsAcquisitionRunEntity::getSkippedSeedCount).sum();
        long checkedItemCount = articleCount + seedCount + skippedSeedCount;
        long skippedItemCount = skippedArticleCount + skippedSeedCount;

        return new EmsAcquisitionCoverage(
            runs.size(),
            articleCount,
            skippedArticleCount,
            seedCount,
            skippedSeedCount,
            checkedItemCount,
            skippedItemCount,
            ratio(skippedItemCount, checkedItemCount),
            List.of()
        );
    }

    private AudioFeatureCompletionCoverage summarizeAudioFeatureCompletion() {
        if (audioFeatureCompletionJobStore.isEmpty()) {
            return new AudioFeatureCompletionCoverage(0L, List.of(), List.of(), List.of(
                "Audio feature completion queue is unavailable because the job store is not configured in this profile."
            ));
        }

        List<AudioFeatureCompletionJobStore.StoredJob> jobs = audioFeatureCompletionJobStore.get().findRecent(null, 500);
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        Map<String, Long> reasonCounts = new LinkedHashMap<>();
        for (AudioFeatureCompletionJobStore.StoredJob job : jobs) {
            if (job == null) {
                continue;
            }
            String status = hasText(job.status()) ? job.status() : "unknown";
            statusCounts.merge(status, 1L, Long::sum);
            if (hasText(job.lastError()) && ("unresolved".equals(status) || "retry_wait".equals(status) || "failed".equals(status))) {
                reasonCounts.merge(job.lastError(), 1L, Long::sum);
            }
        }

        List<AudioFeatureCompletionStatusCount> statusSummary = statusCounts.entrySet().stream()
            .map(entry -> new AudioFeatureCompletionStatusCount(entry.getKey(), entry.getValue()))
            .sorted(Comparator
                .comparingInt((AudioFeatureCompletionStatusCount count) -> audioCompletionStatusRank(count.status()))
                .thenComparing(AudioFeatureCompletionStatusCount::status))
            .toList();
        List<AudioFeatureCompletionReasonCount> reasonSummary = reasonCounts.entrySet().stream()
            .map(entry -> new AudioFeatureCompletionReasonCount(entry.getKey(), entry.getValue()))
            .sorted(Comparator
                .comparingLong(AudioFeatureCompletionReasonCount::jobCount)
                .reversed()
                .thenComparing(AudioFeatureCompletionReasonCount::reason))
            .limit(10)
            .toList();

        return new AudioFeatureCompletionCoverage(jobs.size(), statusSummary, reasonSummary, List.of());
    }

    private boolean isPlaybackTargetAvailable(LibraryTrackState track) {
        String status = track.playbackTargetStatus();
        return "native".equals(status)
            || "resolved".equals(status)
            || hasText(track.spotifyTrackId())
            || hasText(track.tidalTrackId());
    }

    private void assertAdmin(String userId) {
        String normalizedEmail = authAccountStore.findByUserId(userId)
            .map(account -> account.normalizedEmail())
            .orElse("");
        if (!ADMIN_EMAIL.equals(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Feature coverage admin access is restricted.");
        }
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0L) {
            return 0.0d;
        }
        return Math.round((numerator / (double) denominator) * 10000.0d) / 10000.0d;
    }

    private static Instant latest(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null || candidate.isAfter(current)) {
            return candidate;
        }
        return current;
    }

    private static void addSourceClassCoverage(
        Map<String, AudioFeatureSourceClassAccumulator> sourceClasses,
        String sourceClass,
        long trackCount,
        long audioFeatureFilledCount,
        Instant latestAudioResolvedAt,
        Instant staleCutoff
    ) {
        long staleAudioFeatureCount = 0L;
        if (audioFeatureFilledCount > 0L && (latestAudioResolvedAt == null || latestAudioResolvedAt.isBefore(staleCutoff))) {
            staleAudioFeatureCount = audioFeatureFilledCount;
        }
        addSourceClassCoverage(
            sourceClasses,
            sourceClass,
            trackCount,
            audioFeatureFilledCount,
            latestAudioResolvedAt,
            staleAudioFeatureCount
        );
    }

    private static void addSourceClassCoverage(
        Map<String, AudioFeatureSourceClassAccumulator> sourceClasses,
        String sourceClass,
        long trackCount,
        long audioFeatureFilledCount,
        Instant latestAudioResolvedAt,
        long staleAudioFeatureCount
    ) {
        String normalizedSourceClass = hasText(sourceClass) ? sourceClass : "unresolved";
        AudioFeatureSourceClassAccumulator accumulator = sourceClasses.computeIfAbsent(
            normalizedSourceClass,
            ignored -> new AudioFeatureSourceClassAccumulator()
        );
        accumulator.trackCount += trackCount;
        accumulator.audioFeatureFilledCount += audioFeatureFilledCount;
        accumulator.staleAudioFeatureCount += staleAudioFeatureCount;
        accumulator.latestAudioResolvedAt = latest(accumulator.latestAudioResolvedAt, latestAudioResolvedAt);
    }

    private static List<AudioFeatureSourceClassCoverage> toSourceClassCoverage(
        Map<String, AudioFeatureSourceClassAccumulator> sourceClasses
    ) {
        return sourceClasses.entrySet().stream()
            .map(entry -> new AudioFeatureSourceClassCoverage(
                entry.getKey(),
                entry.getValue().trackCount,
                entry.getValue().audioFeatureFilledCount,
                ratio(entry.getValue().audioFeatureFilledCount, entry.getValue().trackCount),
                entry.getValue().staleAudioFeatureCount,
                ratio(entry.getValue().staleAudioFeatureCount, entry.getValue().audioFeatureFilledCount),
                entry.getValue().latestAudioResolvedAt
            ))
            .sorted(Comparator
                .comparingInt((AudioFeatureSourceClassCoverage coverage) -> sourceClassRank(coverage.sourceClass()))
                .thenComparing(AudioFeatureSourceClassCoverage::sourceClass))
            .toList();
    }

    private static String audioFeatureSourceClass(String audioFeatureSource) {
        if (!hasText(audioFeatureSource)) {
            return "unresolved";
        }
        String source = audioFeatureSource.trim().toLowerCase(Locale.ROOT);
        if ("unresolved".equals(source) || "unavailable".equals(source)) {
            return "unresolved";
        }
        if (source.startsWith("reccobeats") || "spotify_api".equals(source) || "spotify_match".equals(source)) {
            return "provider_lookup";
        }
        if (source.startsWith("lastfm")) {
            return "tag_inferred";
        }
        if (source.contains("llm") || source.contains("web_search") || source.contains("search_inferred")) {
            return "llm_search_inferred";
        }
        if (source.contains("fallback_generated") || source.contains("generated")) {
            return "legacy_generated";
        }
        if (source.contains("measured") || source.contains("analysis")) {
            return "measured";
        }
        return "unknown";
    }

    private static int sourceClassRank(String sourceClass) {
        return switch (sourceClass) {
            case "measured" -> 0;
            case "provider_lookup" -> 1;
            case "tag_inferred" -> 2;
            case "llm_search_inferred" -> 3;
            case "legacy_generated" -> 4;
            case "unresolved" -> 5;
            default -> 6;
        };
    }

    private static int audioCompletionStatusRank(String status) {
        return switch (status) {
            case "queued" -> 0;
            case "running" -> 1;
            case "retry_wait" -> 2;
            case "unresolved" -> 3;
            case "failed" -> 4;
            case "completed" -> 5;
            default -> 6;
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static class AudioFeatureSourceClassAccumulator {
        private long trackCount;
        private long audioFeatureFilledCount;
        private long staleAudioFeatureCount;
        private Instant latestAudioResolvedAt;
    }

    public record FeatureCoverageReport(
        String targetUserId,
        Instant generatedAt,
        String status,
        PmsLibraryCoverage pmsLibrary,
        EmsPoolCoverage emsPool,
        EmsAcquisitionCoverage emsAcquisition,
        AudioFeatureCompletionCoverage audioFeatureCompletion,
        LearningDataCoverage learningData,
        List<String> warnings,
        List<DriftSignalEvaluator.DriftSignal> driftSignals
    ) {}

    public record PmsLibraryCoverage(
        int playlistCount,
        long trackCount,
        long audioFeatureFilledCount,
        double audioFeatureCoverageRatio,
        long staleAudioFeatureCount,
        double staleAudioFeatureRatio,
        Instant latestAudioResolvedAt,
        List<AudioFeatureSourceClassCoverage> audioFeatureSourceClasses,
        long isrcCount,
        double isrcCoverageRatio,
        long playbackTargetAvailableCount,
        double playbackTargetCoverageRatio
    ) {}

    public record EmsPoolCoverage(
        long trackCount,
        long audioFeatureFilledCount,
        double audioFeatureCoverageRatio,
        long staleAudioFeatureCount,
        double staleAudioFeatureRatio,
        Instant latestAudioResolvedAt,
        List<AudioFeatureSourceClassCoverage> audioFeatureSourceClasses,
        long isrcCount,
        double isrcCoverageRatio,
        long canonicalTrackCount,
        double canonicalTrackCoverageRatio,
        List<EmsSourceCoverage> sources,
        List<String> warnings
    ) {}

    public record EmsSourceCoverage(
        String sourcePlatform,
        long trackCount,
        long audioFeatureFilledCount,
        double audioFeatureCoverageRatio,
        long staleAudioFeatureCount,
        double staleAudioFeatureRatio,
        Instant latestAudioResolvedAt,
        long isrcCount,
        double isrcCoverageRatio,
        long canonicalTrackCount,
        double canonicalTrackCoverageRatio
    ) {}

    public record AudioFeatureSourceClassCoverage(
        String sourceClass,
        long trackCount,
        long audioFeatureFilledCount,
        double audioFeatureCoverageRatio,
        long staleAudioFeatureCount,
        double staleAudioFeatureRatio,
        Instant latestAudioResolvedAt
    ) {}

    public record EmsAcquisitionCoverage(
        long recentRunCount,
        long articleCount,
        long skippedArticleCount,
        long seedCount,
        long skippedSeedCount,
        long checkedItemCount,
        long skippedItemCount,
        double skippedItemRatio,
        List<String> warnings
    ) {}

    public record AudioFeatureCompletionCoverage(
        long recentJobCount,
        List<AudioFeatureCompletionStatusCount> statusCounts,
        List<AudioFeatureCompletionReasonCount> topReasons,
        List<String> warnings
    ) {}

    public record AudioFeatureCompletionStatusCount(
        String status,
        long jobCount
    ) {}

    public record AudioFeatureCompletionReasonCount(
        String reason,
        long jobCount
    ) {}

    public record LearningDataCoverage(
        long eventCount,
        long recentRecommendationSnapshotCount,
        int recentRecommendationSnapshotLimit
    ) {}
}
