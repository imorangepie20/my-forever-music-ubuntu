package io.myforevermusic.api.modules.ems.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService.EmsTidalHomeTrackBackfillResult;
import io.myforevermusic.api.modules.ems.application.EmsTidalHomeTrackBackfillScheduler.EmsTidalHomeTrackBackfillRun;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EmsTidalHomeTrackBackfillSchedulerTest {

    private final EmsCollectionService collectionService = mock(EmsCollectionService.class);
    private final ApplicationErrorLogService errorLogService = mock(ApplicationErrorLogService.class);

    @Test
    void shouldBackfillOnlyConfiguredBatchSize() {
        when(collectionService.getPendingTidalHomeTrackBackfillPlaylists(3))
            .thenReturn(List.of(playlist(1L), playlist(2L)));
        when(collectionService.backfillTidalHomePlaylistTracks(1L)).thenReturn(result(1L, 20));
        when(collectionService.backfillTidalHomePlaylistTracks(2L)).thenReturn(result(2L, 30));

        EmsTidalHomeTrackBackfillRun run = scheduler().runNow();

        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.processedPlaylistCount()).isEqualTo(2);
        assertThat(run.linkedTrackCount()).isEqualTo(50);
    }

    @Test
    void shouldContinueAndRecordErrorWhenOnePlaylistFails() {
        when(collectionService.getPendingTidalHomeTrackBackfillPlaylists(3))
            .thenReturn(List.of(playlist(1L), playlist(2L)));
        when(collectionService.backfillTidalHomePlaylistTracks(1L))
            .thenThrow(new IllegalStateException("TIDAL track endpoint rejected token"));
        when(collectionService.backfillTidalHomePlaylistTracks(2L)).thenReturn(result(2L, 30));

        EmsTidalHomeTrackBackfillRun run = scheduler(errorLogService).runNow();

        assertThat(run.status()).isEqualTo("completed_with_failures");
        assertThat(run.processedPlaylistCount()).isEqualTo(1);
        assertThat(run.failedPlaylistCount()).isEqualTo(1);
        verify(errorLogService).recordSchedulerFailure(
            eq("ems-tidal-home-track-backfill"),
            contains("playlist_id=1"),
            any(IllegalStateException.class),
            contains("\"playlistId\":1")
        );
    }

    private EmsTidalHomeTrackBackfillScheduler scheduler() {
        return scheduler(null);
    }

    private EmsTidalHomeTrackBackfillScheduler scheduler(ApplicationErrorLogService errorLog) {
        EmsTidalHomeTrackBackfillProperties properties = new EmsTidalHomeTrackBackfillProperties();
        properties.setBatchSize(3);
        return new EmsTidalHomeTrackBackfillScheduler(
            collectionService,
            properties,
            Optional.ofNullable(errorLog)
        );
    }

    private EmsCollectedPlaylistEntity playlist(Long id) {
        EmsCollectedPlaylistEntity playlist = new EmsCollectedPlaylistEntity(
            "playlist-" + id,
            "Playlist " + id,
            "tidal",
            "",
            "",
            null,
            null,
            null,
            30,
            "public_pool",
            "POPULAR_PLAYLISTS",
            Instant.parse("2026-06-01T00:00:00Z")
        );
        ReflectionTestUtils.setField(playlist, "id", id);
        return playlist;
    }

    private EmsTidalHomeTrackBackfillResult result(Long playlistId, int linkedTracks) {
        return new EmsTidalHomeTrackBackfillResult(
            playlistId,
            linkedTracks,
            Instant.parse("2026-06-01T00:00:00Z")
        );
    }
}
