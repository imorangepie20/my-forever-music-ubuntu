package io.myforevermusic.api.modules.ems.application;

import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsPoolEntryEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsPoolEntryRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsPoolIngestRunEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsPoolIngestRunRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@org.springframework.context.annotation.Profile("!local")
@Service
public class EmsPoolIngestService {

    private static final String ADMIN_EMAIL = "jowoosungtidal@gmail.com";

    private final EmsPoolIngestRunRepository runRepository;
    private final EmsPoolEntryRepository entryRepository;
    private final EmsPoolEntryProcessor entryProcessor;
    private final EmsPoolEntryClaimer entryClaimer;
    private final EmsCollectionService collectionService;
    private final AuthAccountStore authAccountStore;
    private final ApplicationEventPublisher eventPublisher;

    public EmsPoolIngestService(
        EmsPoolIngestRunRepository runRepository,
        EmsPoolEntryRepository entryRepository,
        EmsPoolEntryProcessor entryProcessor,
        EmsPoolEntryClaimer entryClaimer,
        EmsCollectionService collectionService,
        AuthAccountStore authAccountStore,
        ApplicationEventPublisher eventPublisher
    ) {
        this.runRepository = runRepository;
        this.entryRepository = entryRepository;
        this.entryProcessor = entryProcessor;
        this.entryClaimer = entryClaimer;
        this.collectionService = collectionService;
        this.authAccountStore = authAccountStore;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<EmsPoolRunSnapshot> listRunsForAdmin(String userId) {
        assertAdmin(userId);
        return runRepository.findTop20ByOrderByCreatedAtDesc().stream()
            .map(this::toRunSnapshot)
            .toList();
    }

    @Transactional(readOnly = true)
    public EmsPoolRunDetailSnapshot getRunForAdmin(String userId, Long runId) {
        assertAdmin(userId);
        EmsPoolIngestRunEntity run = getRun(runId);
        List<EmsPoolEntrySnapshot> entries = entryRepository.findByRunId(runId, PageRequest.of(0, 100)).stream()
            .map(this::toEntrySnapshot)
            .toList();
        return new EmsPoolRunDetailSnapshot(toRunSnapshot(run), entries);
    }

    public EmsPoolRunSnapshot processRunForAdmin(String userId, Long runId) {
        assertAdmin(userId);
        entryClaimer.resetStuckRunningEntries(runId);
        processRun(runId, true);
        return toRunSnapshot(getRun(runId));
    }

    @Transactional
    public void deleteRunForAdmin(String userId, Long runId) {
        assertAdmin(userId);
        if (!runRepository.existsById(runId)) {
            throw new IllegalArgumentException(
                "EMS pool ingest run was not found: %s".formatted(runId)
            );
        }
        runRepository.deleteById(runId);
    }

    public int cleanupEmptyPlaylistsForAdmin(String userId) {
        assertAdmin(userId);
        return collectionService.cleanupEmptyCollectedPlaylists();
    }

    public void assertAdminAccess(String userId) {
        assertAdmin(userId);
    }

    @Transactional
    public EmsPoolEntrySnapshot retryEntryForAdmin(String userId, Long runId, Long entryId) {
        assertAdmin(userId);
        EmsPoolEntryEntity entry = entryRepository.findById(entryId)
            .orElseThrow(() -> new IllegalArgumentException(
                "EMS pool entry was not found: %s".formatted(entryId)
            ));
        if (!entry.getRun().getId().equals(runId)) {
            throw new IllegalArgumentException(
                "EMS pool entry %d does not belong to run %d.".formatted(entryId, runId)
            );
        }
        entry.markQueued(Instant.now());
        entryRepository.save(entry);
        eventPublisher.publishEvent(new EmsPoolRunQueuedEvent(runId));
        return toEntrySnapshot(entry);
    }

    public void processOldestQueuedRun() {
        runRepository.findFirstByStatusOrderByCreatedAtAsc(EmsPoolIngestRunEntity.STATUS_QUEUED)
            .ifPresent(run -> processRun(run.getId()));
    }

    public void processRun(Long runId) {
        processRun(runId, false);
    }

    private void processRun(Long runId, boolean includeFailedEntries) {
        EmsPoolIngestRunEntity run = markRunRunning(runId);
        String lastError = null;
        int collectedPlaylistCount = run.getCollectedPlaylistCount();
        int collectedTrackCount = run.getCollectedTrackCount();

        while (true) {
            List<Long> claimedEntryIds = entryClaimer.claim(runId, EmsPoolEntryEntity.STATUS_QUEUED, 10);
            if (claimedEntryIds.isEmpty() && includeFailedEntries) {
                claimedEntryIds = entryClaimer.claim(runId, EmsPoolEntryEntity.STATUS_FAILED, 10);
            }
            if (claimedEntryIds.isEmpty()) {
                break;
            }

            for (Long entryId : claimedEntryIds) {
                EmsPoolEntryProcessResult result = entryProcessor.processEntry(entryId, run.getRequestedByUserId());
                collectedPlaylistCount += result.collectedPlaylistCount();
                collectedTrackCount += result.collectedTrackCount();
                if (result.failed()) {
                    lastError = result.error();
                }
                refreshProgress(runId, collectedPlaylistCount, collectedTrackCount, lastError);
            }
        }

        markRunCompleted(runId, lastError);
    }

    @Transactional
    EmsPoolIngestRunEntity markRunRunning(Long runId) {
        EmsPoolIngestRunEntity run = getRun(runId);
        if (EmsPoolIngestRunEntity.STATUS_COMPLETED.equals(run.getStatus()) && run.getFailedEntries() == 0) {
            return run;
        }
        run.markRunning(Instant.now());
        return runRepository.save(run);
    }

    @Transactional
    void refreshProgress(Long runId, int collectedPlaylistCount, int collectedTrackCount, String lastError) {
        EmsPoolIngestRunEntity run = getRun(runId);
        int processedPlaylists = (int) entryRepository.countByRunIdAndEntryTypeAndStatus(
            runId,
            EmsPoolEntryEntity.TYPE_PLAYLIST,
            EmsPoolEntryEntity.STATUS_COMPLETED
        );
        int processedTracks = (int) entryRepository.countByRunIdAndEntryTypeAndStatus(
            runId,
            EmsPoolEntryEntity.TYPE_TRACK,
            EmsPoolEntryEntity.STATUS_COMPLETED
        );
        int failed = (int) entryRepository.countByRunIdAndStatus(runId, EmsPoolEntryEntity.STATUS_FAILED);
        run.updateProgress(
            processedPlaylists,
            processedTracks,
            failed,
            collectedPlaylistCount,
            collectedTrackCount,
            truncate(lastError, 1000),
            Instant.now()
        );
        runRepository.save(run);
    }

    @Transactional
    void markRunCompleted(Long runId, String lastError) {
        EmsPoolIngestRunEntity run = getRun(runId);
        refreshProgress(runId, run.getCollectedPlaylistCount(), run.getCollectedTrackCount(), lastError);
        run = getRun(runId);
        run.markCompleted(Instant.now());
        runRepository.save(run);
    }

    private EmsPoolIngestRunEntity getRun(Long runId) {
        return runRepository.findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("EMS pool ingest run was not found: %s".formatted(runId)));
    }

