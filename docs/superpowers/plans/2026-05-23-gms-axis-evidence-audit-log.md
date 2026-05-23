# GMS Axis Evidence Audit Log Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist raw GMS `axis_evidence` in server audit logs and render it only in the operator admin page.

**Architecture:** Add `recommendation_audit_log.axis_evidence_summary` as nullable compact JSON, matching the existing `taste_mode_gate_summary` pattern. Track and playlist GMS preview services serialize only the diagnostic fields needed by operators, and admin APIs expose the JSON through existing admin-checked endpoints. The web admin page reads server audit data instead of browser-local diagnostics.

**Tech Stack:** Spring Boot 3.5, Java 21 records/JPA/Flyway/JUnit/MockMvc, React + TypeScript + Vite + Playwright.

---

## File Structure

- Create `services/api/src/main/resources/db/migration/V47__add_axis_evidence_summary_to_recommendation_audit_log.sql`
  - Adds nullable text storage for compact JSON.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/RecommendationAuditLogStore.java`
  - Adds `axisEvidenceSummary` to audit draft/state.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/persistence/RecommendationAuditLogEntity.java`
  - Maps the new text column.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/local/InMemoryRecommendationAuditLogStore.java`
  - Carries the new field for local/profile tests.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/RecommendationAuditLogAdminController.java`
  - Includes `axis_evidence_summary` in `/recent` admin DTO.
- Create `services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsAxisEvidenceAuditSummaryService.java`
  - Serializes compact GMS track and playlist evidence JSON.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewService.java`
  - Stores track preview evidence JSON in audit rows.
- Modify `services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsPlaylistPreviewService.java`
  - Stores playlist preview evidence JSON in audit rows.
- Modify backend tests:
  - `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/RecommendationAuditLogAdminControllerWebMvcTest.java`
  - `services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java`
  - `services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsPlaylistPreviewServiceTest.java`
- Modify web files:
  - `apps/web/src/types/api.ts`
  - `apps/web/src/pages/PlaylistQualityAdminPage.tsx`
  - `apps/web/src/lib/gmsPreviewAdminEvidence.ts`
  - `apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts`
  - `apps/web/tests/e2e/gms-playlist-save.spec.ts`

## Task 1: Persist the New Audit Field Through Store and Admin DTO

**Files:**
- Create: `services/api/src/main/resources/db/migration/V47__add_axis_evidence_summary_to_recommendation_audit_log.sql`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/RecommendationAuditLogStore.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/persistence/RecommendationAuditLogEntity.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/local/InMemoryRecommendationAuditLogStore.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/RecommendationAuditLogAdminController.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/RecommendationAuditLogAdminControllerWebMvcTest.java`

- [ ] **Step 1: Write the failing WebMvc assertion**

Update the `StoredAuditLog` construction in `shouldReturnRecentAuditLogEntries` to include the new axis evidence JSON just before `createdAt`:

```java
"{\"source\":\"gms-preview\",\"items\":[{\"track_id\":\"axis-narrative-001\"}]}",
Instant.parse("2026-05-14T00:00:00Z")
```

Then add this assertion:

```java
.andExpect(jsonPath("$.entries[0].axis_evidence_summary")
    .value("{\"source\":\"gms-preview\",\"items\":[{\"track_id\":\"axis-narrative-001\"}]}"));
```

- [ ] **Step 2: Run the WebMvc test and verify RED**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.presentation.RecommendationAuditLogAdminControllerWebMvcTest
```

Expected: compilation fails because `StoredAuditLog` has no `axisEvidenceSummary` constructor argument, or JSON path fails because the DTO does not expose `axis_evidence_summary`.

- [ ] **Step 3: Add the Flyway migration**

Create `services/api/src/main/resources/db/migration/V47__add_axis_evidence_summary_to_recommendation_audit_log.sql`:

```sql
ALTER TABLE recommendation_audit_log
    ADD COLUMN axis_evidence_summary TEXT;
