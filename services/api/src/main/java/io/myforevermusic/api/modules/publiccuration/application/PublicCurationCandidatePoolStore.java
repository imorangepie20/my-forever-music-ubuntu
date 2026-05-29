package io.myforevermusic.api.modules.publiccuration.application;

import java.util.List;
import java.util.Map;

public interface PublicCurationCandidatePoolStore {

    List<CandidateTrack> findCandidates(CandidateQuery query);

    record CandidateQuery(
        int limit,
        boolean tidalReadyRequired
    ) {
    }

    record CandidateTrack(
        String sourceScope,
        String sourceId,
        String title,
        String artistName,
        String albumTitle,
        Integer durationMs,
        String isrc,
        String sourcePlatform,
        String tidalTrackId,
        String tidalUri,
        String tidalExternalUrl,
        Map<String, Double> audioFeatures,
        List<String> genres,
        List<String> tags
    ) {
    }
}
