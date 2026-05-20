package io.myforevermusic.api.modules.recommendation.application;

import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackEntity;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore.LibraryPlaylistState;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore.LibraryTrackState;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AudioFeatureCompletionAutoEnqueueService {

    private final AudioFeatureCompletionJobStore jobStore;

    public AudioFeatureCompletionAutoEnqueueService(AudioFeatureCompletionJobStore jobStore) {
        this.jobStore = jobStore;
    }

    public AutoEnqueueResult enqueuePmsMissingTracks(
        String userId,
        List<PmsUserLibraryStore.LibraryPlaylistState> playlists
    ) {
        Instant now = Instant.now();
        Counter counter = new Counter();
        if (playlists == null) {
            return counter.toResult();
        }
        for (LibraryPlaylistState playlist : playlists) {
            if (playlist == null || playlist.tracks() == null) {
                continue;
            }
            for (LibraryTrackState track : playlist.tracks()) {
                counter.scannedTrackCount++;
                if (track == null || track.trackId() == null || track.trackId().isBlank()) {
                    continue;
                }
                if (track.audioFeatures() != null && track.audioFeatures().isComplete()) {
                    continue;
                }
                enqueue(new AudioFeatureCompletionJobStore.Draft(
                    "pms_user_track",
                    track.trackId(),
                    userId,
                    100,
                    "pms_import",
                    now
                ), counter);
            }
        }
        return counter.toResult();
    }

    public AutoEnqueueResult enqueueEmsCollectedTrack(EmsCollectedTrackEntity track) {
        Counter counter = new Counter();
        counter.scannedTrackCount++;
        if (track == null || track.getId() == null) {
            return counter.toResult();
        }
        if (track.getAudioFeatures() != null && track.getAudioFeatures().isAudioFeaturesFilled()) {
            return counter.toResult();
        }
        enqueue(new AudioFeatureCompletionJobStore.Draft(
            "ems_collected_track",
            String.valueOf(track.getId()),
            null,
            60,
            "ems_collect",
            Instant.now()
        ), counter);
        return counter.toResult();
    }

    private void enqueue(AudioFeatureCompletionJobStore.Draft draft, Counter counter) {
        AudioFeatureCompletionJobStore.EnqueueOutcome outcome = jobStore.enqueueIfAbsent(draft);
        if (outcome.inserted()) {
            counter.enqueuedJobCount++;
        } else {
            counter.skippedExistingJobCount++;
        }
    }

    private static class Counter {
        private int scannedTrackCount;
        private int enqueuedJobCount;
        private int skippedExistingJobCount;

        AutoEnqueueResult toResult() {
            return new AutoEnqueueResult(scannedTrackCount, enqueuedJobCount, skippedExistingJobCount);
        }
    }

    public record AutoEnqueueResult(
        int scannedTrackCount,
        int enqueuedJobCount,
        int skippedExistingJobCount
    ) {}
}
