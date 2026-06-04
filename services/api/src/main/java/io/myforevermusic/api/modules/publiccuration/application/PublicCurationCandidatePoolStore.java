package io.myforevermusic.api.modules.publiccuration.application;

import java.util.List;
import java.util.Map;

public interface PublicCurationCandidatePoolStore {

    String PLAYBACK_RESOLUTION_NATIVE_TIDAL = "native_tidal";
    String PLAYBACK_RESOLUTION_RESOLVED_TO_TIDAL = "resolved_to_tidal";
    String PLAYBACK_RESOLUTION_UNRESOLVED = "unresolved";

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
        String imageUrl,
        Integer durationMs,
        String isrc,
        String sourcePlatform,
        String tidalTrackId,
        String tidalUri,
        String tidalExternalUrl,
        Map<String, Double> audioFeatures,
        String audioFeatureSource,
        Double audioFeatureConfidence,
        boolean audioFeaturesFilled,
        List<String> genres,
        List<String> tags,
        List<String> metadataTags,
        SourcePlaylistSignals sourcePlaylistSignals,
        AudienceResponse audienceResponse,
        Double freshness,
        String playbackResolutionStatus
    ) {
        public boolean hasTidalPlaybackTarget() {
            return hasText(tidalTrackId) && hasText(tidalUri);
        }

        public CandidateTrack withResolvedTidalTarget(
            String resolvedTidalTrackId,
            String resolvedTidalUri,
            String resolvedTidalExternalUrl,
            String resolvedImageUrl,
            Integer resolvedDurationMs
        ) {
            return new CandidateTrack(
                sourceScope,
                sourceId,
                title,
                artistName,
                albumTitle,
                hasText(resolvedImageUrl) ? resolvedImageUrl : imageUrl,
                resolvedDurationMs == null ? durationMs : resolvedDurationMs,
                isrc,
                sourcePlatform,
                resolvedTidalTrackId,
                resolvedTidalUri,
                resolvedTidalExternalUrl,
                audioFeatures,
                audioFeatureSource,
                audioFeatureConfidence,
                audioFeaturesFilled,
                genres,
                tags,
                metadataTags,
                sourcePlaylistSignals,
                audienceResponse,
                freshness,
                PLAYBACK_RESOLUTION_RESOLVED_TO_TIDAL
            );
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }

    record SourcePlaylistSignals(
        int playlistCount,
        Integer maxFollowersCount,
        List<String> titles,
        List<String> descriptions,
        List<String> curators,
        List<String> collectionSources,
        List<String> searchQueries
    ) {
    }

    record AudienceResponse(
        int playStartedCount,
        int playCompletedCount,
        int skipCount
    ) {
    }
}
