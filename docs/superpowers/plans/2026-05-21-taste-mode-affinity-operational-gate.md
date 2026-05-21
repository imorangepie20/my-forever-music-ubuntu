# Taste Mode Affinity Operational Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a dry-run operational gate for GMS preview `taste_mode_affinity` so operators can see eligible/blocked/hypothetical boost evidence without changing ranking.

**Architecture:** Keep the gate in Spring API as a focused recommendation application service. GMS preview attaches optional item-level `taste_mode_gate`, stores a preview-level JSON summary in `recommendation_audit_log`, and keeps actual rank/score/order unchanged. The web UI extends the existing GMS Preview affinity panel to show blocked/dry-run gate details.

**Tech Stack:** Spring Boot 3.5, Java 21 records, Flyway, JPA, Jackson, JUnit 5, AssertJ, React 18, TypeScript, Vite, Playwright.

---

## File Structure

- Create `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/TasteModeAffinityGateService.java`
  - Pure gate evaluator with configurable thresholds.
  - Produces item-level `GateResult` and preview-level `GateSummary`.
  - Computes dry-run score only when the gate passes and dry-run is enabled.
- Create `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/TasteModeAffinityGateServiceTest.java`
  - Unit tests for eligible/dry-run, low confidence, low similarity, large distance, weak token, non-heavy, and invalid score behavior.
- Create `services/api/src/main/resources/db/migration/V46__add_taste_mode_gate_summary_to_recommendation_audit_log.sql`
  - Adds nullable `taste_mode_gate_summary TEXT` to the existing audit table.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/gms/presentation/GmsRecommendationPreviewResponse.java`
  - Add nullable `TasteModeGateItem tasteModeGate` to each item.
  - Preserve it through `withAxisEvidence`, `withTasteModeAffinity`, and a new `withTasteModeGate`.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/RecommendationAuditLogStore.java`
  - Add `tasteModeGateSummary` to `AuditDraft` and `StoredAuditLog`.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/local/InMemoryRecommendationAuditLogStore.java`
  - Carry the new audit summary field.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/persistence/RecommendationAuditLogEntity.java`
  - Map `taste_mode_gate_summary` to JPA.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/RecommendationAuditLogAdminController.java`
  - Return `taste_mode_gate_summary` in admin responses.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewService.java`
  - Inject `TasteModeAffinityGateService`.
  - Evaluate gate after nearest mode affinity is attached.
  - Add a short response warning and audit JSON summary.
  - Do not alter ranking order or item score.
- Modify `services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java`
  - Add tests for item gate output, rank/score stability, warning, and audit summary.
  - Update constructor call sites for the new dependency.
- Modify `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`
  - Document the new `taste_mode_gate` response field and dry-run policy.
- Modify `apps/web/src/types/api.ts`
  - Add `GmsTasteModeGate` and optional `taste_mode_gate`.
- Modify `apps/web/src/pages/GmsPreviewPage.tsx`
  - Show gate status/reason/dry-run delta inside the existing affinity panel.
- Modify `apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts`
  - Extend the existing focused test to assert dry-run and blocked gate UI.

Verification commands:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.TasteModeAffinityGateServiceTest --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest -PbuildDir=/tmp/my-forever-music-api-build

cd apps/web
npm run test:e2e -- tests/e2e/gms-preview-taste-mode-affinity.spec.ts
npm run build

cd /srv/my-forever-music
git diff --check
```

---

### Task 1: Add Gate Evaluator With Unit Tests

**Files:**
- Create: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/TasteModeAffinityGateServiceTest.java`
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/TasteModeAffinityGateService.java`

- [ ] **Step 1: Write failing gate service tests**

Create `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/TasteModeAffinityGateServiceTest.java`:

```java
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
            service.evaluate(profile("heavy", 0.82d), usableCandidate(), affinity(0.9321d, 0.0679d, List.of("mode_energy_match")), 0.91d).orElseThrow(),
            service.evaluate(profile("heavy", 0.82d), usableCandidate(), affinity(0.70d, 0.10d, List.of("mode_energy_match")), 0.80d).orElseThrow()
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
```

- [ ] **Step 2: Run tests to verify RED**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.TasteModeAffinityGateServiceTest -PbuildDir=/tmp/my-forever-music-api-build
```

Expected: FAIL because `TasteModeAffinityGateService` does not exist.

- [ ] **Step 3: Add gate evaluator implementation**

Create `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/TasteModeAffinityGateService.java`:

