package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TasteModeAffinityGateServiceTest {

    private final TasteModeAffinityGateService service = new TasteModeAffinityGateService(
        new ObjectMapper(),
        true,
        true,
        false,
        0.55d,
        0.82d,
        0.18d,
        0.03d
    );

    @Test
    void shouldMarkStrongHeavyAffinityAsDryRunWithoutChangingCurrentScore() {
        TasteModeAffinityGateService.GateResult result = service.evaluate(
            profile("heavy", 0.82d),
            usableCandidate(),
            affinity(0.9321d, 0.0679d, List.of("mode_energy_match", "mode_valence_match")),
            0.91d
        ).orElseThrow();

        assertThat(result.status()).isEqualTo("dry_run");
        assertThat(result.reason()).isEqualTo("eligible");
        assertThat(result.reasonTokens()).contains("eligible", "mode_energy_match", "mode_valence_match");
        assertThat(result.suggestedBoostWeight()).isEqualTo(0.0246d);
        assertThat(result.dryRunScore()).isEqualTo(0.9197d);
        assertThat(result.dryRunDelta()).isEqualTo(0.0097d);
    }

    @Test
    void shouldBlockLowProfileConfidence() {
        TasteModeAffinityGateService.GateResult result = service.evaluate(
            profile("heavy", 0.40d),
            usableCandidate(),
            affinity(0.9321d, 0.0679d, List.of("mode_energy_match")),
            0.91d
        ).orElseThrow();

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.reason()).isEqualTo("low_profile_confidence");
        assertThat(result.dryRunScore()).isNull();
        assertThat(result.dryRunDelta()).isNull();
    }

    @Test
    void shouldBlockLowModeSimilarity() {
        TasteModeAffinityGateService.GateResult result = service.evaluate(
            profile("heavy", 0.82d),
            usableCandidate(),
            affinity(0.70d, 0.12d, List.of("mode_energy_match")),
            0.91d
        ).orElseThrow();

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.reason()).isEqualTo("low_mode_similarity");
    }

    @Test
    void shouldBlockLargeModeDistance() {
        TasteModeAffinityGateService.GateResult result = service.evaluate(
            profile("heavy", 0.82d),
            usableCandidate(),
            affinity(0.86d, 0.22d, List.of("mode_energy_match")),
            0.91d
        ).orElseThrow();

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.reason()).isEqualTo("large_mode_distance");
    }

    @Test
    void shouldBlockWeakTokenOnlyMatch() {
        TasteModeAffinityGateService.GateResult result = service.evaluate(
            profile("heavy", 0.82d),
            usableCandidate(),
            affinity(0.90d, 0.10d, List.of("mode_profile_distance")),
            0.91d
        ).orElseThrow();

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.reason()).isEqualTo("weak_token_match");
    }

    @Test
    void shouldReturnNotApplicableForNonHeavyProfile() {
        TasteModeAffinityGateService.GateResult result = service.evaluate(
            profile("strong", 0.82d),
            usableCandidate(),
            affinity(0.90d, 0.10d, List.of("mode_energy_match")),
            0.91d
        ).orElseThrow();

        assertThat(result.status()).isEqualTo("not_applicable");
        assertThat(result.reason()).isEqualTo("profile_not_heavy");
    }

    @Test
    void shouldKeepDryRunScoreEmptyForInvalidCurrentScore() {
        TasteModeAffinityGateService.GateResult result = service.evaluate(
            profile("heavy", 0.82d),
            usableCandidate(),
            affinity(0.9321d, 0.0679d, List.of("mode_energy_match")),
            Double.NaN
        ).orElseThrow();

        assertThat(result.status()).isEqualTo("eligible");
        assertThat(result.reason()).isEqualTo("eligible");
        assertThat(result.dryRunScore()).isNull();
        assertThat(result.dryRunDelta()).isNull();
    }

    @Test
    void shouldSkipItemResultWhenGateDisabledButKeepAuditSummaryAvailable() {
        TasteModeAffinityGateService disabledService = new TasteModeAffinityGateService(
            new ObjectMapper(),
            false,
            true,
            false,
            0.55d,
            0.82d,
            0.18d,
            0.03d
        );

        assertThat(disabledService.evaluate(
            profile("heavy", 0.82d),
            usableCandidate(),
            affinity(0.9321d, 0.0679d, List.of("mode_energy_match")),
            0.91d
        )).isEmpty();

        TasteModeAffinityGateService.GateSummary summary = disabledService.summarize(List.of());
        String json = disabledService.toAuditJson(summary);

        assertThat(summary.gateEnabled()).isFalse();
        assertThat(summary.evaluatedCount()).isZero();
        assertThat(json).contains("\"gate_enabled\":false");
        assertThat(json).contains("\"evaluated_count\":0");
    }

    @Test
    void shouldSummarizeGateResultsAsJson() {
        List<TasteModeAffinityGateService.GateResult> results = List.of(
            service.evaluate(
                profile("heavy", 0.82d),
                usableCandidate(),
                affinity(0.9321d, 0.0679d, List.of("mode_energy_match")),
                0.91d
            ).orElseThrow(),
            service.evaluate(
                profile("heavy", 0.82d),
                usableCandidate(),
                affinity(0.70d, 0.10d, List.of("mode_energy_match")),
                0.80d
            ).orElseThrow()
        );

        TasteModeAffinityGateService.GateSummary summary = service.summarize(results);
        String json = service.toAuditJson(summary);

        assertThat(summary.evaluatedCount()).isEqualTo(2);
        assertThat(summary.dryRunCount()).isEqualTo(1);
        assertThat(summary.blockedCount()).isEqualTo(1);
        assertThat(summary.reasonCounts()).containsEntry("eligible", 1L);
        assertThat(summary.reasonCounts()).containsEntry("low_mode_similarity", 1L);
        assertThat(json).contains("\"dry_run_count\":1");
        assertThat(json).contains("\"low_mode_similarity\":1");
    }

    @Test
    void shouldTrackMostNegativeDeltaInSummary() {
        TasteModeAffinityGateService.GateSummary summary = service.summarize(List.of(
            new TasteModeAffinityGateService.GateResult(
                "dry_run",
                "eligible",
                List.of("eligible"),
                0.01d,
                0.95d,
                -0.01d
            ),
            new TasteModeAffinityGateService.GateResult(
                "dry_run",
                "eligible",
                List.of("eligible"),
                0.01d,
                0.90d,
                -0.04d
            )
        ));

        assertThat(summary.maxNegativeDelta()).isEqualTo(-0.04d);
    }

    @Test
    void shouldApplyRankingBoostOnlyWhenEnabledAndEligible() {
        TasteModeAffinityGateService enabledService = new TasteModeAffinityGateService(
            new ObjectMapper(),
            true,
            true,
            true,
            0.55d,
            0.82d,
            0.18d,
            0.03d
        );
        TasteModeAffinityGateService.GateResult result = enabledService.evaluate(
            profile("heavy", 0.82d),
            usableCandidate(),
            affinity(0.9321d, 0.0679d, List.of("mode_energy_match")),
            0.91d
        ).orElseThrow();

        TasteModeAffinityGateService.BoostResult boost = enabledService.applyRankingBoost(
            result,
            0.91d,
            0.9321d
        ).orElseThrow();

        assertThat(boost.score()).isEqualTo(0.9197d);
        assertThat(boost.delta()).isEqualTo(0.0097d);
        assertThat(service.applyRankingBoost(result, 0.91d, 0.9321d)).isEmpty();
    }

    @Test
    void shouldRejectRankingBoostForBlockedOrInvalidInputs() {
        TasteModeAffinityGateService enabledService = new TasteModeAffinityGateService(
            new ObjectMapper(),
            true,
            true,
            true,
            0.55d,
            0.82d,
            0.18d,
            0.03d
        );
        TasteModeAffinityGateService.GateResult blocked = enabledService.evaluate(
            profile("heavy", 0.82d),
            usableCandidate(),
            affinity(0.70d, 0.0679d, List.of("mode_energy_match")),
            0.91d
        ).orElseThrow();

        assertThat(enabledService.applyRankingBoost(blocked, 0.91d, 0.9321d)).isEmpty();
        assertThat(enabledService.applyRankingBoost(null, 0.91d, 0.9321d)).isEmpty();
        assertThat(enabledService.applyRankingBoost(blocked, Double.NaN, 0.9321d)).isEmpty();
    }

    @Test
    void shouldIncludeBoostMetricsInAuditJson() {
        TasteModeAffinityGateService enabledService = new TasteModeAffinityGateService(
            new ObjectMapper(),
            true,
            true,
            true,
            0.55d,
            0.82d,
            0.18d,
            0.03d
        );
        TasteModeAffinityGateService.GateResult result = enabledService.evaluate(
            profile("heavy", 0.82d),
            usableCandidate(),
            affinity(0.9321d, 0.0679d, List.of("mode_energy_match")),
            0.91d
        ).orElseThrow();

        TasteModeAffinityGateService.GateSummary summary = enabledService.summarize(
            List.of(result),
            new TasteModeAffinityGateService.BoostMetrics(1, 1)
        );
        String json = enabledService.toAuditJson(summary);

        assertThat(summary.boostAppliedCount()).isEqualTo(1);
        assertThat(summary.rankChangedCount()).isEqualTo(1);
        assertThat(json).contains("\"apply_ranking_boost\":true");
        assertThat(json).contains("\"boost_applied_count\":1");
        assertThat(json).contains("\"rank_changed_count\":1");
    }

    private AudioTasteProfileService.Profile profile(String profileType, double profileConfidence) {
        return new AudioTasteProfileService.Profile(
            "user-1",
            "ok",
            true,
            profileType,
            "balanced",
            profileConfidence,
            new AudioTasteProfileService.Diversity(12, "Artist A", 0.18d, 2),
            new AudioTasteProfileService.SourceQualityMix(1.0d, 0.0d, 0.0d, 0.0d, 0.0d),
            List.of(),
            80,
            2,
            500,
            new AudioTasteProfileService.Centroid(0.20d, 0.70d, 0.80d, 0.01d, 0.10d, 0.05d, 0.50d, 0.70d),
            AudioTasteProfileService.Centroid.empty(),
            new AudioTasteProfileService.Coverage(220, 220, 1.0d, 0, 0),
            List.of(),
            Instant.parse("2026-05-21T00:00:00Z")
        );
    }

    private AudioTasteTrackFeature usableCandidate() {
        return new AudioTasteTrackFeature(
            "pms_user_track",
            "candidate-1",
            "Candidate",
            "Artist",
            "spotify",
            "reccobeats_lookup",
            true,
            1.0d,
            "provider",
            0.20d,
            0.78d,
            0.82d,
            0.02d,
            0.12d,
            0.05d,
            142.0d,
            0.70d
        );
    }

    private AudioTasteModeAffinityService.TasteModeAffinity affinity(
        double similarity,
        double distance,
        List<String> tokens
    ) {
        return new AudioTasteModeAffinityService.TasteModeAffinity(
            true,
            "mode-1",
            "high_energy_bright_danceable",
            similarity,
            distance,
            tokens
        );
    }
}