    private void assertAdmin(String userId) {
        String normalizedEmail = authAccountStore.findByUserId(userId)
            .map(account -> account.normalizedEmail())
            .orElse("");
        if (!ADMIN_EMAIL.equals(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "EMS pool admin access is restricted.");
        }
    }

    private EmsPoolRunSnapshot toRunSnapshot(EmsPoolIngestRunEntity run) {
        int totalEntries = run.getTotalPlaylistEntries() + run.getTotalTrackEntries();
        int processedEntries = run.getProcessedPlaylistEntries() + run.getProcessedTrackEntries() + run.getFailedEntries();
        double progressRatio = totalEntries == 0 ? 1.0 : (double) processedEntries / totalEntries;
        return new EmsPoolRunSnapshot(
            run.getId(),
            run.getRequestedByUserId(),
            run.getSourcePlatform(),
            run.getSearchQuery(),
            run.getCollectionSource(),
            run.getStatus(),
            run.getTotalPlaylistEntries(),
            run.getTotalTrackEntries(),
            run.getProcessedPlaylistEntries(),
            run.getProcessedTrackEntries(),
            run.getFailedEntries(),
            run.getCollectedPlaylistCount(),
            run.getCollectedTrackCount(),
            Math.min(1.0, progressRatio),
            run.getLastError(),
            run.getCreatedAt(),
            run.getStartedAt(),
            run.getCompletedAt(),
            run.getUpdatedAt()
        );
    }

    private EmsPoolEntrySnapshot toEntrySnapshot(EmsPoolEntryEntity entry) {
        return new EmsPoolEntrySnapshot(
            entry.getId(),
            entry.getEntryType(),
            entry.getSourcePlatform(),
            entry.getExternalId(),
            entry.getTitle(),
            entry.getArtistName(),
            entry.getStatus(),
            entry.getAttempts(),
            entry.getLastError(),
            entry.getCreatedAt(),
            entry.getUpdatedAt(),
            entry.getProcessedAt()
        );
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record EmsPoolRunSnapshot(
        Long runId,
        String requestedByUserId,
        String sourcePlatform,
        String searchQuery,
        String collectionSource,
        String status,
        int totalPlaylistEntries,
        int totalTrackEntries,
        int processedPlaylistEntries,
        int processedTrackEntries,
        int failedEntries,
        int collectedPlaylistCount,
        int collectedTrackCount,
        double progressRatio,
        String lastError,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt
    ) {}

    public record EmsPoolEntrySnapshot(
        Long entryId,
        String entryType,
        String sourcePlatform,
        String externalId,
        String title,
        String artistName,
        String status,
        int attempts,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant processedAt
    ) {}

    public record EmsPoolRunDetailSnapshot(
        EmsPoolRunSnapshot run,
        List<EmsPoolEntrySnapshot> entries
    ) {}
}