```java
package io.myforevermusic.api.modules.recommendation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TasteModeAffinityGateService {

    private static final String PROFILE_HEAVY = "heavy";
    private static final String STATUS_NOT_APPLICABLE = "not_applicable";
    private static final String STATUS_BLOCKED = "blocked";
    private static final String STATUS_ELIGIBLE = "eligible";
    private static final String STATUS_DRY_RUN = "dry_run";
    private static final String REASON_ELIGIBLE = "eligible";

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
        this.objectMapper = objectMapper;
        this.gateEnabled = gateEnabled;
        this.dryRunEnabled = dryRunEnabled;
        this.applyRankingBoost = applyRankingBoost;
        this.minProfileConfidence = minProfileConfidence;
        this.minSimilarity = minSimilarity;
        this.maxDistance = maxDistance;
        this.maxBoostWeight = maxBoostWeight;
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

        List<String> reasonTokens = new ArrayList<>();
        String blockingReason = firstBlockingReason(profile, candidate, affinity, reasonTokens);
        if (blockingReason != null) {
            String status = blockingReason.startsWith("missing_")
                || "profile_not_heavy".equals(blockingReason)
                || "candidate_audio_unusable".equals(blockingReason)
                ? STATUS_NOT_APPLICABLE
                : STATUS_BLOCKED;
            return Optional.of(new GateResult(
                status,
                blockingReason,
                List.copyOf(reasonTokens),
                null,
                null,
                null
            ));
        }

        reasonTokens.add(REASON_ELIGIBLE);
        reasonTokens.addAll(affinity.tokens());

        double suggestedBoostWeight = round(clamp(profile.profileConfidence()) * maxBoostWeight);
        if (!dryRunEnabled || !finite(currentScore)) {
            return Optional.of(new GateResult(
                STATUS_ELIGIBLE,
                REASON_ELIGIBLE,
                List.copyOf(reasonTokens),
                suggestedBoostWeight,
                null,
                null
            ));
        }

        double centeredAffinity = affinity.similarity() - 0.5d;
        double dryRunScore = roundScore(currentScore * (1.0d + (suggestedBoostWeight * centeredAffinity)));
        double dryRunDelta = roundScore(dryRunScore - currentScore);
        return Optional.of(new GateResult(
            STATUS_DRY_RUN,
            REASON_ELIGIBLE,
            List.copyOf(reasonTokens),
            suggestedBoostWeight,
            dryRunScore,
            dryRunDelta
        ));
    }

    public GateSummary summarize(List<GateResult> results) {
        List<GateResult> safeResults = results == null ? List.of() : results;
        Map<String, Long> reasonCounts = new LinkedHashMap<>();
        for (GateResult result : safeResults) {
            reasonCounts.merge(result.reason(), 1L, Long::sum);
        }
        return new GateSummary(
            gateEnabled,
            dryRunEnabled,
            applyRankingBoost,
            safeResults.size(),
            countStatus(safeResults, STATUS_ELIGIBLE) + countStatus(safeResults, STATUS_DRY_RUN),
            countStatus(safeResults, STATUS_DRY_RUN),
            countStatus(safeResults, STATUS_BLOCKED),
            countStatus(safeResults, STATUS_NOT_APPLICABLE),
            maxDelta(safeResults, true),
            maxDelta(safeResults, false),
            Map.copyOf(reasonCounts)
        );
    }

    public String toAuditJson(GateSummary summary) {
        try {
            return objectMapper.writeValueAsString(auditPayload(summary));
        } catch (JsonProcessingException exception) {
            return "{\"gate_enabled\":%s,\"serialization_error\":\"%s\"}".formatted(
                gateEnabled,
                exception.getClass().getSimpleName()
            );
        }
    }

    private Map<String, Object> auditPayload(GateSummary summary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (summary == null) {
            payload.put("gate_enabled", gateEnabled);
            payload.put("dry_run_enabled", dryRunEnabled);
            payload.put("apply_ranking_boost", applyRankingBoost);
            payload.put("evaluated_count", 0);
            payload.put("eligible_count", 0);
            payload.put("dry_run_count", 0);
            payload.put("blocked_count", 0);
            payload.put("not_applicable_count", 0);
            payload.put("max_positive_delta", 0.0d);
            payload.put("max_negative_delta", 0.0d);
            payload.put("reason_counts", Map.of());
            return payload;
        }
        payload.put("gate_enabled", summary.gateEnabled());
        payload.put("dry_run_enabled", summary.dryRunEnabled());
        payload.put("apply_ranking_boost", summary.applyRankingBoost());
        payload.put("evaluated_count", summary.evaluatedCount());
        payload.put("eligible_count", summary.eligibleCount());
        payload.put("dry_run_count", summary.dryRunCount());
        payload.put("blocked_count", summary.blockedCount());
        payload.put("not_applicable_count", summary.notApplicableCount());
        payload.put("max_positive_delta", summary.maxPositiveDelta());
        payload.put("max_negative_delta", summary.maxNegativeDelta());
        payload.put("reason_counts", summary.reasonCounts());
        return payload;
    }

    private String firstBlockingReason(
        AudioTasteProfileService.Profile profile,
        AudioTasteTrackFeature candidate,
        AudioTasteModeAffinityService.TasteModeAffinity affinity,
        List<String> reasonTokens
    ) {
        if (profile == null) {
            reasonTokens.add("missing_profile");
            return "missing_profile";
        }
        if (!PROFILE_HEAVY.equals(profile.profileType())) {
            reasonTokens.add("profile_not_heavy");
            return "profile_not_heavy";
        }
        if (affinity == null) {
            reasonTokens.add("missing_affinity");
            return "missing_affinity";
        }
        if (candidate == null || !candidate.usable()) {
            reasonTokens.add("candidate_audio_unusable");
            return "candidate_audio_unusable";
        }
        if (profile.profileConfidence() < minProfileConfidence) {
            reasonTokens.add("low_profile_confidence");
            return "low_profile_confidence";
        }
        if (affinity.similarity() < minSimilarity) {
            reasonTokens.add("low_mode_similarity");
            return "low_mode_similarity";
        }
        if (affinity.distance() > maxDistance) {
            reasonTokens.add("large_mode_distance");
            return "large_mode_distance";
        }
        List<String> tokens = affinity.tokens() == null ? List.of() : affinity.tokens();
        if (tokens.isEmpty() || (tokens.size() == 1 && "mode_profile_distance".equals(tokens.getFirst()))) {
            reasonTokens.add("weak_token_match");
            return "weak_token_match";
        }
        return null;
    }

    private int countStatus(List<GateResult> results, String status) {
        return (int) results.stream().filter(result -> status.equals(result.status())).count();
    }

    private Double maxDelta(List<GateResult> results, boolean positive) {
        var deltas = results.stream()
            .map(GateResult::dryRunDelta)
            .filter(this::finite)
            .filter(delta -> positive ? delta > 0.0d : delta < 0.0d)
            .mapToDouble(Double::doubleValue);
        OptionalDouble selectedDelta = positive ? deltas.max() : deltas.min();
        return selectedDelta
            .stream()
            .map(this::roundScore)
            .boxed()
            .findFirst()
            .orElse(0.0d);
    }

    private boolean finite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private double round(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
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
        Map<String, Long> reasonCounts
    ) {
        public GateSummary {
            reasonCounts = reasonCounts == null ? Map.of() : Map.copyOf(reasonCounts);
        }
    }
}
```

