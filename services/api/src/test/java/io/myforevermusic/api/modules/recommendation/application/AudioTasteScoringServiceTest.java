package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AudioTasteScoringServiceTest {

    @Test
    void shouldScoreCandidateNearPositiveCentroidAboveFarCandidate() {
        AudioTasteScoringService scoring = new AudioTasteScoringService();
        AudioTasteProfileService.Profile profile = profile();

        AudioTasteScoringService.Score near = scoring.score(profile, feature("near", 0.70d, 0.70d, 0.70d, 120.0d));
        AudioTasteScoringService.Score far = scoring.score(profile, feature("far", 0.20d, 0.20d, 0.20d, 80.0d));

        assertThat(near.applied()).isTrue();
        assertThat(near.score()).isGreaterThan(far.score());
        assertThat(near.explanationTokens()).contains("energy_match", "tempo_match", "valence_match");
    }

    @Test
    void shouldNoOpWhenProfileIsNotApplicable() {
        AudioTasteScoringService scoring = new AudioTasteScoringService();
        AudioTasteProfileService.Profile profile = new AudioTasteProfileService.Profile(
            "user-1",
            "insufficient_data",
            false,
            0,
            0,
            100,
            AudioTasteProfileService.Centroid.empty(),
            AudioTasteProfileService.Centroid.empty(),
            new AudioTasteProfileService.Coverage(0, 0, 0.0d, 0, 0),
            List.of("Audio taste profile requires at least 10 positive feature-ready tracks."),
            Instant.parse("2026-05-21T00:00:00Z")
        );

        AudioTasteScoringService.Score score = scoring.score(profile, feature("track-a", 0.70d, 0.70d, 0.70d, 120.0d));

        assertThat(score.applied()).isFalse();
        assertThat(score.score()).isEqualTo(0.0d);
        assertThat(score.explanationTokens()).contains("audio_taste_not_applicable");
    }

    private AudioTasteProfileService.Profile profile() {
        return new AudioTasteProfileService.Profile(
            "user-1",
            "ok",
            true,
            12,
            1,
            100,
            new AudioTasteProfileService.Centroid(0.2d, 0.7d, 0.7d, 0.01d, 0.1d, 0.05d, 0.43d, 0.7d),
            new AudioTasteProfileService.Centroid(0.8d, 0.2d, 0.2d, 0.01d, 0.1d, 0.05d, 0.14d, 0.2d),
            new AudioTasteProfileService.Coverage(20, 15, 0.75d, 2, 1),
            List.of(),
            Instant.parse("2026-05-21T00:00:00Z")
        );
    }

    private AudioTasteTrackFeature feature(String trackId, double energy, double valence, double danceability, double tempo) {
        return new AudioTasteTrackFeature(
            "pms_user_track",
            trackId,
            "Title",
            "Artist",
            "spotify",
            "reccobeats_lookup",
            true,
            1.0d,
            "provider",
            0.2d,
            danceability,
            energy,
            0.01d,
            0.1d,
            0.05d,
            tempo,
            valence
        );
    }
}