```

- [ ] **Step 4: Extend `RecommendationAuditLogStore` records**

In `RecommendationAuditLogStore.AuditDraft`, add:

```java
String axisEvidenceSummary,
```

after `String tasteModeGateSummary,`.

In `RecommendationAuditLogStore.StoredAuditLog`, add:

```java
String axisEvidenceSummary,
```

after `String tasteModeGateSummary,`.

- [ ] **Step 5: Map the JPA entity field**

In `RecommendationAuditLogEntity`, add the field:

```java
@Column(name = "axis_evidence_summary", columnDefinition = "TEXT")
private String axisEvidenceSummary;
```

In the constructor, after assigning `tasteModeGateSummary`, add:

```java
this.axisEvidenceSummary = draft.axisEvidenceSummary();
```

In `toState()`, pass `axisEvidenceSummary` immediately after `tasteModeGateSummary`.

- [ ] **Step 6: Update in-memory store**

In `InMemoryRecommendationAuditLogStore.save`, pass:

```java
draft.axisEvidenceSummary(),
```

immediately after `draft.tasteModeGateSummary(),`.

- [ ] **Step 7: Expose the field in admin DTO**

In `RecommendationAuditLogAdminController.RecommendationAuditLogItem`, add:

```java
String axisEvidenceSummary,
```

after `String tasteModeGateSummary,`.

In `RecommendationAuditLogItem.from`, pass:

```java
entry.axisEvidenceSummary(),
```

after `entry.tasteModeGateSummary(),`.

- [ ] **Step 8: Update existing `AuditDraft`/`StoredAuditLog` call sites**

For feedback audit rows and any row that does not have axis evidence, pass `null` for `axisEvidenceSummary`.

Example in feedback service:

```java
recommendationAuditLogStore.save(new RecommendationAuditLogStore.AuditDraft(
    userId,
    request.requestId(),
    request.requestId(),
    RecommendationAuditLogStore.EVENT_FEEDBACK_RECORDED,
    request.sourceSpace(),
    null,
    null,
    null,
    null,
    null,
    null,
    request.feedbackType(),
    request.trackId(),
    request.playlistId(),
    null,
    null,
    Instant.now()
));
```

- [ ] **Step 9: Run the WebMvc test and verify GREEN**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.recommendation.presentation.RecommendationAuditLogAdminControllerWebMvcTest
```

Expected: PASS.

- [ ] **Step 10: Commit Task 1**

```bash
git add \
  services/api/src/main/resources/db/migration/V47__add_axis_evidence_summary_to_recommendation_audit_log.sql \
  services/api/src/main/java/io/myforevermusic/api/modules/recommendation/application/RecommendationAuditLogStore.java \
  services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/persistence/RecommendationAuditLogEntity.java \
  services/api/src/main/java/io/myforevermusic/api/modules/recommendation/infrastructure/local/InMemoryRecommendationAuditLogStore.java \
  services/api/src/main/java/io/myforevermusic/api/modules/recommendation/presentation/RecommendationAuditLogAdminController.java \
  services/api/src/test/java/io/myforevermusic/api/modules/recommendation/presentation/RecommendationAuditLogAdminControllerWebMvcTest.java
git commit -m "feat: expose axis evidence in recommendation audit logs"
```

## Task 2: Store Track Preview Axis Evidence

**Files:**
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsAxisEvidenceAuditSummaryService.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewService.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java`

- [ ] **Step 1: Write the failing service assertion**

In `shouldAttachTasteModeGateDryRunWithoutChangingRankingAndStoreAuditSummary`, extend the final audit assertion:

```java
RecommendationAuditLogStore.StoredAuditLog audit =
    auditLogStore.findRecentByUserId("taste-mode-user", 1).getFirst();

assertThat(audit.tasteModeGateSummary())
    .contains("\"dry_run_count\":2")
    .contains("\"apply_ranking_boost\":false");
assertThat(audit.axisEvidenceSummary())
    .contains("\"source\":\"gms-preview\"")
    .contains("\"items\"")
    .contains("\"track_id\":\"track-heavy-high-001\"")
    .contains("\"axis_evidence\"");
```

- [ ] **Step 2: Run the focused service test and verify RED**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest
```