- [ ] **Step 4: Run gate service tests to verify GREEN**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.TasteModeAffinityGateServiceTest -PbuildDir=/tmp/my-forever-music-api-build
```

Expected: PASS.

- [ ] **Step 5: Commit gate service**

Run:

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/TasteModeAffinityGateService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/TasteModeAffinityGateServiceTest.java
git commit -m "feat: add taste mode affinity gate evaluator"
```

---

### Task 2: Extend Response and Audit Contracts

**Files:**
- Create: `services/api/src/main/resources/db/migration/V46__add_taste_mode_gate_summary_to_recommendation_audit_log.sql`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/gms/presentation/GmsRecommendationPreviewResponse.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/RecommendationAuditLogStore.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/local/InMemoryRecommendationAuditLogStore.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/persistence/RecommendationAuditLogEntity.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/RecommendationAuditLogAdminController.java`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java`

- [ ] **Step 1: Add failing serialization assertion**

In `services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java`, add this assertion to `shouldSerializeTasteModeAffinityOnPreviewItem` after existing `tasteModeAffinity` assertions:

```java
assertThat(json).contains("\"taste_mode_gate\"");
assertThat(json).contains("\"status\":\"dry_run\"");
assertThat(json).contains("\"dry_run_delta\":0.0084");
```

Then update the item constructor in that test to pass a gate item after `TasteModeAffinityItem.from(...)`:

```java
new GmsRecommendationPreviewResponse.TasteModeGateItem(
    "dry_run",
    "eligible",
    List.of("eligible", "mode_energy_match"),
    0.018d,
    0.9184d,
    0.0084d
)
```

