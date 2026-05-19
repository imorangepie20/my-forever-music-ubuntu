package io.myforevermusic.api.modules.recommendation.presentation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.modules.recommendation.application.CanonicalTrackIdentityStore;
import io.myforevermusic.api.modules.recommendation.application.MetadataNormalizationAdminService;
import io.myforevermusic.api.modules.recommendation.application.MetadataNormalizationAdminService.LookupResult;
import io.myforevermusic.api.modules.recommendation.application.TrackIdentityCandidateAuditStore;
import io.myforevermusic.api.modules.recommendation.application.TrackIdentityCandidateStore;
import io.myforevermusic.api.modules.recommendation.infrastructure.musicbrainz.MusicBrainzClient.MusicBrainzArtistCredit;
import io.myforevermusic.api.modules.recommendation.infrastructure.musicbrainz.MusicBrainzClient.MusicBrainzRecording;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@org.springframework.context.annotation.Profile("!local")
@RestController
@RequestMapping("/api/v1/recommendations/admin/metadata")
public class MetadataNormalizationAdminController {

    private final MetadataNormalizationAdminService adminService;

    public MetadataNormalizationAdminController(MetadataNormalizationAdminService adminService) {
        this.adminService = adminService;
    }

    @Operation(summary = "Look up MusicBrainz recording candidates by title + artist for the configured admin user")
    @GetMapping("/musicbrainz/recordings")
    public MetadataLookupResponse lookupMusicBrainzRecordings(
        @RequestParam("user_id") String userId,
        @RequestParam("title") String title,
        @RequestParam(value = "artist", required = false) String artist,
        @RequestParam(value = "limit", defaultValue = "10") int limit,
        @RequestParam(value = "persist", defaultValue = "false") boolean persist
    ) {
        LookupResult result = adminService.lookupMusicBrainz(userId, title, artist, limit, persist);
        List<MetadataLookupCandidate> candidates = Optional.ofNullable(result.response().recordings())
            .orElse(List.of())
            .stream()
            .map(MetadataLookupCandidate::from)
            .toList();
        return new MetadataLookupResponse(
            "api",
            "ok",
            Instant.now(),
            title,
            artist,
            result.response().count() == null ? candidates.size() : result.response().count(),
            candidates,
            result.savedCandidates().stream().map(CandidateItem::from).toList()
        );
    }

    @Operation(summary = "Look up Wikidata entity candidates by title + artist for the configured admin user")
    @GetMapping("/wikidata/entities")
    public ExternalMetadataLookupResponse lookupWikidataEntities(
        @RequestParam("user_id") String userId,
        @RequestParam("title") String title,
        @RequestParam(value = "artist", required = false) String artist,
        @RequestParam(value = "limit", defaultValue = "10") int limit,
        @RequestParam(value = "persist", defaultValue = "false") boolean persist
    ) {
        return ExternalMetadataLookupResponse.from(
            adminService.lookupWikidata(userId, title, artist, limit, persist)
        );
    }

    @Operation(summary = "Look up Discogs master candidates by title + artist for the configured admin user")
    @GetMapping("/discogs/masters")
    public ExternalMetadataLookupResponse lookupDiscogsMasters(
        @RequestParam("user_id") String userId,
        @RequestParam("title") String title,
        @RequestParam(value = "artist", required = false) String artist,
        @RequestParam(value = "limit", defaultValue = "10") int limit,
        @RequestParam(value = "persist", defaultValue = "false") boolean persist
    ) {
        return ExternalMetadataLookupResponse.from(
            adminService.lookupDiscogsMasters(userId, title, artist, limit, persist)
        );
    }