Expected: compilation fails because `axisEvidenceSummary()` does not exist on the previous state, or the new assertion fails because it is `null`.

- [ ] **Step 3: Create the serializer service**

Create `GmsAxisEvidenceAuditSummaryService.java`:

```java
package io.myforevermusic.api.modules.gms.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.gms.presentation.GmsRecommendationPreviewResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class GmsAxisEvidenceAuditSummaryService {

    private final ObjectMapper objectMapper;

    public GmsAxisEvidenceAuditSummaryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String forTrackPreview(GmsRecommendationPreviewResponse response) {
        if (response == null || response.items() == null || response.items().isEmpty()) {
            return null;
        }
        List<Map<String, Object>> items = response.items().stream()
            .filter(item -> item.axisEvidence() != null && !item.axisEvidence().isEmpty())
            .map(item -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("rank", item.rank());
                entry.put("track_id", item.trackId());
                entry.put("title", item.title());
                entry.put("artist_name", item.artistName());
                entry.put("source_platform", item.sourcePlatform());
                entry.put("score", item.score());
                entry.put("axis_evidence", item.axisEvidence());
                return entry;
            })
            .toList();
        if (items.isEmpty()) {
            return null;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source", "gms-preview");
        summary.put("items", items);
        return write(summary);
    }

    private String write(Map<String, Object> summary) {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException exception) {
            return "{\"source\":\"gms\",\"serialization_error\":\"%s\"}".formatted(
                exception.getClass().getSimpleName()
            );
        }
    }
}
```

- [ ] **Step 4: Inject the serializer into `GmsRecommendationPreviewService`**

Add a field:

```java
private final GmsAxisEvidenceAuditSummaryService axisEvidenceAuditSummaryService;
```

Add constructor parameter after `RecommendationAuditLogStore recommendationAuditLogStore`:

```java
GmsAxisEvidenceAuditSummaryService axisEvidenceAuditSummaryService,
```

Assign it:

```java
this.axisEvidenceAuditSummaryService = axisEvidenceAuditSummaryService;
```

- [ ] **Step 5: Store track preview summary in audit rows**

In `recordPreviewAudit`, compute:

```java
String axisEvidenceSummary = axisEvidenceAuditSummaryService.forTrackPreview(response);
```

Pass `axisEvidenceSummary` immediately after `tasteModeGateSummary` in `AuditDraft`.

- [ ] **Step 6: Update test constructors**

Every `new GmsRecommendationPreviewService(...)` in `GmsRecommendationPreviewServiceTest` needs this additional argument immediately after `auditLogStore`:

```java
new GmsAxisEvidenceAuditSummaryService(new com.fasterxml.jackson.databind.ObjectMapper()),
```

- [ ] **Step 7: Run focused service test and verify GREEN**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.gms.application.GmsRecommendationPreviewServiceTest
```

Expected: PASS.

- [ ] **Step 8: Commit Task 2**

```bash
git add \
  services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsAxisEvidenceAuditSummaryService.java \
  services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsRecommendationPreviewServiceTest.java
git commit -m "feat: store gms track axis evidence in audit log"
```

## Task 3: Store Playlist Preview Axis Evidence

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsAxisEvidenceAuditSummaryService.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsPlaylistPreviewService.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsPlaylistPreviewServiceTest.java`

- [ ] **Step 1: Write the failing playlist service assertion**

In the playlist preview test that verifies candidates are returned, add an `InMemoryRecommendationAuditLogStore auditLogStore` and assert:

```java
RecommendationAuditLogStore.StoredAuditLog audit =
    auditLogStore.findRecentByUserId("user-001", 1).getFirst();

assertThat(audit.sourceSpace()).isEqualTo("gms-playlists");
assertThat(audit.axisEvidenceSummary())
    .contains("\"source\":\"gms-playlists\"")
    .contains("\"playlists\"")
    .contains("\"playlist_id\":")
    .contains("\"axis_evidence\"");
```

