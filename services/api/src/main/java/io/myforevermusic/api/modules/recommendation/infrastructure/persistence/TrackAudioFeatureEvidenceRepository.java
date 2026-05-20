package io.myforevermusic.api.modules.recommendation.infrastructure.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrackAudioFeatureEvidenceRepository
    extends JpaRepository<TrackAudioFeatureEvidenceEntity, Long> {

    List<TrackAudioFeatureEvidenceEntity> findByTrackScopeAndTrackIdOrderByCollectedAtDescEvidenceIdDesc(
        String trackScope,
        String trackId,
        Pageable pageable
    );
}
