# Taste Mode Affinity Ranking Boost Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Promote gated `taste_mode_affinity` from dry-run evidence into an optional conservative GMS preview ranking boost.

**Architecture:** Keep the ranking promotion inside Spring API. Extend `TasteModeAffinityGateService` with a focused boost calculation and audit metrics, then wire GMS preview to apply that boost only when `app.recommendation.taste-mode-affinity.apply-ranking-boost=true`. The default remains dry-run only.

**Tech Stack:** Spring Boot 3.5, Java 21 records/services, JUnit 5, AssertJ, Gradle, Flyway-compatible existing audit field.

---

## File Structure

- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/TasteModeAffinityGateService.java`
  - Owns gate eligibility, boost score calculation, summary/audit JSON.
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/TasteModeAffinityGateServiceTest.java`
  - Covers boost eligibility, disabled/default behavior, audit summary metrics.
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewService.java`
  - Applies boost after `audio-taste:v1`, before final projection, with stable sorting and context/warning/audit evidence.
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java`
  - Covers flag false stability, flag true score/rank effect, warning/context/audit.
- Modify: `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`
  - Documents enabled ranking mode and audit fields.

No schema migration is required. `recommendation_audit_log.taste_mode_gate_summary` already exists and can carry the expanded JSON payload.

---

### Task 1: Extend Gate Service With Boost Metrics

**Files:**
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/TasteModeAffinityGateServiceTest.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/TasteModeAffinityGateService.java`

- [ ] **Step 1: Add failing tests for boost calculation and audit metrics**

Append these tests before helper methods in `TasteModeAffinityGateServiceTest.java`:

```java
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
```

- [ ] **Step 2: Run gate service tests to verify RED**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.TasteModeAffinityGateServiceTest -PbuildDir=/tmp/my-forever-music-api-taste-mode-boost-task1-red
```

Expected: FAIL because `BoostResult`, `BoostMetrics`, overloaded `summarize(...)`, and `applyRankingBoost(...)` do not exist.

- [ ] **Step 3: Add boost APIs and audit fields**

In `TasteModeAffinityGateService.java`, add this public method after `evaluate(...)`:

```java
    public Optional<BoostResult> applyRankingBoost(GateResult result, Double currentScore, Double similarity) {
        if (!applyRankingBoost || !eligibleResult(result) || result.suggestedBoostWeight() == null) {
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
```

Replace `summarize(List<GateResult> results)` with a delegating overload plus metrics-aware overload:

```java
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
```

In `toAuditJson(...)`, add these payload fields after `max_negative_delta`:

```java
        audit.put("boost_applied_count", safeSummary.boostAppliedCount());
        audit.put("rank_changed_count", safeSummary.rankChangedCount());
```

Add these records before `GateSummary`:

```java
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
```

Update `GateSummary` constructor parameters to include:

```java
        int boostAppliedCount,
        int rankChangedCount,
```

Place them before `Map<String, Long> reasonCounts`.

- [ ] **Step 4: Run gate service tests to verify GREEN**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.TasteModeAffinityGateServiceTest -PbuildDir=/tmp/my-forever-music-api-taste-mode-boost-task1-green
```

Expected: PASS.

- [ ] **Step 5: Commit gate service changes**

Run:

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/TasteModeAffinityGateService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/application/TasteModeAffinityGateServiceTest.java
git commit -m "feat: add taste mode affinity boost metrics"
```

---

### Task 2: Apply Boost In GMS Preview

**Files:**
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewService.java`

- [ ] **Step 1: Add test helper for boost-enabled gate service**

In `GmsRecommendationPreviewServiceTest.java`, replace the existing `tasteModeAffinityGateService()` helper with:

```java
    private TasteModeAffinityGateService tasteModeAffinityGateService() {
        return tasteModeAffinityGateService(false, 0.03d);
    }

    private TasteModeAffinityGateService tasteModeAffinityGateService(boolean applyRankingBoost, double maxBoostWeight) {
        return new TasteModeAffinityGateService(
            new ObjectMapper(),
            true,
            true,
            applyRankingBoost,
            0.55d,
            0.82d,
            0.18d,
            maxBoostWeight
        );
    }