- [ ] **Step 2: Run the playlist service test and verify RED**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.gms.application.GmsPlaylistPreviewServiceTest
```

Expected: compilation fails until the constructor accepts an audit log store, or the assertion fails because no audit row is written.

- [ ] **Step 3: Add playlist serialization method**

In `GmsAxisEvidenceAuditSummaryService`, add:

```java
public String forPlaylistPreview(GmsPlaylistPreviewService.GmsPlaylistPreviewResult result) {
    if (result == null || result.candidates() == null || result.candidates().isEmpty()) {
        return null;
    }
    List<Map<String, Object>> playlists = result.candidates().stream()
        .filter(candidate -> candidate.axisEvidence() != null && !candidate.axisEvidence().isEmpty())
        .map(candidate -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("rank", result.candidates().indexOf(candidate) + 1);
            entry.put("playlist_id", candidate.playlistId());
            entry.put("title", candidate.title());
            entry.put("source_platform", candidate.sourcePlatform());
            entry.put("track_count", candidate.trackCount());
            entry.put("composite_score", candidate.compositeScore());
            entry.put("affinity_score", candidate.affinityScore());
            entry.put("confidence_score", candidate.confidenceScore());
            entry.put("axis_evidence", candidate.axisEvidence());
            return entry;
        })
        .toList();
    if (playlists.isEmpty()) {
        return null;
    }
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("source", "gms-playlists");
    summary.put("playlists", playlists);
    return write(summary);
}
```

- [ ] **Step 4: Inject audit dependencies into `GmsPlaylistPreviewService`**

Add fields:

```java
private final RecommendationAuditLogStore recommendationAuditLogStore;
private final GmsAxisEvidenceAuditSummaryService axisEvidenceAuditSummaryService;
```

Add constructor parameters after `PlaylistQualityEvaluator playlistQualityEvaluator`:

```java
RecommendationAuditLogStore recommendationAuditLogStore,
GmsAxisEvidenceAuditSummaryService axisEvidenceAuditSummaryService
```

Assign both fields in the constructor.

- [ ] **Step 5: Save playlist preview audit after result creation**

Replace the direct `return new GmsPlaylistPreviewResult(...)` with:

```java
GmsPlaylistPreviewResult result = new GmsPlaylistPreviewResult(
    userId,
    preferredPlatform,
    pmsTrackCount > 0L ? "baseline" : "cold-start",
    Instant.now(),
    candidates
);
recordPlaylistPreviewAudit(result);
return result;
```

Add:

```java
private void recordPlaylistPreviewAudit(GmsPlaylistPreviewResult result) {
    if (result.userId() == null || result.userId().isBlank()) {
        return;
    }
    recommendationAuditLogStore.save(new RecommendationAuditLogStore.AuditDraft(
        result.userId(),
        "gms-playlists-%d".formatted(result.generatedAt().toEpochMilli()),
        null,
        RecommendationAuditLogStore.EVENT_PREVIEW_GENERATED,
        "gms-playlists",
        "gms-playlist-preview-v1",
        null,
        null,
        result.candidates() == null ? 0 : result.candidates().size(),
        null,
        null,
        null,
        null,
        null,
        null,
        axisEvidenceAuditSummaryService.forPlaylistPreview(result),
        result.generatedAt()
    ));
}
```

- [ ] **Step 6: Update playlist service tests**

Every `new GmsPlaylistPreviewService(...)` in `GmsPlaylistPreviewServiceTest` needs:

```java
auditLogStore,
new GmsAxisEvidenceAuditSummaryService(new com.fasterxml.jackson.databind.ObjectMapper())
```

as the final two constructor arguments. For tests that do not assert audit content, create:

```java
InMemoryRecommendationAuditLogStore auditLogStore = new InMemoryRecommendationAuditLogStore();
```

near the other in-memory stores.

- [ ] **Step 7: Run playlist service test and verify GREEN**

Run:

```bash
cd services/api
./gradlew test --tests io.myforevermusic.api.modules.gms.application.GmsPlaylistPreviewServiceTest
```

Expected: PASS.

- [ ] **Step 8: Commit Task 3**

```bash
git add \
  services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsAxisEvidenceAuditSummaryService.java \
  services/api/src/main/java/io/myforevermusic/api/modules/gms/application/GmsPlaylistPreviewService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/gms/application/GmsPlaylistPreviewServiceTest.java
