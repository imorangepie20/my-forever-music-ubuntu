package io.myforevermusic.api.modules.recommendation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackRepository;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsImportedTrackEntity;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsImportedTrackRepository;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsUserTrackEntity;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsUserTrackRepository;
import io.myforevermusic.api.modules.recommendation.infrastructure.discogs.DiscogsClient;
import io.myforevermusic.api.modules.recommendation.infrastructure.discogs.DiscogsClient.DiscogsMasterDetail;
import io.myforevermusic.api.modules.recommendation.infrastructure.discogs.DiscogsClient.DiscogsReleaseDetail;
import io.myforevermusic.api.modules.recommendation.infrastructure.discogs.DiscogsClient.DiscogsReleaseLabel;
import io.myforevermusic.api.modules.recommendation.infrastructure.discogs.DiscogsClient.DiscogsSearchResult;
import io.myforevermusic.api.modules.recommendation.infrastructure.discogs.DiscogsClient.DiscogsSearchResponse;
import io.myforevermusic.api.modules.recommendation.infrastructure.musicbrainz.MusicBrainzClient;
import io.myforevermusic.api.modules.recommendation.infrastructure.musicbrainz.MusicBrainzClient.MusicBrainzRecording;
import io.myforevermusic.api.modules.recommendation.infrastructure.musicbrainz.MusicBrainzClient.MusicBrainzRecordingSearchResponse;
import io.myforevermusic.api.modules.recommendation.infrastructure.wikidata.WikidataClient;
import io.myforevermusic.api.modules.recommendation.infrastructure.wikidata.WikidataClient.WikidataEntitySearchResponse;
import io.myforevermusic.api.modules.recommendation.infrastructure.wikidata.WikidataClient.WikidataEntitySearchResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 2 metadata normalization admin service.
 * 1차: MusicBrainz read-only lookup.
 * 2차(현재): lookup 결과를 track_identity_candidate 에 저장하고 운영자가 accept/reject 한다.
 */
@org.springframework.context.annotation.Profile("!local")
@Service
public class MetadataNormalizationAdminService {

    private static final String ADMIN_EMAIL = "jowoosungtidal@gmail.com";

    private final MusicBrainzClient musicBrainzClient;
    private final WikidataClient wikidataClient;
    private final DiscogsClient discogsClient;
    private final TrackIdentityCandidateStore candidateStore;
    private final TrackIdentityCandidateAuditStore auditStore;
    private final CanonicalTrackIdentityStore canonicalTrackIdentityStore;
    private final AuthAccountStore authAccountStore;
    private final ObjectMapper objectMapper;
    private final EmsCollectedTrackRepository trackRepository;
    private final PmsImportedTrackRepository pmsImportedTrackRepository;
    private final PmsUserTrackRepository pmsUserTrackRepository;
    private final CandidateQualityScorer qualityScorer;

    public MetadataNormalizationAdminService(
        MusicBrainzClient musicBrainzClient,
        WikidataClient wikidataClient,
        DiscogsClient discogsClient,
        TrackIdentityCandidateStore candidateStore,
        TrackIdentityCandidateAuditStore auditStore,
        CanonicalTrackIdentityStore canonicalTrackIdentityStore,
        AuthAccountStore authAccountStore,
        ObjectMapper objectMapper,
        EmsCollectedTrackRepository trackRepository,
        PmsImportedTrackRepository pmsImportedTrackRepository,
        PmsUserTrackRepository pmsUserTrackRepository,
        CandidateQualityScorer qualityScorer
    ) {
        this.musicBrainzClient = musicBrainzClient;
        this.wikidataClient = wikidataClient;
        this.discogsClient = discogsClient;
        this.candidateStore = candidateStore;
        this.auditStore = auditStore;
        this.canonicalTrackIdentityStore = canonicalTrackIdentityStore;
        this.authAccountStore = authAccountStore;
        this.objectMapper = objectMapper;
        this.trackRepository = trackRepository;
        this.pmsImportedTrackRepository = pmsImportedTrackRepository;
        this.pmsUserTrackRepository = pmsUserTrackRepository;
        this.qualityScorer = qualityScorer;
    }