```

- [ ] **Step 2: Add failing GMS preview boost test**

Append this test after `shouldAttachTasteModeGateDryRunWithoutChangingRankingAndStoreAuditSummary()`:

```java
    @Test
    void shouldApplyTasteModeAffinityBoostWhenEnabledAndStoreRankingImpact() {
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
            tasteModeAffinityGateService(true, 0.30d),
            new ColdStartFallbackService(authAccountStore, pmsUserLibraryStore, Optional.empty())
        );

        GmsRecommendationPreviewResponse response = service.previewRecommendations(
            tasteModeAffinityRequest("request-taste-mode-boost", false)
        );

        assertThat(response.items()).hasSize(2);
        assertThat(response.items())
            .extracting(GmsRecommendationPreviewResponse.RecommendationItem::tasteModeAffinity)
            .containsOnlyNulls();
        assertThat(response.items())
            .extracting(GmsRecommendationPreviewResponse.RecommendationItem::tasteModeGate)
            .containsOnlyNulls();
        assertThat(response.context().engine()).contains("taste-mode-affinity:v1");
        assertThat(response.warnings()).anyMatch(warning ->
            warning.contains("Taste mode affinity ranking boost")
                && warning.contains("ranking_impact=enabled")
                && warning.contains("applied=2")
        );
        assertThat(auditLogStore.findRecentByUserId("taste-mode-user", 1).getFirst().tasteModeGateSummary())
            .contains("\"apply_ranking_boost\":true")
            .contains("\"boost_applied_count\":2");
    }
```

This test intentionally uses `includeExplanations=false` to prove ranking and explanation rendering are independent.

- [ ] **Step 3: Run the new GMS test to verify RED**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest.shouldApplyTasteModeAffinityBoostWhenEnabledAndStoreRankingImpact -PbuildDir=/tmp/my-forever-music-api-taste-mode-boost-task2-red
```

Expected: FAIL because GMS preview does not evaluate taste mode affinity when explanations are off and does not apply ranking boost.

- [ ] **Step 4: Add boost state and context model version plumbing**

In `GmsRecommendationPreviewService.java`, add this local state near the top of `previewRecommendations(...)` where existing model version lists are created:

```java
        List<String> appliedTasteModeAffinityModelVersions = new ArrayList<>();
        TasteModeBoostAuditState tasteModeBoostAuditState = new TasteModeBoostAuditState();
```

Update both calls to `projectPlayableItems(...)` to pass `appliedTasteModeAffinityModelVersions` and `tasteModeBoostAuditState`.

Update the `applyModelContexts(...)` call to include the new list:

```java
            applyModelContexts(
                response.context(),
                appliedSasrecModelVersions,
                appliedAudioTasteModelVersions,
                appliedTasteModeAffinityModelVersions
            ),
```

Update calls to `withTasteModeGateWarning(...)` and `recordPreviewAudit(...)` to pass `tasteModeBoostAuditState.metrics()`.

- [ ] **Step 5: Extend method signatures**

Update signatures in `GmsRecommendationPreviewService.java`:

```java
    private void recordPreviewAudit(
        GmsRecommendationPreviewRequest request,
        GmsRecommendationPreviewResponse response,
        List<TasteModeAffinityGateService.GateResult> gateResults,
        TasteModeAffinityGateService.BoostMetrics boostMetrics
    )
```

```java
    private GmsRecommendationPreviewResponse withTasteModeGateWarning(
        GmsRecommendationPreviewResponse response,
        GmsRecommendationPreviewRequest request,
        List<TasteModeAffinityGateService.GateResult> gateResults,
        TasteModeAffinityGateService.BoostMetrics boostMetrics
    )
```

```java
    private List<GmsRecommendationPreviewResponse.RecommendationItem> projectPlayableItems(
        GmsRecommendationPreviewRequest request,
        List<GmsRecommendationPreviewResponse.RecommendationItem> aiItems,
        List<String> enrichmentWarnings,
        List<String> appliedSasrecModelVersions,
        List<String> appliedAudioTasteModelVersions,
        List<String> appliedTasteModeAffinityModelVersions,
        List<TasteModeAffinityGateService.GateResult> tasteModeGateResults,
        TasteModeBoostAuditState tasteModeBoostAuditState
    )
```