git commit -m "feat: store gms playlist axis evidence in audit log"
```

## Task 4: Render Server Audit Evidence in Admin UI

**Files:**
- Modify: `apps/web/src/types/api.ts`
- Modify: `apps/web/src/pages/PlaylistQualityAdminPage.tsx`
- Modify: `apps/web/src/lib/gmsPreviewAdminEvidence.ts`
- Test: `apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts`
- Test: `apps/web/tests/e2e/gms-playlist-save.spec.ts`

- [ ] **Step 1: Write the failing admin E2E fixture**

In `gms-preview-taste-mode-affinity.spec.ts`, update the quality admin test route to mock `/api/v1/recommendations/admin/audit-log/recent`:

```ts
const auditLogResponse = {
    service: 'api',
    status: 'ok',
    generated_at: '2026-05-21T00:04:00Z',
    entries: [
        {
            audit_log_id: 77,
            user_id: userSession.userId,
            recommendation_id: 'preview-affinity-raw-001',
            request_id: 'request-affinity-raw-001',
            event_type: 'preview_generated',
            source_space: 'gms',
            model_version: 'gms-baseline-v1',
            dataset_version: null,
            dataset_fingerprint: null,
            item_count: 1,
            sasrec_applied: false,
            fallback_reason: null,
            feedback_type: null,
            target_track_id: null,
            target_playlist_id: null,
            taste_mode_gate_summary: null,
            axis_evidence_summary: JSON.stringify({
                source: 'gms-preview',
                items: [
                    {
                        rank: 1,
                        track_id: 'axis-narrative-001',
                        title: 'Axis Narrative Candidate',
                        artist_name: 'Signal Curator',
                        source_platform: 'tidal',
                        score: 0.84,
                        axis_evidence: axisNarrativePreviewResponse.items[0].axis_evidence,
                    },
                ],
            }),
            created_at: '2026-05-21T00:02:00Z',
        },
    ],
}
```

Route it:

```ts
await page.route('**/api/v1/recommendations/admin/audit-log/recent**', (route) =>
    fulfillJson(route, auditLogResponse),
)
```

Remove the manual `localStorage.setItem(adminEvidenceStorageKey, ...)` from that test so it proves server data is used.

- [ ] **Step 2: Run the admin E2E and verify RED**

Run:

```bash
cd apps/web
npm run test:e2e -- tests/e2e/gms-preview-taste-mode-affinity.spec.ts
```

Expected: FAIL because `PlaylistQualityAdminPage` does not fetch audit-log recent evidence yet.

- [ ] **Step 3: Extend web types**

In `RecommendationAuditLogItem`, add:

```ts
axis_evidence_summary: string | null
```

after `taste_mode_gate_summary?: string | null` or the existing adjacent audit summary field.

- [ ] **Step 4: Parse server axis evidence in the admin evidence utility**

In `apps/web/src/lib/gmsPreviewAdminEvidence.ts`, export:

```ts
export const parseGmsPreviewAdminEvidenceSnapshot = (
    rawSummary: string | null | undefined,
    fallback: Pick<GmsPreviewAdminEvidenceSnapshot, 'request_id' | 'generated_at' | 'user_id'>,
): GmsPreviewAdminEvidenceSnapshot | null => {
    if (!rawSummary?.trim()) {
        return null
    }
    try {
        const parsed = JSON.parse(rawSummary) as Partial<GmsPreviewAdminEvidenceSnapshot>
        return {
            request_id: fallback.request_id,
            generated_at: fallback.generated_at,
            user_id: fallback.user_id,
            tracks: Array.isArray(parsed.items) ? parsed.items as GmsPreviewAdminEvidenceTrack[] : [],
            playlists: Array.isArray(parsed.playlists) ? parsed.playlists as GmsPreviewAdminEvidencePlaylist[] : [],
        }
    } catch {
        return null
    }
}
```

- [ ] **Step 5: Fetch audit logs from `PlaylistQualityAdminPage`**

Import:

```ts
import { fetchRecentRecommendationAuditLogForAdmin, fetchRecentPlaylistQualityForAdmin } from '@/services/api'
```

Add state:

```ts
const [adminEvidenceLoading, setAdminEvidenceLoading] = useState(false)
```

Inside `load`, after playlist quality response:

```ts
const [qualityResponse, auditResponse] = await Promise.all([
    fetchRecentPlaylistQualityForAdmin(session.userId, DEFAULT_LIMIT, signal),
    fetchRecentRecommendationAuditLogForAdmin(session.userId, undefined, DEFAULT_LIMIT, signal),
])
setPlaylists(qualityResponse.playlists)
setGeneratedAt(qualityResponse.generated_at)
const evidenceEntry = auditResponse.entries.find((entry) => entry.axis_evidence_summary?.trim())
setAdminEvidenceSnapshot(evidenceEntry
    ? parseGmsPreviewAdminEvidenceSnapshot(evidenceEntry.axis_evidence_summary, {
        request_id: evidenceEntry.recommendation_id ?? evidenceEntry.request_id ?? `audit-${evidenceEntry.audit_log_id}`,
        generated_at: evidenceEntry.created_at,
        user_id: evidenceEntry.user_id,
    })
    : null)