    public LookupResult lookupMusicBrainz(
        String adminUserId,
        String title,
        String artist,
        int limit,
        boolean persistCandidates
    ) {
        assertAdmin(adminUserId);
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required.");
        }
        MusicBrainzRecordingSearchResponse response = musicBrainzClient.searchRecordings(title, artist, limit);
        List<TrackIdentityCandidateStore.Entry> savedCandidates = new ArrayList<>();
        if (persistCandidates) {
            Instant now = Instant.now();
            for (MusicBrainzRecording recording : Optional.ofNullable(response.recordings()).orElse(List.of())) {
                if (recording.id() == null || recording.id().isBlank()) {
                    continue;
                }
                Double quality = qualityScorer.scoreFor(
                    title,
                    artist,
                    recording.title(),
                    musicBrainzArtistName(recording),
                    normalizeScore(recording.score())
                );
                savedCandidates.add(candidateStore.save(new TrackIdentityCandidateStore.Draft(
                    title,
                    artist,
                    "musicbrainz",
                    "mbid",
                    recording.id(),
                    quality,
                    serializeMetadata(recording),
                    adminUserId,
                    now
                )));
                if (recording.isrcs() != null) {
                    for (String isrc : recording.isrcs()) {
                        if (isrc == null || isrc.isBlank()) {
                            continue;
                        }
                        savedCandidates.add(candidateStore.save(new TrackIdentityCandidateStore.Draft(
                            title,
                            artist,
                            "musicbrainz",
                            "isrc",
                            isrc,
                            quality,
                            serializeMetadata(recording),
                            adminUserId,
                            now
                        )));
                    }
                }
            }
        }
        return new LookupResult(response, savedCandidates);
    }

    private String musicBrainzArtistName(MusicBrainzRecording recording) {
        if (recording.artistCredit() == null || recording.artistCredit().isEmpty()) {
            return null;
        }
        return recording.artistCredit().stream()
            .map(credit -> credit == null ? null : credit.name())
            .filter(name -> name != null && !name.isBlank())
            .findFirst()
            .orElse(null);
    }

    public ExternalLookupResult lookupWikidata(
        String adminUserId,
        String title,
        String artist,
        int limit,
        boolean persistCandidates
    ) {
        assertAdmin(adminUserId);
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required.");
        }
        WikidataEntitySearchResponse response = wikidataClient.searchEntities(title, artist, limit);
        List<ExternalLookupCandidate> candidates = Optional.ofNullable(response.search())
            .orElse(List.of())
            .stream()
            .filter(result -> result.id() != null && !result.id().isBlank())
            .map(result -> toWikidataCandidate(result, title, artist))
            .toList();
        return new ExternalLookupResult(
            "wikidata",
            title,
            artist,
            candidates,
            persistCandidates(candidates, title, artist, adminUserId, persistCandidates)
        );
    }

    public ExternalLookupResult lookupDiscogsMasters(
        String adminUserId,
        String title,
        String artist,
        int limit,
        boolean persistCandidates
    ) {
        assertAdmin(adminUserId);
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required.");
        }
        DiscogsSearchResponse response = discogsClient.searchMasters(title, artist, limit);
        List<ExternalLookupCandidate> candidates = Optional.ofNullable(response.results())
            .orElse(List.of())
            .stream()
            .filter(result -> result.id() != null)
            .map(result -> toDiscogsCandidate(result, title, artist))
            .toList();
        return new ExternalLookupResult(
            "discogs",
            title,
            artist,
            candidates,
            persistCandidates(candidates, title, artist, adminUserId, persistCandidates)
        );
    }

    public List<TrackIdentityCandidateStore.Entry> listCandidates(
        String adminUserId,
        String status,
        int limit
    ) {
        assertAdmin(adminUserId);
        return candidateStore.findRecentByStatus(status, limit);
    }

    public TrackIdentityCandidateStore.Entry acceptCandidate(
        String adminUserId,
        Long candidateId,
        String notes
    ) {
        assertAdmin(adminUserId);
        return candidateStore.updateStatus(
            candidateId,
            TrackIdentityCandidateStore.STATUS_ACCEPTED,
            adminUserId,
            notes,
            Instant.now()
        );
    }

    public TrackIdentityCandidateStore.Entry rejectCandidate(
        String adminUserId,
        Long candidateId,
        String notes
    ) {
        assertAdmin(adminUserId);
        return candidateStore.updateStatus(
            candidateId,
            TrackIdentityCandidateStore.STATUS_REJECTED,
            adminUserId,
            notes,
            Instant.now()
        );
    }

    public CandidateAuditResult listCandidateAudit(String adminUserId, Long candidateId) {
        assertAdmin(adminUserId);
        TrackIdentityCandidateStore.Entry candidate = candidateStore.findById(candidateId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "track identity candidate was not found."));
        return new CandidateAuditResult(candidate, auditStore.findByCandidateId(candidateId));
    }

    @Transactional
    public CanonicalPromotionResult promoteCandidateToCanonicalIdentity(String adminUserId, Long candidateId) {
        assertAdmin(adminUserId);
        TrackIdentityCandidateStore.Entry candidate = candidateStore.findById(candidateId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "track identity candidate was not found."));
        if (!isSupportedCanonicalIdentityKind(candidate.candidateKind())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported canonical identity candidate kind.");
        }
        if (!isPromotableCanonicalCandidate(candidate)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Only applied ISRC candidates or accepted external identity candidates can be promoted."
            );
        }
        if (candidate.candidateValue() == null || candidate.candidateValue().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "candidate value is empty.");
        }

        DiscogsReleaseMetadata discogsMetadata = resolveDiscogsReleaseMetadata(candidate);
        Instant now = Instant.now();
        CanonicalTrackIdentityStore.UpsertResult result;
        try {
            result = canonicalTrackIdentityStore.upsertIdentity(new CanonicalTrackIdentityStore.Draft(
                candidate.queryTitle(),
                candidate.queryArtist(),
                candidate.candidateKind(),
                candidate.candidateValue(),
                candidate.source(),
                candidate.candidateScore(),
                candidate.id(),
                discogsMetadata.year(),
                discogsMetadata.country(),
                now
            ));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        if (discogsMetadata.hasAnyField()) {
            CanonicalTrackIdentityStore.CanonicalTrackEntry filled = canonicalTrackIdentityStore
                .fillReleaseMetadataIfMissing(
                    result.canonicalTrack().canonicalTrackId(),
                    discogsMetadata.year(),
                    discogsMetadata.country(),
                    discogsMetadata.label(),
                    now
                );
            result = new CanonicalTrackIdentityStore.UpsertResult(
                filled,
                result.identity(),
                result.createdCanonicalTrack(),
                result.createdIdentity()
            );
        }

        CanonicalRowLinkResult links = linkRowsToCanonicalTrack(candidate, result);
        auditStore.save(new TrackIdentityCandidateAuditStore.Draft(
            candidate.id(),
            TrackIdentityCandidateAuditStore.ACTION_CANONICAL_PROMOTE,
            null,
            candidate.candidateValue(),
            null,
            null,
            result.createdIdentity()
                ? TrackIdentityCandidateAuditStore.STATUS_CANONICAL_PROMOTED
                : TrackIdentityCandidateAuditStore.STATUS_CANONICAL_EXISTS,
            "canonical track id %d, identity id %d, linked rows ems=%d, pms_imported=%d, pms_user=%d, conflicts=%d".formatted(
                result.canonicalTrack().canonicalTrackId(),
                result.identity().canonicalTrackIdentityId(),
                links.emsLinkedCount(),
                links.pmsImportedLinkedCount(),
                links.pmsUserLinkedCount(),
                links.totalConflictCount()
            ),
            adminUserId,
            now
        ));
        return new CanonicalPromotionResult(
            candidate,
            result.canonicalTrack(),
            result.identity(),
            result.createdCanonicalTrack(),
            result.createdIdentity(),
            links
        );
    }

    @Transactional(readOnly = true)
    public CanonicalLinkConflictResult listCanonicalLinkConflicts(String adminUserId, Long candidateId) {
        assertAdmin(adminUserId);
        TrackIdentityCandidateStore.Entry candidate = candidateStore.findById(candidateId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "track identity candidate was not found."));
        if (!"isrc".equalsIgnoreCase(candidate.candidateKind())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only ISRC candidates can have row link conflicts.");
        }
        if (candidate.candidateValue() == null || candidate.candidateValue().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "candidate ISRC is empty.");
        }
        CanonicalTrackIdentityStore.IdentityEntry identity = canonicalTrackIdentityStore.findActiveIdentity(
                candidate.source(),
                candidate.candidateKind(),
                candidate.candidateValue()
            )
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Candidate has not been promoted to canonical identity."
            ));

        String isrc = identity.identityValue();
        Long canonicalTrackId = identity.canonicalTrackId();
        List<CanonicalLinkConflictRow> rows = new ArrayList<>();
        rows.addAll(trackRepository.findCanonicalTrackConflictsByIsrc(isrc, canonicalTrackId).stream()
            .map(this::toConflictRow)
            .toList());
        rows.addAll(pmsImportedTrackRepository.findCanonicalTrackConflictsByIsrc(isrc, canonicalTrackId).stream()
            .map(this::toConflictRow)
            .toList());
        rows.addAll(pmsUserTrackRepository.findCanonicalTrackConflictsByIsrc(isrc, canonicalTrackId).stream()
            .map(this::toConflictRow)
            .toList());
        return new CanonicalLinkConflictResult(candidate, identity, rows);
    }

    /**
     * Pending candidate 중 candidate_score 가 minScore 이상인 항목을 한꺼번에 accept 한다.
     * 후속 단계의 ISRC 보강 worker 가 호출하는 같은 흐름을 운영자가 수동으로도 트리거할 수 있게 한다.
     */
    public AutoAcceptResult autoAcceptPendingCandidates(
        String adminUserId,
        double minScore,
        int batchLimit,
        String source,
        String candidateKind
    ) {
        assertAdmin(adminUserId);
        double threshold = Math.max(0.0d, Math.min(1.0d, minScore));
        int safeLimit = Math.max(1, Math.min(200, batchLimit));
        List<TrackIdentityCandidateStore.Entry> pending = candidateStore.findRecentByStatus(
            TrackIdentityCandidateStore.STATUS_PENDING,
            safeLimit
        );

        List<TrackIdentityCandidateStore.Entry> accepted = new ArrayList<>();
        List<TrackIdentityCandidateStore.Entry> skipped = new ArrayList<>();
        Instant now = Instant.now();
        String autoNote = "auto-accepted (score>=%.2f)".formatted(threshold);

        for (TrackIdentityCandidateStore.Entry candidate : pending) {
            if (!matchesFilter(candidate, source, candidateKind)) {
                continue;
            }
            Double score = candidate.candidateScore();
            if (score == null || score < threshold) {
                skipped.add(candidate);
                continue;
            }
            accepted.add(candidateStore.updateStatus(
                candidate.id(),
                TrackIdentityCandidateStore.STATUS_ACCEPTED,
                adminUserId,
                autoNote,
                now
            ));
        }
        return new AutoAcceptResult(threshold, pending.size(), accepted, skipped);
    }

    /**
     * Phase 2 metadata normalization 5차: accepted ISRC candidate를 실제 ems_collected_track 에 반영한다.
     * candidate.query_title/query_artist 로 EMS 트랙을 찾아 isrc 가 비어 있으면 update, 다른 isrc 가
     * 이미 있으면 conflict, 트랙을 못 찾으면 no_match 로 candidate status 를 갱신한다.
     */
    @Transactional
    public IsrcApplyResult applyAcceptedIsrcCandidates(String adminUserId, int batchLimit) {
        assertAdmin(adminUserId);
        int safeLimit = Math.max(1, Math.min(500, batchLimit));
        List<TrackIdentityCandidateStore.Entry> accepted = candidateStore.findRecentByStatus(
            TrackIdentityCandidateStore.STATUS_ACCEPTED,
            safeLimit
        );

        List<AppliedCandidate> applied = new ArrayList<>();
        List<TrackIdentityCandidateStore.Entry> noMatch = new ArrayList<>();
        List<TrackIdentityCandidateStore.Entry> conflicts = new ArrayList<>();
        Instant now = Instant.now();
        int isrcConsidered = 0;

        for (TrackIdentityCandidateStore.Entry candidate : accepted) {
            if (!"isrc".equals(candidate.candidateKind())) {
                continue;
            }
            String isrc = candidate.candidateValue();
            if (isrc == null || isrc.isBlank()) {
                continue;
            }
            isrcConsidered++;

            List<EmsCollectedTrackEntity> matches = findMatchingEmsTracks(
                candidate.queryTitle(),
                candidate.queryArtist()
            );

            if (matches.isEmpty()) {
                auditStore.save(new TrackIdentityCandidateAuditStore.Draft(
                    candidate.id(),
                    TrackIdentityCandidateAuditStore.ACTION_NO_MATCH,
                    null,
                    isrc,
                    null,
                    null,
                    TrackIdentityCandidateAuditStore.STATUS_NO_MATCH,
                    "no EMS track matched candidate query",
                    adminUserId,
                    now
                ));
                noMatch.add(candidateStore.updateStatus(
                    candidate.id(),
                    TrackIdentityCandidateStore.STATUS_NO_MATCH,
                    adminUserId,
                    "no EMS track matched candidate query",
                    now
                ));
                continue;
            }

            List<Long> updatedTrackIds = new ArrayList<>();
            List<Long> conflictTrackIds = new ArrayList<>();
            boolean anyAlreadyMatches = false;
            for (EmsCollectedTrackEntity track : matches) {
                String existing = track.getIsrc();
                if (existing == null || existing.isBlank()) {
                    int rows = trackRepository.updateIsrcIfNull(track.getId(), isrc);
                    if (rows > 0) {
                        updatedTrackIds.add(track.getId());
                        auditStore.save(new TrackIdentityCandidateAuditStore.Draft(
                            candidate.id(),
                            TrackIdentityCandidateAuditStore.ACTION_APPLY,
                            track.getId(),
                            isrc,
                            existing,
                            isrc,
                            TrackIdentityCandidateAuditStore.STATUS_APPLIED,
                            "applied accepted ISRC candidate",
                            adminUserId,
                            now
                        ));
                    }
                } else if (existing.equalsIgnoreCase(isrc)) {
                    anyAlreadyMatches = true;
                    auditStore.save(new TrackIdentityCandidateAuditStore.Draft(
                        candidate.id(),
                        TrackIdentityCandidateAuditStore.ACTION_APPLY,
                        track.getId(),
                        isrc,
                        existing,
                        existing,
                        TrackIdentityCandidateAuditStore.STATUS_ALREADY_MATCHED,
                        "track already had matching ISRC",
                        adminUserId,
                        now
                    ));
                } else {
                    conflictTrackIds.add(track.getId());
                    auditStore.save(new TrackIdentityCandidateAuditStore.Draft(
                        candidate.id(),
                        TrackIdentityCandidateAuditStore.ACTION_CONFLICT,
                        track.getId(),
                        isrc,
                        existing,
                        existing,
                        TrackIdentityCandidateAuditStore.STATUS_CONFLICT,
                        "track already had a different ISRC",
                        adminUserId,
                        now
                    ));
                }
            }

            if (!updatedTrackIds.isEmpty()) {
                String note = "applied to track ids %s".formatted(updatedTrackIds);
                applied.add(new AppliedCandidate(
                    candidateStore.updateStatus(
                        candidate.id(),
                        TrackIdentityCandidateStore.STATUS_APPLIED,
                        adminUserId,
                        note,
                        now
                    ),
                    updatedTrackIds,
                    conflictTrackIds
                ));
            } else if (anyAlreadyMatches && conflictTrackIds.isEmpty()) {
                applied.add(new AppliedCandidate(
                    candidateStore.updateStatus(
                        candidate.id(),
                        TrackIdentityCandidateStore.STATUS_APPLIED,
                        adminUserId,
                        "already applied (matching ISRCs)",
                        now
                    ),
                    List.of(),
                    List.of()
                ));
            } else {
                String note = "conflicting ISRC on track ids %s".formatted(conflictTrackIds);
                conflicts.add(candidateStore.updateStatus(
                    candidate.id(),
                    TrackIdentityCandidateStore.STATUS_CONFLICT,
                    adminUserId,
                    note,
                    now
                ));
            }
        }

        return new IsrcApplyResult(accepted.size(), isrcConsidered, applied, noMatch, conflicts);
    }

    /**
     * 잘못 적용된 accepted ISRC candidate 를 되돌린다.
     * 적용 당시 notes 에 기록된 track id 에 대해서만, 현재 isrc 가 candidate 값과 일치할 때 clear 한다.
     * 적용 track id 가 없거나 현재 값이 달라진 경우에는 자동 삭제하지 않고 review_required 로 남긴다.
     */
    @Transactional
    public IsrcRollbackResult rollbackAppliedIsrcCandidate(String adminUserId, Long candidateId, String notes) {
        assertAdmin(adminUserId);
        TrackIdentityCandidateStore.Entry candidate = candidateStore.findById(candidateId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "track identity candidate was not found."));
        if (!"isrc".equals(candidate.candidateKind())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only ISRC candidates can be rolled back.");
        }
        if (!TrackIdentityCandidateStore.STATUS_APPLIED.equals(candidate.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only applied candidates can be rolled back.");
        }
        String isrc = candidate.candidateValue();
        if (isrc == null || isrc.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "candidate ISRC is empty.");
        }

        List<Long> targetTrackIds = auditStore.findByCandidateIdAndAction(
                candidate.id(),
                TrackIdentityCandidateAuditStore.ACTION_APPLY
            )
            .stream()
            .filter(entry -> TrackIdentityCandidateAuditStore.STATUS_APPLIED.equals(entry.status()))
            .map(TrackIdentityCandidateAuditStore.Entry::emsCollectedTrackId)
            .filter(trackId -> trackId != null)
            .distinct()
            .toList();
        List<Long> clearedTrackIds = new ArrayList<>();
        List<Long> skippedTrackIds = new ArrayList<>();
        for (Long trackId : targetTrackIds) {
            int rows = trackRepository.clearIsrcIfMatches(trackId, isrc);
            if (rows > 0) {
                clearedTrackIds.add(trackId);
                auditStore.save(new TrackIdentityCandidateAuditStore.Draft(
                    candidate.id(),
                    TrackIdentityCandidateAuditStore.ACTION_ROLLBACK,
                    trackId,
                    isrc,
                    isrc,
                    null,
                    TrackIdentityCandidateAuditStore.STATUS_ROLLED_BACK,
                    "cleared ISRC applied by candidate audit",
                    adminUserId,
                    Instant.now()
                ));
            } else {
                skippedTrackIds.add(trackId);
                auditStore.save(new TrackIdentityCandidateAuditStore.Draft(
                    candidate.id(),
                    TrackIdentityCandidateAuditStore.ACTION_ROLLBACK,
                    trackId,
                    isrc,
                    null,
                    null,
                    TrackIdentityCandidateAuditStore.STATUS_REVIEW_REQUIRED,
                    "current ISRC did not match candidate value; skipped automatic rollback",
                    adminUserId,
                    Instant.now()
                ));
            }
        }

        Instant now = Instant.now();
        if (targetTrackIds.isEmpty()) {
            auditStore.save(new TrackIdentityCandidateAuditStore.Draft(
                candidate.id(),
                TrackIdentityCandidateAuditStore.ACTION_REVIEW_REQUIRED,
                null,
                isrc,
                null,
                null,
                TrackIdentityCandidateAuditStore.STATUS_REVIEW_REQUIRED,
                "no applied audit rows found for rollback",
                adminUserId,
                now
            ));
        }
        String status = !clearedTrackIds.isEmpty() && skippedTrackIds.isEmpty()
            ? TrackIdentityCandidateStore.STATUS_ROLLED_BACK
            : TrackIdentityCandidateStore.STATUS_REVIEW_REQUIRED;
        String rollbackNote = rollbackNote(status, targetTrackIds, clearedTrackIds, skippedTrackIds, notes);
        TrackIdentityCandidateStore.Entry updated = candidateStore.updateStatus(
            candidate.id(),
            status,
            adminUserId,
            rollbackNote,
            now
        );
        return new IsrcRollbackResult(updated, targetTrackIds, clearedTrackIds, skippedTrackIds);
    }

    private List<EmsCollectedTrackEntity> findMatchingEmsTracks(String title, String artist) {
        if (title == null || title.isBlank()) {
            return List.of();
        }
        String normalizedTitle = title.trim();
        if (artist == null || artist.isBlank()) {
            return trackRepository.findByTitleIgnoreCase(normalizedTitle);
        }
        return trackRepository.findByTitleIgnoreCaseAndArtistNameIgnoreCase(normalizedTitle, artist.trim());
    }

    private String rollbackNote(
        String status,
        List<Long> targetTrackIds,
        List<Long> clearedTrackIds,
        List<Long> skippedTrackIds,
        String operatorNotes
    ) {
        String base = TrackIdentityCandidateStore.STATUS_ROLLED_BACK.equals(status)
            ? "rolled back track ids %s; skipped track ids %s".formatted(clearedTrackIds, skippedTrackIds)
            : "rollback review required; target track ids %s; skipped track ids %s".formatted(targetTrackIds, skippedTrackIds);
        if (operatorNotes == null || operatorNotes.isBlank()) {
            return base;
        }
        return "%s; operator notes: %s".formatted(base, operatorNotes.trim());
    }

    private boolean matchesFilter(
        TrackIdentityCandidateStore.Entry candidate,
        String source,
        String candidateKind
    ) {
        if (source != null && !source.isBlank() && !source.equals(candidate.source())) {
            return false;
        }
        if (candidateKind != null && !candidateKind.isBlank() && !candidateKind.equals(candidate.candidateKind())) {
            return false;
        }
        return true;
    }

    private List<TrackIdentityCandidateStore.Entry> persistCandidates(
        List<ExternalLookupCandidate> candidates,
        String title,
        String artist,
        String adminUserId,
        boolean persistCandidates
    ) {
        if (!persistCandidates) {
            return List.of();
        }
        Instant now = Instant.now();
        List<TrackIdentityCandidateStore.Entry> saved = new ArrayList<>();
        for (ExternalLookupCandidate candidate : candidates) {
            saved.add(candidateStore.save(new TrackIdentityCandidateStore.Draft(
                title,
                artist,
                candidate.source(),
                candidate.candidateKind(),
                candidate.candidateValue(),
                candidate.candidateScore(),
                candidate.metadataJson(),
                adminUserId,
                now
            )));
        }
        return saved;
    }

    private ExternalLookupCandidate toWikidataCandidate(
        WikidataEntitySearchResult result,
        String queryTitle,
        String queryArtist
    ) {
        String candidateArtist = qualityScorer.extractWikidataArtist(result.description());
        Double quality = qualityScorer.scoreFor(
            queryTitle,
            queryArtist,
            result.label(),
            candidateArtist,
            null
        );
        return new ExternalLookupCandidate(
            "wikidata",
            "wikidata_qid",
            result.id(),
            result.label(),
            result.description(),
            quality,
            serializeMetadata(result)
        );
    }

    private ExternalLookupCandidate toDiscogsCandidate(
        DiscogsSearchResult result,
        String queryTitle,
        String queryArtist
    ) {
        String description = discogsDescription(result);
        CandidateQualityScorer.DiscogsTitleParts parts = qualityScorer.parseDiscogsTitle(result.title());
        Double quality = qualityScorer.scoreFor(
            queryTitle,
            queryArtist,
            parts.title(),
            parts.artist(),
            null
        );
        return new ExternalLookupCandidate(
            "discogs",
            "discogs_master_id",
            String.valueOf(result.id()),
            result.title(),
            description,
            quality,
            serializeMetadata(result)
        );
    }

    private DiscogsReleaseMetadata resolveDiscogsReleaseMetadata(TrackIdentityCandidateStore.Entry candidate) {
        DiscogsReleaseMetadata searchMetadata = extractDiscogsReleaseMetadata(candidate);
        DiscogsReleaseMetadata detailMetadata = fetchDiscogsReleaseMetadata(candidate);
        return searchMetadata.merge(detailMetadata);
    }

    private DiscogsReleaseMetadata extractDiscogsReleaseMetadata(TrackIdentityCandidateStore.Entry candidate) {
        if (!"discogs".equalsIgnoreCase(candidate.source()) || candidate.metadata() == null) {
            return DiscogsReleaseMetadata.empty();
        }
        try {
            DiscogsSearchResult parsed = objectMapper.readValue(candidate.metadata(), DiscogsSearchResult.class);
            return new DiscogsReleaseMetadata(parsed.year(), parsed.country(), null);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return DiscogsReleaseMetadata.empty();
        }
    }

    private DiscogsReleaseMetadata fetchDiscogsReleaseMetadata(TrackIdentityCandidateStore.Entry candidate) {
        if (!"discogs".equalsIgnoreCase(candidate.source())) {
            return DiscogsReleaseMetadata.empty();
        }
        Integer candidateId = parseDiscogsCandidateId(candidate);
        if ("discogs_release_id".equalsIgnoreCase(candidate.candidateKind())) {
            DiscogsReleaseDetail release = discogsClient.getRelease(candidateId);
            return new DiscogsReleaseMetadata(release.year(), release.country(), primaryDiscogsLabel(release.labels()));
        }
        DiscogsMasterDetail master = discogsClient.getMaster(candidateId);
        if (master.mainRelease() == null || master.mainRelease() <= 0) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Discogs master detail did not include a main release id."
            );
        }
        DiscogsReleaseDetail release = discogsClient.getRelease(master.mainRelease());
        return new DiscogsReleaseMetadata(
            release.year() == null && master.year() != null ? String.valueOf(master.year()) : release.year(),
            release.country(),
            primaryDiscogsLabel(release.labels())
        );
    }

    private Integer parseDiscogsCandidateId(TrackIdentityCandidateStore.Entry candidate) {
        try {
            return Integer.parseInt(candidate.candidateValue());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Discogs candidate value must be a numeric id.");
        }
    }

    private String primaryDiscogsLabel(List<DiscogsReleaseLabel> labels) {
        return Optional.ofNullable(labels)
            .orElse(List.of())
            .stream()
            .map(DiscogsReleaseLabel::name)
            .filter(name -> name != null && !name.isBlank())
            .map(String::trim)
            .findFirst()
            .orElse(null);
    }

    private record DiscogsReleaseMetadata(String year, String country, String label) {
        static DiscogsReleaseMetadata empty() {
            return new DiscogsReleaseMetadata(null, null, null);
        }

        boolean hasAnyField() {
            return (year != null && !year.isBlank())
                || (country != null && !country.isBlank())
                || (label != null && !label.isBlank());
        }

        DiscogsReleaseMetadata merge(DiscogsReleaseMetadata detail) {
            if (detail == null) {
                return this;
            }
            return new DiscogsReleaseMetadata(
                firstNonBlank(detail.year(), year),
                firstNonBlank(detail.country(), country),
                firstNonBlank(detail.label(), label)
            );
        }

        private String firstNonBlank(String primary, String fallback) {
            if (primary != null && !primary.isBlank()) {
                return primary.trim();
            }
            if (fallback != null && !fallback.isBlank()) {
                return fallback.trim();
            }
            return null;
        }
    }

    private String discogsDescription(DiscogsSearchResult result) {
        List<String> parts = new ArrayList<>();
        if (result.country() != null && !result.country().isBlank()) {
            parts.add(result.country());
        }
        if (result.year() != null && !result.year().isBlank()) {
            parts.add(result.year());
        }
        return parts.isEmpty() ? null : String.join(" / ", parts);
    }

    private CanonicalRowLinkResult linkRowsToCanonicalTrack(
        TrackIdentityCandidateStore.Entry candidate,
        CanonicalTrackIdentityStore.UpsertResult canonicalResult
    ) {
        if (!"isrc".equalsIgnoreCase(candidate.candidateKind())) {
            return CanonicalRowLinkResult.empty();
        }
        String isrc = canonicalResult.identity().identityValue();
        Long canonicalTrackId = canonicalResult.canonicalTrack().canonicalTrackId();
        int emsLinked = trackRepository.linkCanonicalTrackByIsrc(isrc, canonicalTrackId);
        long emsConflicts = trackRepository.countCanonicalTrackConflictsByIsrc(isrc, canonicalTrackId);
        int pmsImportedLinked = pmsImportedTrackRepository.linkCanonicalTrackByIsrc(isrc, canonicalTrackId);
        long pmsImportedConflicts = pmsImportedTrackRepository.countCanonicalTrackConflictsByIsrc(
            isrc,
            canonicalTrackId
        );
        int pmsUserLinked = pmsUserTrackRepository.linkCanonicalTrackByIsrc(isrc, canonicalTrackId);
        long pmsUserConflicts = pmsUserTrackRepository.countCanonicalTrackConflictsByIsrc(isrc, canonicalTrackId);
        return new CanonicalRowLinkResult(
            emsLinked,
            emsConflicts,
            pmsImportedLinked,
            pmsImportedConflicts,
            pmsUserLinked,
            pmsUserConflicts
        );
    }

    private CanonicalLinkConflictRow toConflictRow(EmsCollectedTrackEntity track) {
        return new CanonicalLinkConflictRow(
            "ems_collected_track",
            String.valueOf(track.getId()),
            track.getCanonicalTrackId(),
            track.getTitle(),
            track.getArtistName(),
            track.getSourcePlatform(),
            track.getExternalTrackId(),
            track.getIsrc()
        );
    }

    private CanonicalLinkConflictRow toConflictRow(PmsImportedTrackEntity track) {
        return new CanonicalLinkConflictRow(
            "pms_imported_track",
            track.getTrackId(),
            track.getCanonicalTrackId(),
            track.getTitle(),
            track.getArtistName(),
            track.getSourcePlatform(),
            track.getExternalTrackId(),
            track.getIsrc()
        );
    }

    private CanonicalLinkConflictRow toConflictRow(PmsUserTrackEntity track) {
        return new CanonicalLinkConflictRow(
            "pms_user_track",
            track.getTrackId(),
            track.getCanonicalTrackId(),
            track.getTitle(),
            track.getArtistName(),
            track.getSourcePlatform(),
            track.getExternalTrackId(),
            track.getIsrc()
        );
    }

    private boolean isSupportedCanonicalIdentityKind(String candidateKind) {
        if (candidateKind == null) {
            return false;
        }
        String kind = candidateKind.trim().toLowerCase();
        return "isrc".equals(kind)
            || "mbid".equals(kind)
            || "musicbrainz_recording_id".equals(kind)
            || "wikidata_qid".equals(kind)
            || "discogs_master_id".equals(kind)
            || "discogs_release_id".equals(kind);
    }

    private boolean isPromotableCanonicalCandidate(TrackIdentityCandidateStore.Entry candidate) {
        if ("isrc".equalsIgnoreCase(candidate.candidateKind())) {
            return TrackIdentityCandidateStore.STATUS_APPLIED.equals(candidate.status());
        }
        return TrackIdentityCandidateStore.STATUS_ACCEPTED.equals(candidate.status())
            || TrackIdentityCandidateStore.STATUS_APPLIED.equals(candidate.status());
    }

    private Double normalizeScore(Integer score) {
        if (score == null) {
            return null;
        }
        return Math.min(1.0d, Math.max(0.0d, score / 100.0d));
    }

    private String serializeMetadata(Object recording) {
        try {
            return objectMapper.writeValueAsString(recording);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private void assertAdmin(String userId) {
        String normalizedEmail = authAccountStore.findByUserId(userId)
            .map(account -> account.normalizedEmail())
            .orElse("");
        if (!ADMIN_EMAIL.equals(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Metadata normalization admin access is restricted.");
        }
    }

    public record LookupResult(
        MusicBrainzRecordingSearchResponse response,
        List<TrackIdentityCandidateStore.Entry> savedCandidates
    ) {}

    public record ExternalLookupResult(
        String source,
        String title,
        String artist,
        List<ExternalLookupCandidate> candidates,
        List<TrackIdentityCandidateStore.Entry> savedCandidates
    ) {}

    public record ExternalLookupCandidate(
        String source,
        String candidateKind,
        String candidateValue,
        String label,
        String description,
        Double candidateScore,
        String metadataJson
    ) {}

    public record AutoAcceptResult(
        double threshold,
        int reviewedCount,
        List<TrackIdentityCandidateStore.Entry> accepted,
        List<TrackIdentityCandidateStore.Entry> skipped
    ) {}

    public record IsrcApplyResult(
        int reviewedCount,
        int isrcConsideredCount,
        List<AppliedCandidate> applied,
        List<TrackIdentityCandidateStore.Entry> noMatch,
        List<TrackIdentityCandidateStore.Entry> conflicts
    ) {}

    public record AppliedCandidate(
        TrackIdentityCandidateStore.Entry candidate,
        List<Long> updatedTrackIds,
        List<Long> conflictTrackIds
    ) {}

    public record CandidateAuditResult(
        TrackIdentityCandidateStore.Entry candidate,
        List<TrackIdentityCandidateAuditStore.Entry> entries
    ) {}

    public record CanonicalPromotionResult(
        TrackIdentityCandidateStore.Entry candidate,
        CanonicalTrackIdentityStore.CanonicalTrackEntry canonicalTrack,
        CanonicalTrackIdentityStore.IdentityEntry identity,
        boolean createdCanonicalTrack,
        boolean createdIdentity,
        CanonicalRowLinkResult links
    ) {}

    public record CanonicalRowLinkResult(
        int emsLinkedCount,
        long emsConflictCount,
        int pmsImportedLinkedCount,
        long pmsImportedConflictCount,
        int pmsUserLinkedCount,
        long pmsUserConflictCount
    ) {
        static CanonicalRowLinkResult empty() {
            return new CanonicalRowLinkResult(0, 0, 0, 0, 0, 0);
        }

        public long totalConflictCount() {
            return emsConflictCount + pmsImportedConflictCount + pmsUserConflictCount;
        }
    }

    public record CanonicalLinkConflictResult(
        TrackIdentityCandidateStore.Entry candidate,
        CanonicalTrackIdentityStore.IdentityEntry targetIdentity,
        List<CanonicalLinkConflictRow> rows
    ) {}

    public record CanonicalLinkConflictRow(
        String trackStore,
        String rowId,
        Long existingCanonicalTrackId,
        String title,
        String artistName,
        String sourcePlatform,
        String externalTrackId,
        String isrc
    ) {}

    public record IsrcRollbackResult(
        TrackIdentityCandidateStore.Entry candidate,
        List<Long> targetTrackIds,
        List<Long> clearedTrackIds,
        List<Long> skippedTrackIds
    ) {}
}
