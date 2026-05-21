package io.myforevermusic.api.modules.recommendation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TasteModeAffinityGateService {

    private static final String STATUS_NOT_APPLICABLE = "not_applicable";
    private static final String STATUS_BLOCKED = "blocked";
    private static final String STATUS_ELIGIBLE = "eligible";
    private static final String STATUS_DRY_RUN = "dry_run";

    private static final String REASON_MISSING_PROFILE = "missing_profile";
    private static final String REASON_PROFILE_NOT_HEAVY = "profile_not_heavy";
    private static final String REASON_MISSING_AFFINITY = "missing_affinity";
    private static final String REASON_CANDIDATE_AUDIO_UNUSABLE = "candidate_audio_unusable";
    private static final String REASON_LOW_PROFILE_CONFIDENCE = "low_profile_confidence";
    private static final String REASON_LOW_MODE_SIMILARITY = "low_mode_similarity";
    private static final String REASON_LARGE_MODE_DISTANCE = "large_mode_distance";
    private static final String REASON_WEAK_TOKEN_MATCH = "weak_token_match";
    private static final String REASON_ELIGIBLE = "eligible";

    private static final String PROFILE_HEAVY = "heavy";
    private static final String WEAK_DISTANCE_TOKEN = "mode_profile_distance";

    private final ObjectMapper objectMapper;
    private final boolean gateEnabled;
    private final boolean dryRunEnabled;
    private final boolean applyRankingBoost;
    private final double minProfileConfidence;
    private final double minSimilarity;
    private final double maxDistance;
    private final double maxBoostWeight;

    public TasteModeAffinityGateService(
        ObjectMapper objectMapper,
        @Value("${app.recommendation.taste-mode-affinity.gate.enabled:true}") boolean gateEnabled,
        @Value("${app.recommendation.taste-mode-affinity.dry-run-enabled:true}") boolean dryRunEnabled,
        @Value("${app.recommendation.taste-mode-affinity.apply-ranking-boost:false}") boolean applyRankingBoost,
        @Value("${app.recommendation.taste-mode-affinity.min-profile-confidence:0.55}") double minProfileConfidence,
        @Value("${app.recommendation.taste-mode-affinity.min-similarity:0.82}") double minSimilarity,
        @Value("${app.recommendation.taste-mode-affinity.max-distance:0.18}") double maxDistance,
        @Value("${app.recommendation.taste-mode-affinity.max-boost-weight:0.03}") double maxBoostWeight
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.gateEnabled = gateEnabled;
        this.dryRunEnabled = dryRunEnabled;
        this.applyRankingBoost = applyRankingBoost;
        this.minProfileConfidence = minProfileConfidence;
        this.minSimilarity = minSimilarity;
        this.maxDistance = maxDistance;
        this.maxBoostWeight = Math.max(0.0d, maxBoostWeight);
    }

    public Optional<GateResult> evaluate(
        AudioTasteProfileService.Profile profile,
        AudioTasteTrackFeature candidate,
        AudioTasteModeAffinityService.TasteModeAffinity affinity,
        Double currentScore
    ) {
        if (!gateEnabled) {
            return Optional.empty();
        }
        if (profile == null) {
            return Optional.of(result(STATUS_NOT_APPLICABLE, REASON_MISSING_PROFILE, null, null, null, null));
        }
        if (!PROFILE_HEAVY.equals(profile.profileType())) {
            return Optional.of(result(STATUS_NOT_APPLICABLE, REASON_PROFILE_NOT_HEAVY, null, null, null, null));
        }
        if (affinity == null || !affinity.applied()) {
            return Optional.of(result(STATUS_NOT_APPLICABLE, REASON_MISSING_AFFINITY, null, null, null, null));
        }
        if (candidate == null || !candidate.usable()) {
            return Optional.of(result(
                STATUS_NOT_APPLICABLE,
                REASON_CANDIDATE_AUDIO_UNUSABLE,
                affinity.tokens(),
                null,
                null,
                null
            ));
        }
        if (profile.profileConfidence() < minProfileConfidence) {
            return Optional.of(blocked(REASON_LOW_PROFILE_CONFIDENCE, affinity.tokens()));
        }
        if (affinity.similarity() < minSimilarity) {
            return Optional.of(blocked(REASON_LOW_MODE_SIMILARITY, affinity.tokens()));
        }
        if (affinity.distance() > maxDistance) {
            return Optional.of(blocked(REASON_LARGE_MODE_DISTANCE, affinity.tokens()));
        }
        if (!hasStrongTokenMatch(affinity.tokens())) {
            return Optional.of(blocked(REASON_WEAK_TOKEN_MATCH, affinity.tokens()));
        }

        double suggestedBoostWeight = round(clamp(profile.profileConfidence()) * maxBoostWeight);
        if (!dryRunEnabled || !finite(currentScore)) {
            return Optional.of(result(
                STATUS_ELIGIBLE,
                REASON_ELIGIBLE,
                affinity.tokens(),
                suggestedBoostWeight,
                null,
                null
            ));
        }

        double dryRunScore = roundScore(currentScore * (1.0d + suggestedBoostWeight * (affinity.similarity() - 0.5d)));
        double dryRunDelta = roundScore(dryRunScore - currentScore);
        return Optional.of(result(
            STATUS_DRY_RUN,
            REASON_ELIGIBLE,
            affinity.tokens(),
            suggestedBoostWeight,
            dryRunScore,
            dryRunDelta
        ));
    }

    public Optional<BoostResult> applyRankingBoost(GateResult result, Double currentScore, Double similarity) {
        if (result == null || !applyRankingBoost || !eligibleResult(result) || result.suggestedBoostWeight() == null) {
            return Optional.empty();
        }
        if (!finite(currentScore) || !finite(similarity)) {
            return Optional.empty();
        }
        double nextScore = roundScore(currentScore * (1.0d + result.suggestedBoostWeight() * (similarity - 0.5d)));
        return Optional.of(new BoostResult(nextScore, roundScore(nextScore - currentScore)));
    }

    public boolean rankingBoostEnabled() {
        return applyRankingBoost;
    }

    public GateSummary summarize(List<GateResult> results) {
        return summarize(results, BoostMetrics.empty());
    }

    public GateSummary summarize(List<GateResult> results, BoostMetrics boostMetrics) {
        List<GateResult> safeResults = results == null ? List.of() : results.stream()
            .filter(Objects::nonNull)
            .toList();
        BoostMetrics safeBoostMetrics = boostMetrics == null ? BoostMetrics.empty() : boostMetrics;
        Map<String, Long> reasonCounts = new LinkedHashMap<>();
        for (GateResult result : safeResults) {
            reasonCounts.merge(result.reason(), 1L, Long::sum);
        }
        return new GateSummary(
            gateEnabled,
            dryRunEnabled,
            applyRankingBoost,
            safeResults.size(),
            (int) safeResults.stream().filter(this::eligibleResult).count(),
            (int) safeResults.stream().filter(result -> STATUS_DRY_RUN.equals(result.status())).count(),
            (int) safeResults.stream().filter(result -> STATUS_BLOCKED.equals(result.status())).count(),
            (int) safeResults.stream().filter(result -> STATUS_NOT_APPLICABLE.equals(result.status())).count(),
            safeResults.stream()
                .map(GateResult::dryRunDelta)
                .filter(Objects::nonNull)
                .filter(delta -> delta > 0.0d)
                .max(Double::compareTo)
                .orElse(0.0d),
            safeResults.stream()
                .map(GateResult::dryRunDelta)
                .filter(Objects::nonNull)
                .filter(delta -> delta < 0.0d)
                .min(Double::compareTo)
                .orElse(0.0d),
            safeBoostMetrics.boostAppliedCount(),
            safeBoostMetrics.rankChangedCount(),
            reasonCounts
        );
    }

    public String toAuditJson(GateSummary summary) {
        GateSummary safeSummary = summary == null ? summarize(List.of()) : summary;
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("gate_enabled", safeSummary.gateEnabled());
        audit.put("dry_run_enabled", safeSummary.dryRunEnabled());
        audit.put("apply_ranking_boost", safeSummary.applyRankingBoost());
        audit.put("evaluated_count", safeSummary.evaluatedCount());
        audit.put("eligible_count", safeSummary.eligibleCount());
        audit.put("dry_run_count", safeSummary.dryRunCount());
        audit.put("blocked_count", safeSummary.blockedCount());
        audit.put("not_applicable_count", safeSummary.notApplicableCount());
        audit.put("max_positive_delta", safeSummary.maxPositiveDelta());
        audit.put("max_negative_delta", safeSummary.maxNegativeDelta());
        audit.put("boost_applied_count", safeSummary.boostAppliedCount());
        audit.put("rank_changed_count", safeSummary.rankChangedCount());
        audit.put("reason_counts", safeSummary.reasonCounts());
        try {
            return objectMapper.writeValueAsString(audit);
        } catch (JsonProcessingException exception) {
            return "{\"gate_enabled\":%s,\"serialization_error\":\"%s\"}".formatted(
                gateEnabled,
                exception.getClass().getSimpleName()
            );
        }
    }

    private GateResult blocked(String reason, List<String> affinityTokens) {
        return result(STATUS_BLOCKED, reason, affinityTokens, null, null, null);
    }

    private GateResult result(
        String status,
        String reason,
        List<String> affinityTokens,
        Double suggestedBoostWeight,
        Double dryRunScore,
        Double dryRunDelta
    ) {
        return new GateResult(
            status,
            reason,
            reasonTokens(reason, affinityTokens),
            suggestedBoostWeight,
            dryRunScore,
            dryRunDelta
        );
    }

    private List<String> reasonTokens(String reason, List<String> affinityTokens) {
        List<String> tokens = new ArrayList<>();
        tokens.add(reason);
        if (affinityTokens != null) {
            affinityTokens.stream()
                .filter(token -> token != null && !token.isBlank())
                .forEach(tokens::add);
        }
        return List.copyOf(tokens);
    }

    private boolean hasStrongTokenMatch(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return false;
        }
        return tokens.stream()
            .filter(token -> token != null && !token.isBlank())
            .anyMatch(token -> !WEAK_DISTANCE_TOKEN.equals(token));
    }

    private boolean eligibleResult(GateResult result) {
        return REASON_ELIGIBLE.equals(result.reason())
            && (STATUS_ELIGIBLE.equals(result.status()) || STATUS_DRY_RUN.equals(result.status()));
    }

    private boolean finite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private double round(double value) {
        return roundScore(value);
    }

    private double roundScore(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    public record GateResult(
        String status,
        String reason,
        List<String> reasonTokens,
        Double suggestedBoostWeight,
        Double dryRunScore,
        Double dryRunDelta
    ) {
        public GateResult {
            reasonTokens = reasonTokens == null ? List.of() : List.copyOf(reasonTokens);
        }
    }

    public record BoostResult(
        double score,
        double delta
    ) {
    }

    public record BoostMetrics(
        int boostAppliedCount,
        int rankChangedCount
    ) {
        public static BoostMetrics empty() {
            return new BoostMetrics(0, 0);
        }
    }

    public record GateSummary(
        boolean gateEnabled,
        boolean dryRunEnabled,
        boolean applyRankingBoost,
        int evaluatedCount,
        int eligibleCount,
        int dryRunCount,
        int blockedCount,
        int notApplicableCount,
        Double maxPositiveDelta,
        Double maxNegativeDelta,
        int boostAppliedCount,
        int rankChangedCount,
        Map<String, Long> reasonCounts
    ) {
        public GateSummary {
            reasonCounts = reasonCounts == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(reasonCounts));
        }
    }
}