- [ ] **Step 2: Run test to verify RED**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest.shouldSerializeTasteModeAffinityOnPreviewItem -PbuildDir=/tmp/my-forever-music-api-build
```

Expected: FAIL because `TasteModeGateItem` and the extra constructor field do not exist.

- [ ] **Step 3: Extend GMS preview item response record**

In `services/api/src/main/java/io/myforevermusic/api/modules/gms/presentation/GmsRecommendationPreviewResponse.java`, add `TasteModeGateItem tasteModeGate` after `TasteModeAffinityItem tasteModeAffinity` in `RecommendationItem`.

Update all canonical constructor delegations by adding `null` after the existing affinity argument.

Update `withAxisEvidence` to preserve both fields:

```java
evidence == null ? List.of() : evidence,
tasteModeAffinity,
tasteModeGate
```

Update `withTasteModeAffinity` to preserve gate:

```java
axisEvidence,
affinity,
tasteModeGate
```

Add:

```java
public RecommendationItem withTasteModeGate(TasteModeGateItem gate) {
    return new RecommendationItem(
        rank,
        trackId,
        title,
        artistName,
        sourcePlatform,
        sourcePlaylistId,
        sourcePlaylistTitle,
        albumTitle,
        albumImageUrl,
        platformExternalUrl,
        platformUri,
        previewUrl,
        spotifyTrackId,
        audioFeatureTrackId,
        durationMs,
        score,
        sourceSpace,
        energyLevel,
        reason,
        axisEvidence,
        tasteModeAffinity,
        gate
    );
}
```

Add the nested response item:

```java
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TasteModeGateItem(
    String status,
    String reason,
    List<String> reasonTokens,
    Double suggestedBoostWeight,
    Double dryRunScore,
    Double dryRunDelta
) {
    public TasteModeGateItem {
        reasonTokens = reasonTokens == null ? List.of() : List.copyOf(reasonTokens);
    }

    public static TasteModeGateItem from(TasteModeAffinityGateService.GateResult gate) {
        if (gate == null) {
            return null;
        }
        return new TasteModeGateItem(
            gate.status(),
            gate.reason(),
            gate.reasonTokens(),
            gate.suggestedBoostWeight(),
            gate.dryRunScore(),
            gate.dryRunDelta()
        );
    }
}
```

Also import:

```java
import io.myforevermusic.api.modules.recommendation.application.TasteModeAffinityGateService;
```

- [ ] **Step 4: Add audit migration**

Create `services/api/src/main/resources/db/migration/V46__add_taste_mode_gate_summary_to_recommendation_audit_log.sql`:

```sql
ALTER TABLE recommendation_audit_log
    ADD COLUMN taste_mode_gate_summary TEXT;
```

- [ ] **Step 5: Extend audit store records**

In `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/RecommendationAuditLogStore.java`, add `String tasteModeGateSummary` immediately before `Instant createdAt` in both `AuditDraft` and `StoredAuditLog`.

Update both call sites:

In `GmsRecommendationFeedbackService.recordFeedbackAudit(...)`, add `null` before `storedFeedback.createdAt()`.

In `GmsRecommendationPreviewService.recordPreviewAudit(...)`, this argument will be wired in Task 3. Until then pass `null` before the timestamp.

- [ ] **Step 6: Extend local and JPA audit stores**

In `RecommendationAuditLogEntity`, add:

```java
@Column(name = "taste_mode_gate_summary", columnDefinition = "TEXT")
private String tasteModeGateSummary;
```

Set it in the constructor:

```java
this.tasteModeGateSummary = draft.tasteModeGateSummary();
```

Return it in `toState()` immediately before `createdAt`.

In `InMemoryRecommendationAuditLogStore`, update `new StoredAuditLog(...)` to include `draft.tasteModeGateSummary()` immediately before created time.

- [ ] **Step 7: Extend audit admin response**

In `RecommendationAuditLogAdminController.RecommendationAuditLogItem`, add:

```java
String tasteModeGateSummary,
```

Map it in `from(...)`:

```java
entry.tasteModeGateSummary(),
```

- [ ] **Step 8: Run response/audit focused tests**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest.shouldSerializeTasteModeAffinityOnPreviewItem --tests io.myforevermusic.api.modules.recommendation.presentation.RecommendationAuditLogAdminControllerWebMvcTest -PbuildDir=/tmp/my-forever-music-api-build
```

Expected: PASS.

- [ ] **Step 9: Commit contract changes**

Run:

```bash
git add services/api/src/main/resources/db/migration/V46__add_taste_mode_gate_summary_to_recommendation_audit_log.sql \
  services/api/src/main/java/io/myforevermusic/api/modules/gms/presentation/GmsRecommendationPreviewResponse.java \
  services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/RecommendationAuditLogStore.java \
  services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/local/InMemoryRecommendationAuditLogStore.java \
  services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/persistence/RecommendationAuditLogEntity.java \
  services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/RecommendationAuditLogAdminController.java \
  services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java
git commit -m "feat: extend taste mode gate response and audit contract"
```

