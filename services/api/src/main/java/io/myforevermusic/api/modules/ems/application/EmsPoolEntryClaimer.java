package io.myforevermusic.api.modules.ems.application;

import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsPoolEntryEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsPoolEntryRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.context.annotation.Profile("!local")
@Component
public class EmsPoolEntryClaimer {

    private final EmsPoolEntryRepository entryRepository;

    public EmsPoolEntryClaimer(EmsPoolEntryRepository entryRepository) {
        this.entryRepository = entryRepository;
    }

    @Transactional
    public List<Long> claim(Long runId, String status, int limit) {
        List<EmsPoolEntryEntity> entries = entryRepository.findClaimableEntriesForUpdate(runId, status, limit);
        if (entries.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now();
        for (EmsPoolEntryEntity entry : entries) {
            entry.markClaimed(now);
        }
        entryRepository.saveAll(entries);
        return entries.stream().map(EmsPoolEntryEntity::getId).toList();
    }

    @Transactional
    public int resetStuckRunningEntries(Long runId) {
        return entryRepository.updateStatusByRunIdAndStatus(
            runId,
            EmsPoolEntryEntity.STATUS_RUNNING,
            EmsPoolEntryEntity.STATUS_QUEUED,
            Instant.now()
        );
    }
}
