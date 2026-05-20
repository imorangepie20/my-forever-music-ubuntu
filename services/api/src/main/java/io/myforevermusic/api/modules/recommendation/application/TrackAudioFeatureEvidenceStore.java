package io.myforevermusic.api.modules.recommendation.application;

import java.time.Instant;
import java.util.List;

public interface TrackAudioFeatureEvidenceStore {

    List<StoredEvidence> saveAll(List<Draft> drafts);

    List<StoredEvidence> findByTrack(String trackScope, String trackId, int limit);

    record Draft(
        String trackScope,
        String trackId,
        String sourceName,
        String sourceClass,
        String sourceUrl,
        String evidenceKind,
        String evidencePayloadJson,
        double confidence,
        Instant collectedAt,
        Instant expiresAt
    ) {}

    record StoredEvidence(
        Long evidenceId,
        String trackScope,
        String trackId,
        String sourceName,
        String sourceClass,
        String sourceUrl,
        String evidenceKind,
        String evidencePayloadJson,
        double confidence,
        Instant collectedAt,
        Instant expiresAt
    ) {}
}