---

### Task 3: Integrate Gate Into GMS Preview Without Ranking Impact

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewService.java`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java`

- [ ] **Step 1: Add failing GMS preview gate test**

In `GmsRecommendationPreviewServiceTest`, add:

```java
@Test
void shouldAttachTasteModeGateDryRunWithoutChangingRankingAndStoreAuditSummary() {
    InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
    InMemoryPmsUserLibraryStore pmsUserLibraryStore = new InMemoryPmsUserLibraryStore();
    InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
    InMemoryRecommendationAuditLogStore auditLogStore = new InMemoryRecommendationAuditLogStore();
    pmsUserLibraryStore.savePlaylists(
        "taste-mode-user",
        List.of(sampleHeavyLibraryPlaylistForTasteModeAffinity())
    );
    for (int index = 1; index <= 60; index++) {
        eventStore.save(audioTasteEvent(
            "taste-mode-user",
            "track-heavy-high-%03d".formatted(index),
            index
        ));
    }
    AudioTasteProfileService audioTasteProfileService = new AudioTasteProfileService(
        pmsUserLibraryStore,
        eventStore,
        new InMemoryTrackAudioFeatureEvidenceStore(),
        new EventSignalWeights()
    );
    GmsRecommendationPreviewService service = new GmsRecommendationPreviewService(
        new TwoItemAiRecommendationPreviewClient(),
        Optional.empty(),
        authAccountStore,
        new InMemoryLastFmScrobbleStore(),
        pmsUserLibraryStore,
        Optional.empty(),
        new RecommendationSnapshotService(new InMemoryRecommendationSnapshotStore()),
        auditLogStore,
        new PlaylistQualityEvaluator(),
        new InMemoryUserPersonalizationProfileStore(),
        new RecommendationReranker(),
        audioTasteProfileService,
        new AudioTasteScoringService(),
        new AudioTasteModeAffinityService(),
        new TasteModeAffinityGateService(new ObjectMapper(), true, true, false, 0.55d, 0.82d, 0.18d, 0.03d),
        new ColdStartFallbackService(authAccountStore, pmsUserLibraryStore, Optional.empty())
    );

    GmsRecommendationPreviewResponse response = service.previewRecommendations(
        tasteModeAffinityRequest("request-taste-mode-gate", true)
    );

    assertThat(response.items()).hasSize(2);
    assertThat(response.items())
        .extracting(GmsRecommendationPreviewResponse.RecommendationItem::trackId)
        .containsExactly("track-heavy-high-001", "track-heavy-high-002");
    assertThat(response.items()).allSatisfy(item -> {
        assertThat(item.tasteModeAffinity()).isNotNull();
        assertThat(item.tasteModeGate()).isNotNull();
        assertThat(item.tasteModeGate().status()).isEqualTo("dry_run");
        assertThat(item.tasteModeGate().dryRunDelta()).isNotNull();
    });
    assertThat(response.warnings()).anyMatch(warning ->
        warning.contains("Taste mode affinity gate dry-run")
            && warning.contains("dry_run=2")
    );
    assertThat(auditLogStore.findRecentByUserId("taste-mode-user", 1).getFirst().tasteModeGateSummary())
        .contains("\"dry_run_count\":2")
        .contains("\"apply_ranking_boost\":false");
}
```

Update all existing `new GmsRecommendationPreviewService(...)` calls in the test to pass:

```java
new TasteModeAffinityGateService(new ObjectMapper(), true, true, false, 0.55d, 0.82d, 0.18d, 0.03d),
```

immediately before `new ColdStartFallbackService(...)`.