```

Remove the `useEffect` that reads `loadGmsPreviewAdminEvidenceSnapshot()` from local storage.

- [ ] **Step 6: Keep local storage out of the user flow**

Remove these imports and calls:

```ts
saveGmsPreviewAdminEvidenceSnapshot(preview)
saveGmsPlaylistAdminEvidenceSnapshot(response)
```

from `GmsPreviewPage` and `GmsPlaylistsPage`. If no other code uses `saveGmsPreviewAdminEvidenceSnapshot`, remove that save helper from `gmsPreviewAdminEvidence.ts`.

- [ ] **Step 7: Run admin E2E and verify GREEN**

Run:

```bash
cd apps/web
npm run test:e2e -- tests/e2e/gms-preview-taste-mode-affinity.spec.ts
```

Expected: PASS for admin evidence rendering from mocked server audit data.

- [ ] **Step 8: Run playlist E2E and verify GREEN**

Run:

```bash
cd apps/web
npm run test:e2e -- tests/e2e/gms-playlist-save.spec.ts
```

Expected: PASS with raw playlist evidence hidden from the user-facing playlist card.

- [ ] **Step 9: Commit Task 4**

```bash
git add \
  apps/web/src/types/api.ts \
  apps/web/src/pages/PlaylistQualityAdminPage.tsx \
  apps/web/src/pages/GmsPreviewPage.tsx \
  apps/web/src/pages/GmsPlaylistsPage.tsx \
  apps/web/src/lib/gmsPreviewAdminEvidence.ts \
  apps/web/tests/e2e/gms-preview-taste-mode-affinity.spec.ts \
  apps/web/tests/e2e/gms-playlist-save.spec.ts
git commit -m "feat: render server gms evidence diagnostics"
```

## Task 5: Full Verification

**Files:**
- Verify only; no edits expected.

- [ ] **Step 1: Run backend recommendation tests**

Run:

```bash
cd services/api
./gradlew test --tests 'io.myforevermusic.api.modules.recommendation.*' --tests 'io.myforevermusic.api.modules.gms.*'
```

Expected: PASS.

- [ ] **Step 2: Run frontend build**

Run:

```bash
cd apps/web
npm run build
```

Expected: PASS. The existing Vite chunk-size warning is acceptable if the build exits 0.

- [ ] **Step 3: Check staged/uncommitted changes**

Run:

```bash
git status --short
git log --oneline -5
```

Expected: only pre-existing unrelated user changes remain outside the commits from this plan.

- [ ] **Step 4: Push after user approval**

Run:

```bash
git push origin main
```

Expected: `main -> main` on `github.com/imorangepie20/my-forever-music-ubuntu.git`.