    @Operation(summary = "List recent track identity candidates (filter by status)")
    @GetMapping("/candidates")
    public CandidateListResponse listCandidates(
        @RequestParam("user_id") String userId,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "limit", defaultValue = "30") int limit
    ) {
        List<TrackIdentityCandidateStore.Entry> entries = adminService.listCandidates(userId, status, limit);
        return new CandidateListResponse(
            "api",
            "ok",
            Instant.now(),
            entries.stream().map(CandidateItem::from).toList()
        );
    }

    @Operation(summary = "Accept a track identity candidate")
    @PostMapping("/candidates/{candidateId}/accept")
    public CandidateCommandResponse acceptCandidate(
        @PathVariable Long candidateId,
        @RequestParam("user_id") String userId,
        @RequestBody(required = false) CandidateResolutionRequest request
    ) {
        TrackIdentityCandidateStore.Entry entry = adminService.acceptCandidate(
            userId,
            candidateId,
            request == null ? null : request.notes()
        );
        return new CandidateCommandResponse("api", "ok", Instant.now(), CandidateItem.from(entry));
    }

    @Operation(summary = "Reject a track identity candidate")
    @PostMapping("/candidates/{candidateId}/reject")
    public CandidateCommandResponse rejectCandidate(
        @PathVariable Long candidateId,
        @RequestParam("user_id") String userId,
        @RequestBody(required = false) CandidateResolutionRequest request
    ) {
        TrackIdentityCandidateStore.Entry entry = adminService.rejectCandidate(
            userId,
            candidateId,
            request == null ? null : request.notes()
        );
        return new CandidateCommandResponse("api", "ok", Instant.now(), CandidateItem.from(entry));
    }

    @Operation(summary = "Bulk auto-accept pending candidates whose score >= min_score")
    @PostMapping("/candidates/auto-accept")
    public CandidateAutoAcceptResponse autoAcceptPendingCandidates(
        @RequestParam("user_id") String userId,
        @RequestParam(value = "min_score", defaultValue = "0.95") double minScore,
        @RequestParam(value = "limit", defaultValue = "100") int limit,
        @RequestParam(value = "source", required = false) String source,
        @RequestParam(value = "candidate_kind", required = false) String candidateKind
    ) {
        MetadataNormalizationAdminService.AutoAcceptResult result = adminService.autoAcceptPendingCandidates(
            userId,
            minScore,
            limit,
            source,
            candidateKind
        );
        return new CandidateAutoAcceptResponse(
            "api",
            "ok",
            Instant.now(),
            result.threshold(),
            result.reviewedCount(),
            result.accepted().size(),
            result.skipped().size(),
            result.accepted().stream().map(CandidateItem::from).toList()
        );
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MetadataLookupResponse(
        String service,
        String status,
        Instant generatedAt,
        String title,
        String artist,
        int totalCount,
        List<MetadataLookupCandidate> candidates,
        List<CandidateItem> savedCandidates
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MetadataLookupCandidate(
        String mbid,
        String title,
        String artistName,
        Integer lengthMs,
        Integer score,
        List<String> isrcs,
        List<String> releaseTitles
    ) {
        static MetadataLookupCandidate from(MusicBrainzRecording recording) {
            String artistName = Optional.ofNullable(recording.artistCredit())
                .map(credits -> String.join(", ", credits.stream().map(MusicBrainzArtistCredit::name).toList()))
                .orElse(null);
            List<String> releaseTitles = Optional.ofNullable(recording.releases())
                .map(releases -> releases.stream().map(ref -> ref.title()).toList())
                .orElse(List.of());
            return new MetadataLookupCandidate(
                recording.id(),
                recording.title(),
                artistName,
                recording.length(),
                recording.score(),
                recording.isrcs() == null ? List.of() : recording.isrcs(),
                releaseTitles
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExternalMetadataLookupResponse(
        String service,
        String status,
        Instant generatedAt,
        String source,
        String title,
        String artist,
        int totalCount,
        List<ExternalMetadataLookupCandidate> candidates,
        List<CandidateItem> savedCandidates
    ) {
        static ExternalMetadataLookupResponse from(MetadataNormalizationAdminService.ExternalLookupResult result) {
            return new ExternalMetadataLookupResponse(
                "api",
                "ok",
                Instant.now(),
                result.source(),
                result.title(),
                result.artist(),
                result.candidates().size(),
                result.candidates().stream().map(ExternalMetadataLookupCandidate::from).toList(),
                result.savedCandidates().stream().map(CandidateItem::from).toList()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExternalMetadataLookupCandidate(
        String source,
        String candidateKind,
        String candidateValue,
        String label,
        String description,
        Double candidateScore
    ) {
        static ExternalMetadataLookupCandidate from(MetadataNormalizationAdminService.ExternalLookupCandidate candidate) {
            return new ExternalMetadataLookupCandidate(
                candidate.source(),
                candidate.candidateKind(),
                candidate.candidateValue(),
                candidate.label(),
                candidate.description(),
                candidate.candidateScore()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CandidateListResponse(
        String service,
        String status,
        Instant generatedAt,
        List<CandidateItem> candidates
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CandidateCommandResponse(
        String service,
        String status,
        Instant generatedAt,
        CandidateItem candidate
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CandidateItem(
        Long id,
        String queryTitle,
        String queryArtist,
        String source,
        String candidateKind,
        String candidateValue,
        Double candidateScore,
        String status,
        String createdBy,
        Instant createdAt,
        String resolvedBy,
        Instant resolvedAt,
        String notes
    ) {
        static CandidateItem from(TrackIdentityCandidateStore.Entry entry) {
            return new CandidateItem(
                entry.id(),
                entry.queryTitle(),
                entry.queryArtist(),
                entry.source(),
                entry.candidateKind(),
                entry.candidateValue(),
                entry.candidateScore(),
                entry.status(),
                entry.createdBy(),
                entry.createdAt(),
                entry.resolvedBy(),
                entry.resolvedAt(),
                entry.notes()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CandidateResolutionRequest(String notes) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CandidateAutoAcceptResponse(
        String service,
        String status,
        Instant generatedAt,
        double threshold,
        int reviewedCount,
        int acceptedCount,
        int skippedCount,
        List<CandidateItem> accepted
    ) {}

    @Operation(summary = "List structured audit entries for a track identity candidate")
    @GetMapping("/candidates/{candidateId}/audit")
    public CandidateAuditResponse listCandidateAudit(
        @PathVariable Long candidateId,
        @RequestParam("user_id") String userId
    ) {
        MetadataNormalizationAdminService.CandidateAuditResult result =
            adminService.listCandidateAudit(userId, candidateId);
        return new CandidateAuditResponse(
            "api",
            "ok",
            Instant.now(),
            CandidateItem.from(result.candidate()),
            result.entries().stream().map(CandidateAuditItem::from).toList()
        );
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CandidateAuditResponse(
        String service,
        String status,
        Instant generatedAt,
        CandidateItem candidate,
        List<CandidateAuditItem> entries
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CandidateAuditItem(
        Long id,
        Long candidateId,
        String action,
        Long emsCollectedTrackId,
        String candidateValue,
        String previousIsrc,
        String newIsrc,
        String status,
        String message,
        String actedBy,
        Instant actedAt
    ) {
        static CandidateAuditItem from(TrackIdentityCandidateAuditStore.Entry entry) {
            return new CandidateAuditItem(
                entry.id(),
                entry.candidateId(),
                entry.action(),
                entry.emsCollectedTrackId(),
                entry.candidateValue(),
                entry.previousIsrc(),
                entry.newIsrc(),
                entry.status(),
                entry.message(),
                entry.actedBy(),
                entry.actedAt()
            );
        }
    }

    @Operation(summary = "Promote an applied candidate into canonical track identity storage")
    @PostMapping("/candidates/{candidateId}/promote-canonical")
    public CandidateCanonicalPromotionResponse promoteCandidateToCanonical(
        @PathVariable Long candidateId,
        @RequestParam("user_id") String userId
    ) {
        MetadataNormalizationAdminService.CanonicalPromotionResult result =
            adminService.promoteCandidateToCanonicalIdentity(userId, candidateId);
        return new CandidateCanonicalPromotionResponse(
            "api",
            "ok",
            Instant.now(),
            CandidateItem.from(result.candidate()),
            CanonicalTrackItem.from(result.canonicalTrack()),
            CanonicalTrackIdentityItem.from(result.identity()),
            result.createdCanonicalTrack(),
            result.createdIdentity(),
            CanonicalRowLinkItem.from(result.links())
        );
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CandidateCanonicalPromotionResponse(
        String service,
        String status,
        Instant generatedAt,
        CandidateItem candidate,
        CanonicalTrackItem canonicalTrack,
        CanonicalTrackIdentityItem identity,
        boolean createdCanonicalTrack,
        boolean createdIdentity,
        CanonicalRowLinkItem links
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CanonicalRowLinkItem(
        int emsLinkedCount,
        long emsConflictCount,
        int pmsImportedLinkedCount,
        long pmsImportedConflictCount,
        int pmsUserLinkedCount,
        long pmsUserConflictCount,
        long totalConflictCount
    ) {
        static CanonicalRowLinkItem from(MetadataNormalizationAdminService.CanonicalRowLinkResult result) {
            return new CanonicalRowLinkItem(
                result.emsLinkedCount(),
                result.emsConflictCount(),
                result.pmsImportedLinkedCount(),
                result.pmsImportedConflictCount(),
                result.pmsUserLinkedCount(),
                result.pmsUserConflictCount(),
                result.totalConflictCount()
            );
        }
    }

    @Operation(summary = "List EMS/PMS rows whose ISRC points at a different canonical track")
    @GetMapping("/candidates/{candidateId}/canonical-link-conflicts")
    public CandidateCanonicalLinkConflictResponse listCanonicalLinkConflicts(
        @PathVariable Long candidateId,
        @RequestParam("user_id") String userId
    ) {
        MetadataNormalizationAdminService.CanonicalLinkConflictResult result =
            adminService.listCanonicalLinkConflicts(userId, candidateId);
        return new CandidateCanonicalLinkConflictResponse(
            "api",
            "ok",
            Instant.now(),
            CandidateItem.from(result.candidate()),
            CanonicalTrackIdentityItem.from(result.targetIdentity()),
            result.rows().stream().map(CanonicalLinkConflictRowItem::from).toList()
        );
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CandidateCanonicalLinkConflictResponse(
        String service,
        String status,
        Instant generatedAt,
        CandidateItem candidate,
        CanonicalTrackIdentityItem targetIdentity,
        List<CanonicalLinkConflictRowItem> rows
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CanonicalLinkConflictRowItem(
        String trackStore,
        String rowId,
        Long existingCanonicalTrackId,
        String title,
        String artistName,
        String sourcePlatform,
        String externalTrackId,
        String isrc
    ) {
        static CanonicalLinkConflictRowItem from(MetadataNormalizationAdminService.CanonicalLinkConflictRow row) {
            return new CanonicalLinkConflictRowItem(
                row.trackStore(),
                row.rowId(),
                row.existingCanonicalTrackId(),
                row.title(),
                row.artistName(),
                row.sourcePlatform(),
                row.externalTrackId(),
                row.isrc()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CanonicalTrackItem(
        Long canonicalTrackId,
        String displayTitle,
        String displayArtistName,
        String releaseYear,
        String releaseCountry,
        String releaseLabel,
        Instant createdAt,
        Instant updatedAt
    ) {
        static CanonicalTrackItem from(CanonicalTrackIdentityStore.CanonicalTrackEntry entry) {
            return new CanonicalTrackItem(
                entry.canonicalTrackId(),
                entry.displayTitle(),
                entry.displayArtistName(),
                entry.releaseYear(),
                entry.releaseCountry(),
                entry.releaseLabel(),
                entry.createdAt(),
                entry.updatedAt()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CanonicalTrackIdentityItem(
        Long canonicalTrackIdentityId,
        Long canonicalTrackId,
        String identityKind,
        String identityValue,
        String source,
        Double confidenceScore,
        String status,
        Long createdFromCandidateId,
        Instant createdAt,
        Instant updatedAt
    ) {
        static CanonicalTrackIdentityItem from(CanonicalTrackIdentityStore.IdentityEntry entry) {
            return new CanonicalTrackIdentityItem(
                entry.canonicalTrackIdentityId(),
                entry.canonicalTrackId(),
                entry.identityKind(),
                entry.identityValue(),
                entry.source(),
                entry.confidenceScore(),
                entry.status(),
                entry.createdFromCandidateId(),
                entry.createdAt(),
                entry.updatedAt()
            );
        }
    }

    @Operation(summary = "Apply accepted ISRC candidates to the matching ems_collected_track rows")
    @PostMapping("/candidates/apply-accepted-isrcs")
    public CandidateApplyResponse applyAcceptedIsrcs(
        @RequestParam("user_id") String userId,
        @RequestParam(value = "limit", defaultValue = "100") int limit
    ) {
        MetadataNormalizationAdminService.IsrcApplyResult result =
            adminService.applyAcceptedIsrcCandidates(userId, limit);
        return new CandidateApplyResponse(
            "api",
            "ok",
            Instant.now(),
            result.reviewedCount(),
            result.isrcConsideredCount(),
            result.applied().size(),
            result.noMatch().size(),
            result.conflicts().size(),
            result.applied().stream().map(AppliedCandidateItem::from).toList(),
            result.noMatch().stream().map(CandidateItem::from).toList(),
            result.conflicts().stream().map(CandidateItem::from).toList()
        );
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CandidateApplyResponse(
        String service,
        String status,
        Instant generatedAt,
        int reviewedCount,
        int isrcConsideredCount,
        int appliedCount,
        int noMatchCount,
        int conflictCount,
        List<AppliedCandidateItem> applied,
        List<CandidateItem> noMatch,
        List<CandidateItem> conflicts
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AppliedCandidateItem(
        CandidateItem candidate,
        List<Long> updatedTrackIds,
        List<Long> conflictTrackIds
    ) {
        static AppliedCandidateItem from(MetadataNormalizationAdminService.AppliedCandidate applied) {
            return new AppliedCandidateItem(
                CandidateItem.from(applied.candidate()),
                applied.updatedTrackIds(),
                applied.conflictTrackIds()
            );
        }
    }

    @Operation(summary = "Rollback an applied ISRC candidate from the recorded ems_collected_track rows")
    @PostMapping("/candidates/{candidateId}/rollback-applied-isrc")
    public CandidateRollbackResponse rollbackAppliedIsrc(
        @PathVariable Long candidateId,
        @RequestParam("user_id") String userId,
        @RequestBody(required = false) CandidateResolutionRequest request
    ) {
        MetadataNormalizationAdminService.IsrcRollbackResult result =
            adminService.rollbackAppliedIsrcCandidate(
                userId,
                candidateId,
                request == null ? null : request.notes()
            );
        return new CandidateRollbackResponse(
            "api",
            "ok",
            Instant.now(),
            CandidateItem.from(result.candidate()),
            result.targetTrackIds(),
            result.clearedTrackIds(),
            result.skippedTrackIds()
        );
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CandidateRollbackResponse(
        String service,
        String status,
        Instant generatedAt,
        CandidateItem candidate,
        List<Long> targetTrackIds,
        List<Long> clearedTrackIds,
        List<Long> skippedTrackIds
    ) {}
}
