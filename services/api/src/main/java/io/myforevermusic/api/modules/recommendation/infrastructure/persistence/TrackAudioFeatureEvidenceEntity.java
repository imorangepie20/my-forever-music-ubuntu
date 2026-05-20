package io.myforevermusic.api.modules.recommendation.infrastructure.persistence;

import io.myforevermusic.api.modules.recommendation.application.TrackAudioFeatureEvidenceStore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "track_audio_feature_evidence")
public class TrackAudioFeatureEvidenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "track_audio_feature_evidence_id")
    private Long evidenceId;

    @Column(name = "track_scope", nullable = false, length = 80)
    private String trackScope;

    @Column(name = "track_id", nullable = false, length = 200)
    private String trackId;

    @Column(name = "source_name", nullable = false, length = 80)
    private String sourceName;

    @Column(name = "source_class", nullable = false, length = 50)
    private String sourceClass;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(name = "evidence_kind", nullable = false, length = 80)
    private String evidenceKind;

    @Column(name = "evidence_payload_json", nullable = false, length = 8000)
    private String evidencePayloadJson;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected TrackAudioFeatureEvidenceEntity() {
    }

    public TrackAudioFeatureEvidenceEntity(TrackAudioFeatureEvidenceStore.Draft draft) {
        this.trackScope = draft.trackScope();
        this.trackId = draft.trackId();
        this.sourceName = draft.sourceName();
        this.sourceClass = draft.sourceClass();
        this.sourceUrl = draft.sourceUrl();
        this.evidenceKind = draft.evidenceKind();
        this.evidencePayloadJson = draft.evidencePayloadJson();
        this.confidence = draft.confidence();
        this.collectedAt = draft.collectedAt();
        this.expiresAt = draft.expiresAt();
    }

    public TrackAudioFeatureEvidenceStore.StoredEvidence toState() {
        return new TrackAudioFeatureEvidenceStore.StoredEvidence(
            evidenceId,
            trackScope,
            trackId,
            sourceName,
            sourceClass,
            sourceUrl,
            evidenceKind,
            evidencePayloadJson,
            confidence,
            collectedAt,
            expiresAt
        );
    }
}