```java
    private List<RankedLibraryCandidate> applyTasteModeAffinity(
        GmsRecommendationPreviewRequest request,
        List<RankedLibraryCandidate> rankedCandidates,
        AudioTasteProfileService.Profile profile,
        List<TasteModeAffinityGateService.GateResult> tasteModeGateResults,
        TasteModeBoostAuditState tasteModeBoostAuditState
    )
```

- [ ] **Step 6: Add taste mode context helper**

Replace `applyModelContexts(...)` with a four-list version:

```java
    private GmsRecommendationPreviewResponse.RecommendationContext applyModelContexts(
        GmsRecommendationPreviewResponse.RecommendationContext context,
        List<String> appliedSasrecModelVersions,
        List<String> appliedAudioTasteModelVersions,
        List<String> appliedTasteModeAffinityModelVersions
    ) {
        return applyTasteModeAffinityContext(
            applyAudioTasteContext(
                applySasrecContext(context, appliedSasrecModelVersions),
                appliedAudioTasteModelVersions
            ),
            appliedTasteModeAffinityModelVersions
        );
    }
```

Add this helper after `applyAudioTasteContext(...)`:

```java
    private GmsRecommendationPreviewResponse.RecommendationContext applyTasteModeAffinityContext(
        GmsRecommendationPreviewResponse.RecommendationContext context,
        List<String> appliedTasteModeAffinityModelVersions
    ) {
        if (appliedTasteModeAffinityModelVersions.isEmpty()) {
            return context;
        }

        String modelVersion = appliedTasteModeAffinityModelVersions.getLast();
        if (context == null) {
            return new GmsRecommendationPreviewResponse.RecommendationContext(
                "gms-hybrid-blend",
                "gms-baseline-v1+taste-mode-affinity:%s".formatted(modelVersion),
                "gms",
                null,
                null,
                List.of()
            );
        }
        String engine = context.engine();
        if (engine != null && engine.contains("taste-mode-affinity:%s".formatted(modelVersion))) {
            return context;
        }
        String nextEngine = engine == null || engine.isBlank()
            ? "gms-baseline-v1+taste-mode-affinity:%s".formatted(modelVersion)
            : "%s+taste-mode-affinity:%s".formatted(engine, modelVersion);
        return new GmsRecommendationPreviewResponse.RecommendationContext(
            context.strategy(),
            nextEngine,
            context.mode(),
            context.mood(),
            context.energyLevel(),
            context.seedBasis()
        );
    }
```

- [ ] **Step 7: Add stable candidate comparator**

Extract the existing sort comparator into a helper:

```java
    private Comparator<RankedLibraryCandidate> rankedCandidateComparator(GmsRecommendationPreviewRequest request) {
        return Comparator.comparingDouble(RankedLibraryCandidate::affinityScore).reversed()
            .thenComparing((RankedLibraryCandidate ranked) -> ranked.candidate().seed()).reversed()
            .thenComparing((RankedLibraryCandidate ranked) -> requestedPlaylistMatch(request.playlistId(), ranked.candidate())).reversed()
            .thenComparing(ranked -> ranked.candidate().sortOrder())
            .thenComparing(ranked -> ranked.candidate().trackId());
    }
```

In `applyAudioTasteRanking(...)`, replace the inline `.sorted(...)` comparator with:

```java
            .sorted(rankedCandidateComparator(request))
```

- [ ] **Step 8: Implement boost application inside `applyTasteModeAffinity(...)`**

Replace the early return guard:

```java
        if (!request.includeExplanations() || profile == null || profile.tasteModes().isEmpty()) {
            return rankedCandidates;
        }
```

with:

```java
        if (
            (!request.includeExplanations() && !tasteModeAffinityGateService.rankingBoostEnabled())
                || profile == null
                || profile.tasteModes().isEmpty()
        ) {
            return rankedCandidates;
        }
```

Then replace the stream body with this implementation:

```java
        List<String> beforeOrder = rankedCandidates.stream()
            .map(ranked -> ranked.candidate().trackId())
            .toList();
        List<RankedLibraryCandidate> evaluated = rankedCandidates.stream()
            .map(ranked -> {
                AudioTasteTrackFeature feature = toAudioTasteTrackFeature(ranked.candidate());
                return audioTasteModeAffinityService
                    .findNearestMode(profile, feature)
                    .map(affinity -> {
                        RankedLibraryCandidate next = request.includeExplanations()
                            ? ranked.withTasteModeAffinity(affinity)
                            : ranked;
                        Optional<TasteModeAffinityGateService.GateResult> gate =
                            tasteModeAffinityGateService.evaluate(profile, feature, affinity, ranked.affinityScore());
                        gate.ifPresent(tasteModeGateResults::add);
                        if (request.includeExplanations() && gate.isPresent()) {
                            next = next.withTasteModeGate(gate.get());
                        }
                        Optional<TasteModeAffinityGateService.BoostResult> boost = gate
                            .flatMap(result -> tasteModeAffinityGateService.applyRankingBoost(
                                result,
                                ranked.affinityScore(),
                                affinity.similarity()
                            ));
                        if (boost.isEmpty()) {
                            return next;
                        }
                        tasteModeBoostAuditState.incrementBoostApplied();
                        return next.withTasteModeAffinityBoostScore(clampScore(boost.get().score()));
                    })
                    .orElse(ranked);
            })
            .toList();

        if (tasteModeBoostAuditState.boostAppliedCount() == 0) {
            return evaluated;
        }

        List<RankedLibraryCandidate> reranked = evaluated.stream()
            .sorted(rankedCandidateComparator(request))
            .toList();
        tasteModeBoostAuditState.setRankChangedCount(countRankChanges(beforeOrder, reranked));
        return reranked;
```

Add `countRankChanges(...)` near the comparator helper:

```java
    private int countRankChanges(List<String> beforeOrder, List<RankedLibraryCandidate> afterCandidates) {
        if (beforeOrder == null || beforeOrder.isEmpty() || afterCandidates == null || afterCandidates.isEmpty()) {
            return 0;
        }
        int limit = Math.min(beforeOrder.size(), afterCandidates.size());
        int changed = 0;
        for (int index = 0; index < limit; index++) {
            if (!Objects.equals(beforeOrder.get(index), afterCandidates.get(index).candidate().trackId())) {
                changed++;
            }
        }
        return changed;
    }
```

- [ ] **Step 9: Add audit state class and ranked candidate score helper**

Add this class near the nested records at the bottom of `GmsRecommendationPreviewService.java`:

```java
    private static final class TasteModeBoostAuditState {
        private int boostAppliedCount;
        private int rankChangedCount;

        private void incrementBoostApplied() {
            boostAppliedCount++;
        }

        private int boostAppliedCount() {
            return boostAppliedCount;
        }

        private void setRankChangedCount(int rankChangedCount) {
            this.rankChangedCount = Math.max(0, rankChangedCount);
        }

        private TasteModeAffinityGateService.BoostMetrics metrics() {
            return new TasteModeAffinityGateService.BoostMetrics(boostAppliedCount, rankChangedCount);
        }
    }
```

Add this method inside `RankedLibraryCandidate` after `withTasteModeGate(...)`:

```java
        private RankedLibraryCandidate withTasteModeAffinityBoostScore(double nextAffinityScore) {
            return new RankedLibraryCandidate(
                candidate,
                nextAffinityScore,
                sasrecRanked,
                audioTasteRanked,
                audioTasteTokens,
                tasteModeAffinity,
                tasteModeGate
            );
        }
```

- [ ] **Step 10: Wire warning and audit metrics**

In `recordPreviewAudit(...)`, build the summary with metrics:

```java
        TasteModeAffinityGateService.BoostMetrics safeBoostMetrics = boostMetrics == null
            ? TasteModeAffinityGateService.BoostMetrics.empty()
            : boostMetrics;
        String tasteModeGateSummary = shouldStoreGateSummary
            ? tasteModeAffinityGateService.toAuditJson(
                tasteModeAffinityGateService.summarize(
                    gateResults == null ? List.of() : gateResults,
                    safeBoostMetrics
                )
            )
            : null;
```

In `withTasteModeGateWarning(...)`, use metrics and different warning text:

```java
        TasteModeAffinityGateService.BoostMetrics safeBoostMetrics = boostMetrics == null
            ? TasteModeAffinityGateService.BoostMetrics.empty()
            : boostMetrics;
        TasteModeAffinityGateService.GateSummary summary = tasteModeAffinityGateService.summarize(
            gateResults == null ? List.of() : gateResults,
            safeBoostMetrics
        );
        List<String> mergedWarnings = new ArrayList<>(response.warnings());
        if (summary.boostAppliedCount() > 0) {
            mergedWarnings.add(
                "Taste mode affinity ranking boost: gate_enabled=%s, evaluated=%d, eligible=%d, applied=%d, rank_changed=%d, blocked=%d, max_positive_delta=%.4f, max_negative_delta=%.4f, ranking_impact=enabled."
                    .formatted(
                        summary.gateEnabled(),
                        summary.evaluatedCount(),
                        summary.eligibleCount(),
                        summary.boostAppliedCount(),
                        summary.rankChangedCount(),
                        summary.blockedCount(),
                        summary.maxPositiveDelta(),
                        summary.maxNegativeDelta()
                    )
            );
        } else {
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
        }
```

Add `"v1"` to `appliedTasteModeAffinityModelVersions` when boost metrics show applied count:

```java
        if (tasteModeBoostAuditState.boostAppliedCount() > 0) {
            appliedTasteModeAffinityModelVersions.add("v1");
        }
```

Place that after `applyTasteModeAffinity(...)` returns in `projectPlayableItems(...)` and before final projection.

- [ ] **Step 11: Run focused GMS tests to verify GREEN**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest.shouldAttachTasteModeAffinityOnlyAsExplanationWithoutChangingRanking --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest.shouldAttachTasteModeGateDryRunWithoutChangingRankingAndStoreAuditSummary --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest.shouldApplyTasteModeAffinityBoostWhenEnabledAndStoreRankingImpact -PbuildDir=/tmp/my-forever-music-api-taste-mode-boost-task2-green
```

Expected: PASS.

- [ ] **Step 12: Commit GMS boost integration**

Run:

```bash
git add services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java
git commit -m "feat: apply taste mode affinity ranking boost"
```

---

### Task 3: Update API Documentation

**Files:**
- Modify: `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`

- [ ] **Step 1: Document enabled boost mode**

In `docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md`, update the `Taste mode affinity operational gate` section after the status table with:

```markdown
When `app.recommendation.taste-mode-affinity.apply-ranking-boost=true`, eligible `dry_run` or `eligible` gate results become a conservative ranking signal.

- The default remains `false`, so production starts in dry-run mode.
- The boost runs after `audio-taste:v1` and uses the same formula shown by dry-run.
- `context.engine` appends `+taste-mode-affinity:v1` only when at least one candidate is actually boosted.
- `warnings[]` uses `ranking_impact=enabled` and includes applied, rank-changed, blocked, max-positive-delta, and max-negative-delta counts.
- `taste_mode_gate_summary` includes `boost_applied_count` and `rank_changed_count`.
```

- [ ] **Step 2: Run markdown diff check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 3: Commit documentation update**

Run:

```bash
git add docs/api/AUDIO_TASTE_MODEL_ADMIN_API.md
git commit -m "docs: document taste mode affinity ranking boost"
```

---

### Task 4: Final Verification

**Files:**
- No source edits expected.

- [ ] **Step 1: Run full focused backend verification**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.application.TasteModeAffinityGateServiceTest --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest --tests io.myforevermusic.api.modules.recommendation.presentation.RecommendationAuditLogAdminControllerWebMvcTest -PbuildDir=/tmp/my-forever-music-api-taste-mode-boost-final
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run repository diff checks**

Run:

```bash
git diff --check
git status --short --branch
```

Expected:

- `git diff --check` prints no output.
- `git status --short --branch` shows only intentional branch/ahead state and pre-existing untracked `.idea/`, `.superpowers/`, `hud-theme/` if they are still present.

- [ ] **Step 3: Review commit log**

Run:

```bash
git log --oneline -6
```

Expected: latest commits include:

- `docs: document taste mode affinity ranking boost`
- `feat: apply taste mode affinity ranking boost`
- `feat: add taste mode affinity boost metrics`
- `docs: design taste mode affinity ranking boost`

Do not claim completion until every command in this task has been run and read.