- [ ] **Step 2: Run test to verify RED**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest.shouldAttachTasteModeGateDryRunWithoutChangingRankingAndStoreAuditSummary -PbuildDir=/tmp/my-forever-music-api-build
```

Expected: FAIL because `GmsRecommendationPreviewService` does not inject or attach gate results yet.

- [ ] **Step 3: Inject gate service**

In `GmsRecommendationPreviewService`, add:

```java
import io.myforevermusic.api.modules.recommendation.application.TasteModeAffinityGateService;
```

Add a field:

```java
private final TasteModeAffinityGateService tasteModeAffinityGateService;
```

Add constructor parameter immediately after `AudioTasteModeAffinityService audioTasteModeAffinityService`:

```java
TasteModeAffinityGateService tasteModeAffinityGateService,
```

Assign it:

```java
this.tasteModeAffinityGateService = tasteModeAffinityGateService;
```

- [ ] **Step 4: Carry gate results through preview generation**

In `previewRecommendations`, create:

```java
List<TasteModeAffinityGateService.GateResult> tasteModeGateResults = new ArrayList<>();
```

Pass it to `projectPlayableItems(...)`.

Update `projectPlayableItems` signature to accept:

```java
List<TasteModeAffinityGateService.GateResult> tasteModeGateResults
```

and pass it to `applyTasteModeAffinity(...)`.

Update `applyTasteModeAffinity` signature to accept the same list.

- [ ] **Step 5: Attach item-level gate result**

Extend `RankedLibraryCandidate` with:

```java
TasteModeAffinityGateService.GateResult tasteModeGate
```

Update all constructors and copy methods to preserve this field.

Add:

```java
private RankedLibraryCandidate withTasteModeGate(
    TasteModeAffinityGateService.GateResult nextTasteModeGate
) {
    return new RankedLibraryCandidate(
        candidate,
        affinityScore,
        sasrecRanked,
        audioTasteRanked,
        audioTasteTokens,
        tasteModeAffinity,
        nextTasteModeGate
    );
}
```

In `applyTasteModeAffinity`, replace the mapping body with:

```java
return rankedCandidates.stream()
    .map(ranked -> {
        AudioTasteTrackFeature feature = toAudioTasteTrackFeature(ranked.candidate());
        return audioTasteModeAffinityService
            .findNearestMode(profile, feature)
            .map(affinity -> {
                RankedLibraryCandidate withAffinity = ranked.withTasteModeAffinity(affinity);
                Optional<TasteModeAffinityGateService.GateResult> gate =
                    tasteModeAffinityGateService.evaluate(profile, feature, affinity, ranked.affinityScore());
                gate.ifPresent(tasteModeGateResults::add);
                return gate
                    .map(withAffinity::withTasteModeGate)
                    .orElse(withAffinity);
            })
            .orElse(ranked);
    })
    .toList();
```

In `toRecommendationItem`, pass gate item after affinity:

```java
GmsRecommendationPreviewResponse.TasteModeAffinityItem.from(rankedCandidate.tasteModeAffinity()),
GmsRecommendationPreviewResponse.TasteModeGateItem.from(rankedCandidate.tasteModeGate())
```

- [ ] **Step 6: Add gate warning and audit summary**

Add helper:

```java
private GmsRecommendationPreviewResponse withTasteModeGateWarning(
    GmsRecommendationPreviewResponse response,
    GmsRecommendationPreviewRequest request,
    List<TasteModeAffinityGateService.GateResult> gateResults
) {
    if ((gateResults == null || gateResults.isEmpty()) && !request.includeExplanations()) {
        return response;
    }
    TasteModeAffinityGateService.GateSummary summary = tasteModeAffinityGateService.summarize(
        gateResults == null ? List.of() : gateResults
    );
    List<String> mergedWarnings = new ArrayList<>(response.warnings());
    mergedWarnings.add(
        "Taste mode affinity gate dry-run: gate_enabled=%s, evaluated=%d, eligible=%d, dry_run=%d, blocked=%d, ranking_impact=none."
            .formatted(
                summary.gateEnabled(),
                summary.evaluatedCount(),
                summary.eligibleCount(),
                summary.dryRunCount(),
                summary.blockedCount()
            )
    );
    return new GmsRecommendationPreviewResponse(
        response.requestId(),
        response.generatedAt(),
        response.service(),
        response.status(),
        response.context(),
        response.inputSummary(),
        response.items(),
        List.copyOf(mergedWarnings)
    );
}
```

Call it after `withAxisEvidence(...)` and before snapshot/audit in both branches:

```java
finalResponse = withTasteModeGateWarning(finalResponse, enrichedRequest, tasteModeGateResults);
```

Update `recordPreviewAudit` signature:

```java
private void recordPreviewAudit(
    GmsRecommendationPreviewRequest request,
    GmsRecommendationPreviewResponse response,
    List<TasteModeAffinityGateService.GateResult> gateResults
)
```

Update both existing call sites:

```java
recordPreviewAudit(enrichedRequest, finalResponse, tasteModeGateResults);
```

Inside it:

```java
boolean shouldStoreGateSummary = request.includeExplanations()
    || (gateResults != null && !gateResults.isEmpty());
String tasteModeGateSummary = shouldStoreGateSummary
    ? tasteModeAffinityGateService.toAuditJson(
        tasteModeAffinityGateService.summarize(gateResults == null ? List.of() : gateResults)
    )
    : null;
```

Pass `tasteModeGateSummary` into `AuditDraft` immediately before created time.

- [ ] **Step 7: Run GMS preview tests**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest -PbuildDir=/tmp/my-forever-music-api-build
```

