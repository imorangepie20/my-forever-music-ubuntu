package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.platform.application.LastFmProperties;
import io.myforevermusic.api.modules.platform.infrastructure.lastfm.LastFmWebApiClient;
import io.myforevermusic.api.modules.recommendation.infrastructure.local.InMemoryTrackAudioFeatureEvidenceStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LastFmAudioFeatureInferenceServiceTest {

    @Test
    void shouldInferPartialAudioFeaturesFromTrackAndArtistTagsAndPersistEvidence() {
        InMemoryTrackAudioFeatureEvidenceStore evidenceStore = new InMemoryTrackAudioFeatureEvidenceStore();
        LastFmAudioFeatureInferenceService service = new LastFmAudioFeatureInferenceService(
            new FakeLastFmWebApiClient(),
            evidenceStore,
            new ObjectMapper()
        );
        ReflectionTestUtils.setField(service, "minConfidence", 0.5d);

        LastFmAudioFeatureInferenceService.InferredAudioFeatureSnapshot snapshot = service.infer(
            new LastFmAudioFeatureInferenceService.AudioFeatureInferenceTarget(
                "pms_user_track",
                "pms-track-001",
                "Digital Love",
                "Daft Punk",
                212_000,
                "https://www.last.fm/music/Daft+Punk/_/Digital+Love"
            )
        ).orElseThrow();

        assertThat(snapshot.source()).isEqualTo("lastfm_track_tag_inferred");
        assertThat(snapshot.sourceClass()).isEqualTo("tag_inferred");
        assertThat(snapshot.confidence()).isGreaterThanOrEqualTo(0.5d);
        assertThat(snapshot.audioFeaturesFilled()).isFalse();
        assertThat(snapshot.durationMs()).isEqualTo(212_000);
        assertThat(snapshot.danceability()).isGreaterThan(0.65d);
        assertThat(snapshot.energy()).isGreaterThan(0.6d);
        assertThat(snapshot.acousticness()).isLessThan(0.35d);
        assertThat(snapshot.tempo()).isBetween(110.0d, 125.0d);
        assertThat(snapshot.modelVersion()).isEqualTo("lastfm-tag-rules-v1");

        List<TrackAudioFeatureEvidenceStore.StoredEvidence> evidence = evidenceStore.findByTrack(
            "pms_user_track",
            "pms-track-001",
            10
        );
        assertThat(evidence).hasSize(4);
        assertThat(evidence).extracting(TrackAudioFeatureEvidenceStore.StoredEvidence::evidenceKind)
            .contains("track_tag", "artist_tag", "tag_inference_result");
        assertThat(evidence).allSatisfy(entry -> {
            assertThat(entry.sourceName()).isEqualTo("lastfm");
            assertThat(entry.sourceClass()).isEqualTo("tag_inferred");
        });
    }

    private static final class FakeLastFmWebApiClient extends LastFmWebApiClient {

        private FakeLastFmWebApiClient() {
            super(new LastFmProperties(), new ObjectMapper());
        }

        @Override
        public List<LastFmTag> getTrackTopTags(String artistName, String trackName) {
            return List.of(
                new LastFmTag("electronic", 100L, "https://www.last.fm/tag/electronic"),
                new LastFmTag("dance", 80L, "https://www.last.fm/tag/dance")
            );
        }

        @Override
        public List<LastFmTag> getArtistTopTags(String artistName) {
            return List.of(new LastFmTag("synthpop", 50L, "https://www.last.fm/tag/synthpop"));
        }
    }
}
