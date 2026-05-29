package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import io.myforevermusic.api.modules.publiccuration.application.PublicCurationPlaylistStore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "public_curation_run")
public class PublicCurationRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "public_curation_run_id")
    private Long runId;

    @Column(name = "playlist_id", nullable = false)
    private Long playlistId;

    @Column(name = "prompt", columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "filter_snapshot_json", columnDefinition = "TEXT")
    private String filterSnapshotJson;

    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;

    @Column(name = "selected_count", nullable = false)
    private int selectedCount;

    @Column(name = "model_version", length = 160)
    private String modelVersion;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "score_summary_json", columnDefinition = "TEXT")
    private String scoreSummaryJson;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected PublicCurationRunEntity() {
    }

    public PublicCurationRunEntity(Long playlistId, PublicCurationPlaylistStore.RunDraft draft) {
        this.playlistId = playlistId;
        this.prompt = draft.prompt();
        this.filterSnapshotJson = draft.filterSnapshotJson();
        this.candidateCount = draft.candidateCount();
        this.selectedCount = draft.selectedCount();
        this.modelVersion = draft.modelVersion();
        this.status = draft.status();
        this.scoreSummaryJson = draft.scoreSummaryJson();
        this.errorMessage = draft.errorMessage();
        this.startedAt = draft.startedAt();
        this.completedAt = draft.completedAt();
    }

    public PublicCurationPlaylistStore.StoredRun toState() {
        return new PublicCurationPlaylistStore.StoredRun(
            runId,
            playlistId,
            prompt,
            filterSnapshotJson,
            candidateCount,
            selectedCount,
            modelVersion,
            status,
            scoreSummaryJson,
            errorMessage,
            startedAt,
            completedAt
        );
    }
}