Expected: PASS. Existing taste-mode affinity test still confirms rank/score/context stability, and the new test confirms gate dry-run/audit output.

- [ ] **Step 8: Commit GMS integration**

Run:

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java
git commit -m "feat: attach taste mode gate dry run to gms preview"
```

---

### Task 4: Surface Gate Details in Web GMS Preview

**Files:**
- Modify: `apps/web/src/types/api.ts`
- Modify: `apps/web/src/pages/GmsPreviewPage.tsx`
- Modify: `apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts`

- [ ] **Step 1: Extend failing Playwright assertions**

In `apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts`, add `taste_mode_gate` to the first mock item:

```ts
taste_mode_gate: {
    status: 'dry_run',
    reason: 'eligible',
    reason_tokens: ['eligible', 'mode_energy_match', 'mode_valence_match'],
    suggested_boost_weight: 0.018,
    dry_run_score: 0.9184,
    dry_run_delta: 0.0084,
},
```

Replace the second mock item's `taste_mode_affinity: null` with a blocked affinity plus gate. This keeps the blocked gate inside the existing affinity panel render condition:

```ts
taste_mode_affinity: {
    applied: true,
    mode_id: 'mode-low-similarity',
    label: 'low_similarity_sparse_match',
    similarity: 0.71,
    distance: 0.29,
    tokens: ['mode_profile_distance'],
},
taste_mode_gate: {
    status: 'blocked',
    reason: 'low_mode_similarity',
    reason_tokens: ['low_mode_similarity'],
    suggested_boost_weight: null,
    dry_run_score: null,
    dry_run_delta: null,
},
```

Append a third mock item to the `items` array so the test still covers the no-affinity/no-panel case:

```ts
{
    rank: 3,
    track_id: 'track-affinity-003',
    title: 'Quiet Static',
    artist_name: 'No Mode',
    source_platform: 'spotify',
    source_playlist_id: 'playlist-quiet',
    source_playlist_title: 'Quiet Drift',
    album_title: 'Quiet Album',
    album_image_url: null,
    platform_external_url: null,
    platform_uri: null,
    preview_url: null,
    spotify_track_id: null,
    audio_feature_track_id: null,
    duration_ms: 180000,
    score: 0.42,
    source_space: 'audio_taste',
    energy_level: 'low',
    reason: 'No nearest taste mode in this fixture.',
    axis_evidence: [],
    taste_mode_affinity: null,
    taste_mode_gate: null,
},
```

After existing affinity assertions, add:

```ts
await expect(page.getByText('Taste mode')).toHaveCount(2)
await expect(page.getByText('Gate dry run')).toBeVisible()
await expect(page.getByText('+0.0084')).toBeVisible()
await expect(page.getByText('ranking unchanged')).toBeVisible()
await expect(page.getByText('blocked')).toBeVisible()
await expect(page.getByText('low_mode_similarity')).toBeVisible()
await expect(page.getByText('Quiet Static')).toBeVisible()
```

- [ ] **Step 2: Run Playwright test to verify RED**

Run:

```bash
cd apps/web
npm run test:e2e -- tests/e2e/gms-preview-taste-mode-affinity.spec.ts
```

Expected: FAIL because the web app does not render `taste_mode_gate` yet. In the sandbox, this command may need escalation because Vite opens `127.0.0.1:5173`.

- [ ] **Step 3: Add frontend API type**

In `apps/web/src/types/api.ts`, add:

```ts
export interface GmsTasteModeGate {
    status: 'not_applicable' | 'blocked' | 'eligible' | 'dry_run' | string
    reason: string
    reason_tokens?: string[] | null
    suggested_boost_weight?: number | null
    dry_run_score?: number | null
    dry_run_delta?: number | null
}
```

Add optional field to each GMS preview item:

```ts
taste_mode_gate?: GmsTasteModeGate | null
```

- [ ] **Step 4: Render gate details inside affinity panel**

In `GmsPreviewPage.tsx`, add helpers near the existing affinity helpers:

```tsx
const formatGateDelta = (value: number | null | undefined) => {
    if (typeof value !== 'number' || !Number.isFinite(value)) {
        return 'n/a'
    }
    return `${value >= 0 ? '+' : ''}${value.toFixed(4)}`
}

const gateReasonTokens = (tokens: string[] | null | undefined) =>
    tokens?.filter((token) => token.trim().length > 0) ?? []
