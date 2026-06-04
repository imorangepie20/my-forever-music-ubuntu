package io.myforevermusic.api.modules.ems.application;

import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsPoolEntryEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsPoolEntryRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.context.annotation.Profile("!local")
@Service
public class EmsPoolEntryProcessor {

    private final EmsPoolEntryRepository entryRepository;
    private final EmsCollectionService collectionService;
    private final Optional<ApplicationErrorLogService> errorLogService;

    public EmsPoolEntryProcessor(
        EmsPoolEntryRepository entryRepository,
        EmsCollectionService collectionService,
        Optional<ApplicationErrorLogService> errorLogService
    ) {
        this.entryRepository = entryRepository;
        this.collectionService = collectionService;
        this.errorLogService = errorLogService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EmsPoolEntryProcessResult processEntry(Long entryId, String userId) {
        EmsPoolEntryEntity entry = entryRepository.findById(entryId)
            .orElseThrow(() -> new IllegalArgumentException("EMS pool entry was not found: %s".formatted(entryId)));

        String status = entry.getStatus();
        if (!EmsPoolEntryEntity.STATUS_QUEUED.equals(status)
            && !EmsPoolEntryEntity.STATUS_FAILED.equals(status)
            && !EmsPoolEntryEntity.STATUS_RUNNING.equals(status)) {
            return new EmsPoolEntryProcessResult(entry.getEntryType(), 0, 0, null);
        }

        Instant now = Instant.now();
        entry.markRunning(now);
        entryRepository.saveAndFlush(entry);

        try {
            EmsCollectionService.EmsSearchPoolCollectionResult result = collectionService.collectSearchPoolEntry(userId, entry);
            entry.markCompleted(Instant.now());
            entryRepository.save(entry);
            return new EmsPoolEntryProcessResult(
                entry.getEntryType(),
                result.collectedPlaylistCount(),
                result.collectedTrackCount(),
                null
            );
        } catch (Exception e) {
            entry.markFailed(truncate(e.getMessage(), 1000), Instant.now());
            entryRepository.save(entry);
            errorLogService.ifPresent(service -> service.recordSchedulerFailure(
                "ems-pool-entry",
                "EMS pool entry processing failed entry_id=%s type=%s: %s".formatted(
                    entry.getId(),
                    entry.getEntryType(),
                    e.getMessage()
                ),
                e,
                null
            ));
            return new EmsPoolEntryProcessResult(entry.getEntryType(), 0, 0, e.getMessage());
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
