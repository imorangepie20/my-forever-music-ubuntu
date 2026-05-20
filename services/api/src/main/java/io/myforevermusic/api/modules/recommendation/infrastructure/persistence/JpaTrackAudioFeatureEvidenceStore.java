package io.myforevermusic.api.modules.recommendation.infrastructure.persistence;

import io.myforevermusic.api.modules.recommendation.application.TrackAudioFeatureEvidenceStore;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!local")
public class JpaTrackAudioFeatureEvidenceStore implements TrackAudioFeatureEvidenceStore {

    private final TrackAudioFeatureEvidenceRepository repository;

    public JpaTrackAudioFeatureEvidenceStore(TrackAudioFeatureEvidenceRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public List<StoredEvidence> saveAll(List<Draft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        return repository.saveAll(drafts.stream()
                .map(TrackAudioFeatureEvidenceEntity::new)
                .toList())
            .stream()
            .map(TrackAudioFeatureEvidenceEntity::toState)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredEvidence> findByTrack(String trackScope, String trackId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return repository.findByTrackScopeAndTrackIdOrderByCollectedAtDescEvidenceIdDesc(
                trackScope,
                trackId,
                Pageable.ofSize(limit)
            )
            .stream()
            .map(TrackAudioFeatureEvidenceEntity::toState)
            .toList();
    }
}