```

Update `TasteModeAffinityPanelProps`:

```tsx
type TasteModeAffinityPanelProps = {
    affinity: NonNullable<GmsRecommendationPreviewResponse['items'][number]['taste_mode_affinity']>
    gate?: GmsRecommendationPreviewResponse['items'][number]['taste_mode_gate']
}
```

Inside `TasteModeAffinityPanel`, derive:

```tsx
const reasonTokens = gateReasonTokens(gate?.reason_tokens)
const gateTone =
    gate?.status === 'dry_run'
        ? 'border-emerald-300/30 bg-emerald-300/10 text-emerald-100'
        : gate?.status === 'blocked'
            ? 'border-amber-300/30 bg-amber-300/10 text-amber-100'
            : 'border-hud-border-secondary bg-hud-bg-primary/60 text-hud-text-secondary'
```

Render below token chips:

```tsx
{gate && (
    <div className={`mt-3 rounded-lg border px-3 py-2 text-xs ${gateTone}`}>
        <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="font-semibold">
                {gate.status === 'dry_run' ? 'Gate dry run' : gate.status}
            </span>
            {gate.status === 'dry_run' && (
                <span>{formatGateDelta(gate.dry_run_delta)}</span>
            )}
        </div>
        <p className="mt-1 text-[11px] text-hud-text-muted">
            {gate.reason}
            {gate.status === 'dry_run' ? ' · ranking unchanged' : ''}
        </p>
        {reasonTokens.length > 0 && (
            <div className="mt-2 flex flex-wrap gap-1.5">
                {reasonTokens.map((token) => (
                    <span
                        key={`${affinity.mode_id}-gate-${token}`}
                        className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary/60 px-2 py-0.5 text-[10px] text-hud-text-secondary"
                    >
                        {token}
                    </span>
                ))}
            </div>
        )}
    </div>
)}
```

Update render call:

```tsx
<TasteModeAffinityPanel affinity={item.taste_mode_affinity} gate={item.taste_mode_gate} />
```

- [ ] **Step 5: Run focused Playwright test**

Run:

```bash
cd apps/web
npm run test:e2e -- tests/e2e/gms-preview-taste-mode-affinity.spec.ts
```

Expected: PASS.

- [ ] **Step 6: Run frontend build**

Run:

```bash
cd apps/web
npm run build
```

Expected: PASS.

- [ ] **Step 7: Commit web UI**

Run:

```bash
git add apps/web/src/types/api.ts apps/web/src/pages/GmsPreviewPage.tsx apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts
git commit -m "feat: show taste mode gate dry run in gms preview"
```

---

### Task 5: Document Contract and Final Verification

**Files:**
- Modify: `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`
- Review: all files changed in Tasks 1-4

- [ ] **Step 1: Document gate contract**

In `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`, add a short section after the existing `taste_mode_affinity` rules:

````markdown
### Taste mode affinity operational gate

`include_explanations=true` may also return `items[].taste_mode_gate` when a heavy profile candidate has `taste_mode_affinity`.

`taste_mode_gate` is an operational dry-run signal. It never changes `rank`, `score`, or item order in this phase.

```json
{
  "taste_mode_gate": {
    "status": "dry_run",
    "reason": "eligible",
    "reason_tokens": ["eligible", "mode_energy_match"],
    "suggested_boost_weight": 0.018,
    "dry_run_score": 0.9184,
    "dry_run_delta": 0.0084
  }
}
```

Statuses:

| Status | Meaning |
| --- | --- |
| `not_applicable` | gate could not evaluate this item |
| `blocked` | affinity exists but operating thresholds rejected it |
| `eligible` | thresholds passed but dry-run score was not calculated |
| `dry_run` | thresholds passed and hypothetical score delta was calculated |

The preview audit log stores a request-level `taste_mode_gate_summary` payload with evaluated, eligible, dry-run, blocked, and reason count totals.
````

- [ ] **Step 2: Run backend focused tests**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.TasteModeAffinityGateServiceTest --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest -PbuildDir=/tmp/my-forever-music-api-build
```

Expected: PASS.

- [ ] **Step 3: Run frontend focused test and build**

Run:

```bash
cd apps/web
npm run test:e2e -- tests/e2e/gms-preview-taste-mode-affinity.spec.ts
npm run build
```

Expected: PASS. Existing Vite chunk-size warning can remain.

- [ ] **Step 4: Run diff check**

Run:

```bash
cd /srv/my-forever-music
git diff --check
git status --short --branch
```

Expected: no whitespace errors. Existing untracked `.idea/`, `.superpowers/`, and `hud-theme/` may remain untracked and must not be staged.

- [ ] **Step 5: Commit docs and verification-ready state**

Run:

```bash
git add docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md
git commit -m "docs: document taste mode gate dry run contract"
```

If Task 5 only changes docs, this is the final commit. If Task 1-4 commits already include all implementation, do not squash them unless the user explicitly asks.
