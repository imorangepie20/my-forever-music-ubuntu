package io.myforevermusic.api.modules.recommendation.infrastructure.local;

import io.myforevermusic.api.modules.recommendation.application.TrackAudioFeatureEvidenceStore;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class InMemoryTrackAudioFeatureEvidenceStore implements TrackAudioFeatureEvidenceStore {

    private final AtomicLong sequence = new AtomicLong(1);
    private final Map<String, List<StoredEvidence>> evidenceByTrack = new ConcurrentHashMap<>();

    @Override
    public List<StoredEvidence> saveAll(List<Draft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        return drafts.stream()
            .map(this::save)
            .toList();
    }

    @Override
    public List<StoredEvidence> findByTrack(String trackScope, String trackId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return evidenceByTrack.getOrDefault(identity(trackScope, trackId), List.of()).stream()
            .sorted(Comparator.comparing(StoredEvidence::collectedAt).reversed()
                .thenComparing(StoredEvidence::evidenceId, Comparator.reverseOrder()))
            .limit(limit)
            .toList();
    }

    private StoredEvidence save(Draft draft) {
        StoredEvidence evidence = new StoredEvidence(
            sequence.getAndIncrement(),
            draft.trackScope(),
            draft.trackId(),
            draft.sourceName(),
            draft.sourceClass(),
            draft.sourceUrl(),
            draft.evidenceKind(),
            draft.evidencePayloadJson(),
            draft.confidence(),
            draft.collectedAt(),
            draft.expiresAt()
        );
        evidenceByTrack.computeIfAbsent(identity(draft.trackScope(), draft.trackId()), ignored -> new CopyOnWriteArrayList<>())
            .add(evidence);
        return evidence;
    }

    private String identity(String trackScope, String trackId) {
        return trackScope + "\n" + trackId;
    }
}
