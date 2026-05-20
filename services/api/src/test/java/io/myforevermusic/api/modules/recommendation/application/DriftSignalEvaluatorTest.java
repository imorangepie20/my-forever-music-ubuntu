package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DriftSignalEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-05-14T00:00:00Z");

    @Test
    void shouldRaiseSignalsForLowPmsAudioPlaybackAndIsrcCoverage() {
        DriftSignalEvaluator evaluator = configuredEvaluator();
        FeatureCoverageAdminService.FeatureCoverageReport report = report(
            pms(1, 100L, 20L, 0.2d, 0L, 0d, NOW, 30L, 0.3d, 50L, 0.5d),
            emptyEms(),
            healthyAcquisition(),
            new FeatureCoverageAdminService.LearningDataCoverage(100L, 0L, 1000)
        );

        List<DriftSignalEvaluator.DriftSignal> signals = evaluator.evaluate(report);

        assertThat(signals).extracting(DriftSignalEvaluator.DriftSignal::category)
            .containsExactlyInAnyOrder(
                DriftSignalEvaluator.CATEGORY_PMS_AUDIO,
                DriftSignalEvaluator.CATEGORY_PMS_PLAYBACK,
                DriftSignalEvaluator.CATEGORY_PMS_ISRC
            );
        assertThat(signals).allSatisfy(signal ->
            assertThat(signal.severity()).isEqualTo(DriftSignalEvaluator.SEVERITY_WARN)
        );
    }

    @Test
    void shouldRespectMinTrackCountGuardForEmsSources() {
        DriftSignalEvaluator evaluator = configuredEvaluator();
        FeatureCoverageAdminService.FeatureCoverageReport report = report(
            pms(0, 0L, 0L, 0d, 0L, 0d, null, 0L, 0d, 0L, 0d),
            emsPool(10L, 0L, 0d, 0L, 0d, null, 0L, 0d, 0L, 0d, List.of(
                emsSource("tidal", 10L, 0L, 0d, 0L, 0d, null, 0L, 0d, 0L, 0d)
            )),
            healthyAcquisition(),
            new FeatureCoverageAdminService.LearningDataCoverage(1000L, 0L, 1000)
        );

        // 10 tracks is below the ems-min-track-count (20) so no source-level signals should fire
        assertThat(evaluator.evaluate(report)).isEmpty();
    }

    @Test
    void shouldRaiseEmsAudioAndIsrcSignalsAboveMinTrackCount() {
        DriftSignalEvaluator evaluator = configuredEvaluator();
        FeatureCoverageAdminService.FeatureCoverageReport report = report(
            pms(0, 0L, 0L, 0d, 0L, 0d, null, 0L, 0d, 0L, 0d),
            emsPool(30L, 3L, 0.1d, 0L, 0d, NOW, 6L, 0.2d, 0L, 0d, List.of(
                emsSource("spotify", 30L, 3L, 0.1d, 0L, 0d, NOW, 6L, 0.2d, 0L, 0d)
            )),
            healthyAcquisition(),
            new FeatureCoverageAdminService.LearningDataCoverage(1000L, 0L, 1000)
        );

        List<DriftSignalEvaluator.DriftSignal> signals = evaluator.evaluate(report);
        assertThat(signals).extracting(DriftSignalEvaluator.DriftSignal::category)
            .contains(
                DriftSignalEvaluator.CATEGORY_EMS_AUDIO,
                DriftSignalEvaluator.CATEGORY_EMS_ISRC
            );
        assertThat(signals).extracting(DriftSignalEvaluator.DriftSignal::targetScope)
            .allSatisfy(scope -> assertThat(scope).isEqualTo("ems:spotify"));
    }

    @Test
    void shouldRaiseAudioStaleSignalsWhenResolvedFeaturesAreOld() {
        DriftSignalEvaluator evaluator = configuredEvaluator();
        FeatureCoverageAdminService.FeatureCoverageReport report = report(
            pms(1, 100L, 80L, 0.8d, 40L, 0.5d, NOW.minusSeconds(10), 90L, 0.9d, 95L, 0.95d),
            emsPool(100L, 80L, 0.8d, 35L, 0.4375d, NOW.minusSeconds(10), 90L, 0.9d, 70L, 0.7d, List.of(
                emsSource("spotify", 100L, 80L, 0.8d, 35L, 0.4375d, NOW.minusSeconds(10), 90L, 0.9d, 70L, 0.7d)
            )),
            healthyAcquisition(),
            new FeatureCoverageAdminService.LearningDataCoverage(500L, 50L, 1000)
        );

        List<DriftSignalEvaluator.DriftSignal> signals = evaluator.evaluate(report);

        assertThat(signals).extracting(DriftSignalEvaluator.DriftSignal::category)
            .containsExactlyInAnyOrder(
                DriftSignalEvaluator.CATEGORY_AUDIO_STALE,
                DriftSignalEvaluator.CATEGORY_AUDIO_STALE
            );
        assertThat(signals).extracting(DriftSignalEvaluator.DriftSignal::targetScope)
            .containsExactlyInAnyOrder("pms", "ems:spotify");
    }

    @Test
    void shouldRaiseAcquisitionSkipSignalWhenRecentRunsAreMostlyDuplicates() {
        DriftSignalEvaluator evaluator = configuredEvaluator();
        FeatureCoverageAdminService.FeatureCoverageReport report = report(
            pms(1, 100L, 90L, 0.9d, 0L, 0d, NOW, 80L, 0.8d, 95L, 0.95d),
            emsPool(100L, 90L, 0.9d, 0L, 0d, NOW, 85L, 0.85d, 70L, 0.7d, List.of(
                emsSource("spotify", 100L, 90L, 0.9d, 0L, 0d, NOW, 85L, 0.85d, 70L, 0.7d)
            )),
            new FeatureCoverageAdminService.EmsAcquisitionCoverage(
                3L,
                10L,
                9L,
                1L,
                9L,
                20L,
                18L,
                0.9d,
                List.of()
            ),
            new FeatureCoverageAdminService.LearningDataCoverage(500L, 50L, 1000)
        );

        List<DriftSignalEvaluator.DriftSignal> signals = evaluator.evaluate(report);

        assertThat(signals).extracting(DriftSignalEvaluator.DriftSignal::category)
            .containsExactly(DriftSignalEvaluator.CATEGORY_EMS_ACQUISITION_SKIPS);
        assertThat(signals.getFirst().severity()).isEqualTo(DriftSignalEvaluator.SEVERITY_INFO);
    }

    @Test
    void shouldRaiseLearningSignalWhenEventCountBelowThreshold() {
        DriftSignalEvaluator evaluator = configuredEvaluator();
        FeatureCoverageAdminService.FeatureCoverageReport report = report(
            pms(0, 0L, 0L, 0d, 0L, 0d, null, 0L, 0d, 0L, 0d),
            emptyEms(),
            healthyAcquisition(),
            new FeatureCoverageAdminService.LearningDataCoverage(10L, 0L, 1000)
        );

        List<DriftSignalEvaluator.DriftSignal> signals = evaluator.evaluate(report);
        assertThat(signals).extracting(DriftSignalEvaluator.DriftSignal::category)
            .containsExactly(DriftSignalEvaluator.CATEGORY_LEARNING_THIN);
        assertThat(signals).extracting(DriftSignalEvaluator.DriftSignal::severity)
            .containsExactly(DriftSignalEvaluator.SEVERITY_INFO);
    }

    @Test
    void shouldEmitNothingWhenAllMetricsHealthy() {
        DriftSignalEvaluator evaluator = configuredEvaluator();
        FeatureCoverageAdminService.FeatureCoverageReport report = report(
            pms(2, 100L, 90L, 0.9d, 0L, 0d, NOW, 80L, 0.8d, 95L, 0.95d),
            emsPool(100L, 90L, 0.9d, 0L, 0d, NOW, 85L, 0.85d, 70L, 0.7d, List.of(
                emsSource("spotify", 100L, 90L, 0.9d, 0L, 0d, NOW, 85L, 0.85d, 70L, 0.7d)
            )),
            healthyAcquisition(),
            new FeatureCoverageAdminService.LearningDataCoverage(500L, 50L, 1000)
        );

        assertThat(evaluator.evaluate(report)).isEmpty();
    }

    private DriftSignalEvaluator configuredEvaluator() {
        DriftSignalEvaluator evaluator = new DriftSignalEvaluator();
        ReflectionTestUtils.setField(evaluator, "pmsAudioMinRatio", 0.5d);
        ReflectionTestUtils.setField(evaluator, "pmsPlaybackMinRatio", 0.7d);
        ReflectionTestUtils.setField(evaluator, "pmsIsrcMinRatio", 0.4d);
        ReflectionTestUtils.setField(evaluator, "emsAudioMinRatio", 0.3d);
        ReflectionTestUtils.setField(evaluator, "emsIsrcMinRatio", 0.5d);
        ReflectionTestUtils.setField(evaluator, "emsCanonicalMinRatio", 0.2d);
        ReflectionTestUtils.setField(evaluator, "emsMinTrackCount", 20L);
        ReflectionTestUtils.setField(evaluator, "emsCanonicalMinTrackCount", 50L);
        ReflectionTestUtils.setField(evaluator, "audioStaleMaxRatio", 0.25d);
        ReflectionTestUtils.setField(evaluator, "audioStaleMinFilledCount", 20L);
        ReflectionTestUtils.setField(evaluator, "emsAcquisitionSkipMaxRatio", 0.8d);
        ReflectionTestUtils.setField(evaluator, "emsAcquisitionMinCheckedCount", 10L);
        ReflectionTestUtils.setField(evaluator, "learningMinEventCount", 50L);
        return evaluator;
    }

    private FeatureCoverageAdminService.PmsLibraryCoverage pms(
        int playlistCount,
        long trackCount,
        long audioFeatureFilledCount,
        double audioFeatureCoverageRatio,
        long staleAudioFeatureCount,
        double staleAudioFeatureRatio,
        Instant latestAudioResolvedAt,
        long isrcCount,
        double isrcCoverageRatio,
        long playbackTargetAvailableCount,
        double playbackTargetCoverageRatio
    ) {
        return new FeatureCoverageAdminService.PmsLibraryCoverage(
            playlistCount,
            trackCount,
            audioFeatureFilledCount,
            audioFeatureCoverageRatio,
            staleAudioFeatureCount,
            staleAudioFeatureRatio,
            latestAudioResolvedAt,
            List.of(),
            isrcCount,
            isrcCoverageRatio,
            playbackTargetAvailableCount,
            playbackTargetCoverageRatio
        );
    }

    private FeatureCoverageAdminService.EmsPoolCoverage emptyEms() {
        return emsPool(0L, 0L, 0d, 0L, 0d, null, 0L, 0d, 0L, 0d, List.of());
    }

    private FeatureCoverageAdminService.EmsPoolCoverage emsPool(
        long trackCount,
        long audioFeatureFilledCount,
        double audioFeatureCoverageRatio,
        long staleAudioFeatureCount,
        double staleAudioFeatureRatio,
        Instant latestAudioResolvedAt,
        long isrcCount,
        double isrcCoverageRatio,
        long canonicalTrackCount,
        double canonicalTrackCoverageRatio,
        List<FeatureCoverageAdminService.EmsSourceCoverage> sources
    ) {
        return new FeatureCoverageAdminService.EmsPoolCoverage(
            trackCount,
            audioFeatureFilledCount,
            audioFeatureCoverageRatio,
            staleAudioFeatureCount,
            staleAudioFeatureRatio,
            latestAudioResolvedAt,
            List.of(),
            isrcCount,
            isrcCoverageRatio,
            canonicalTrackCount,
            canonicalTrackCoverageRatio,
            sources,
            List.of()
        );
    }

    private FeatureCoverageAdminService.EmsSourceCoverage emsSource(
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
    ) {
        return new FeatureCoverageAdminService.EmsSourceCoverage(
            sourcePlatform,
            trackCount,
            audioFeatureFilledCount,
            audioFeatureCoverageRatio,
            staleAudioFeatureCount,
            staleAudioFeatureRatio,
            latestAudioResolvedAt,
            isrcCount,
            isrcCoverageRatio,
            canonicalTrackCount,
            canonicalTrackCoverageRatio
        );
    }

    private FeatureCoverageAdminService.EmsAcquisitionCoverage healthyAcquisition() {
        return new FeatureCoverageAdminService.EmsAcquisitionCoverage(
            2L,
            20L,
            1L,
            10L,
            1L,
            31L,
            2L,
            0.0645d,
            List.of()
        );
    }

    private FeatureCoverageAdminService.FeatureCoverageReport report(
        FeatureCoverageAdminService.PmsLibraryCoverage pms,
        FeatureCoverageAdminService.EmsPoolCoverage ems,
        FeatureCoverageAdminService.EmsAcquisitionCoverage acquisition,
        FeatureCoverageAdminService.LearningDataCoverage learning
    ) {
        return new FeatureCoverageAdminService.FeatureCoverageReport(
            "user-1",
            NOW,
            "ok",
            pms,
            ems,
            acquisition,
            new FeatureCoverageAdminService.AudioFeatureCompletionCoverage(0L, List.of(), List.of(), List.of()),
            learning,
            List.of(),
            List.of()
        );
    }
}
