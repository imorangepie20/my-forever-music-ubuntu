package io.myforevermusic.api.modules.publiccuration.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PublicCurationPlaylistStore {

    StoredPlaylist createDraft(CreateDraft draft);

    StoredPlaylist publish(Long playlistId, Instant publishedAt);

    void delete(Long playlistId);

    List<StoredPlaylistSummary> findRecentForAdmin(int limit);

    Optional<StoredPlaylist> findPublishedBySlug(String slug);

    record CreateDraft(
        String slug,
        String title,
        String subtitle,
        String description,
        String prompt,
        String filterSnapshotJson,
        String coverStyle,
        String modelVersion,
        int trackCount,
        long durationMs,
        String createdByAdminUserId,
        Instant createdAt,
        List<TrackDraft> tracks,
        RunDraft run
    ) {
    }

    record TrackDraft(
        int trackOrder,
        String sourceTrackScope,
        String sourceTrackId,
        String title,
        String artistName,
        String albumTitle,
        String imageUrl,
        Integer durationMs,
        String isrc,
        String tidalTrackId,
        String tidalUri,
        String tidalExternalUrl,
        double score,
        String scoreBreakdownJson,
        String reason
    ) {
    }

    record RunDraft(
        String prompt,
        String filterSnapshotJson,
        int candidateCount,
        int selectedCount,
        String modelVersion,
        String status,
        String scoreSummaryJson,
        String errorMessage,
        Instant startedAt,
        Instant completedAt
    ) {
    }

    record StoredPlaylist(
        Long playlistId,
        String slug,
        String title,
        String subtitle,
        String description,
        String prompt,
        String filterSnapshotJson,
        String status,
        String coverStyle,
        String modelVersion,
        int trackCount,
        long durationMs,
        Instant publishedAt,
        String createdByAdminUserId,
        Instant createdAt,
        Instant updatedAt,
        List<StoredTrack> tracks,
        StoredRun run
    ) {
        public StoredPlaylistSummary toSummary() {
            return new StoredPlaylistSummary(
                playlistId,
                slug,
                title,
                subtitle,
                status,
                coverStyle,
                modelVersion,
                trackCount,
                durationMs,
                publishedAt,
                createdByAdminUserId,
                createdAt,
                updatedAt
            );
        }
    }

    record StoredPlaylistSummary(
        Long playlistId,
        String slug,
        String title,
        String subtitle,
        String status,
        String coverStyle,
        String modelVersion,
        int trackCount,
        long durationMs,
        Instant publishedAt,
        String createdByAdminUserId,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    record StoredTrack(
        Long trackId,
        Long playlistId,
        int trackOrder,
        String sourceTrackScope,
        String sourceTrackId,
        String title,
        String artistName,
        String albumTitle,
        String imageUrl,
        Integer durationMs,
        String isrc,
        String tidalTrackId,
        String tidalUri,
        String tidalExternalUrl,
        double score,
        String scoreBreakdownJson,
        String reason,
        Instant createdAt
    ) {
    }

    record StoredRun(
        Long runId,
        Long playlistId,
        String prompt,
        String filterSnapshotJson,
        int candidateCount,
        int selectedCount,
        String modelVersion,
        String status,
        String scoreSummaryJson,
        String errorMessage,
        Instant startedAt,
        Instant completedAt
    ) {
    }
}
