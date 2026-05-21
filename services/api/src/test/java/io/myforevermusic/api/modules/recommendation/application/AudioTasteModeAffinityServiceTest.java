package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AudioTasteModeAffinityServiceTest {

    private final AudioTasteModeAffinityService service = new AudioTasteModeAffinityService();

    @Test
    void shouldReturnNearestModeForHeavyProfileCandidate() {
        AudioTasteProfileService.Profile profile = profile(
            "heavy",
            List.of(
                mode(
                    "mode-1",
                    "high_energy_bright_danceable",
                    0.18d,
                    0.76d,
                    0.81d,
                    0.03d,
                    0.13d,
                    0.06d,
                    0.58d,
                    0.72d,
                    0.74d,
                    78
                ),
                mode(
                    "mode-2",
                    "low_energy_dark_acoustic_slow",
                    0.82d,
                    0.32d,
                    0.24d,
                    0.01d,
                    0.12d,
                    0.04d,
                    0.18d,
                    0.30d,
                    0.68d,
                    65
                )
            )
        );

        AudioTasteModeAffinityService.TasteModeAffinity affinity = service.findNearestMode(
            profile,
            candidate("candidate-1", 0.20d, 0.78d, 0.82d, 0.02d, 0.12d, 0.05d, 142.0d, 0.70d)
        ).orElseThrow();

        assertThat(affinity.applied()).isTrue();
        assertThat(affinity.modeId()).isEqualTo("mode-1");
        assertThat(affinity.label()).isEqualTo("high_energy_bright_danceable");
        assertThat(affinity.similarity()).isGreaterThan(0.80d);
        assertThat(affinity.distance()).isBetween(0.0d, 0.20d);
        assertThat(affinity.tokens()).contains(
            "mode_energy_match",
            "mode_valence_match",
            "mode_danceability_match"
        );
    }

    @Test
    void shouldPreferHigherConfidenceThenTrackCountThenModeIdOnDistanceTie() {
        AudioTasteProfileService.Profile profile = profile(
            "heavy",
            List.of(
                mode(
                    "mode-b",
                    "same_shape_lower_confidence",
                    0.20d,
                    0.70d,
                    0.80d,
                    0.01d,
                    0.10d,
                    0.05d,
                    0.50d,
                    0.70d,
                    0.60d,
                    120
                ),
                mode(
                    "mode-a",
                    "same_shape_higher_confidence",
                    0.20d,
                    0.70d,
                    0.80d,
                    0.01d,
                    0.10d,
                    0.05d,
                    0.50d,
                    0.70d,
                    0.80d,
                    80
                )
            )
        );

        AudioTasteModeAffinityService.TasteModeAffinity affinity = service.findNearestMode(
            profile,
            candidate("candidate-1", 0.20d, 0.70d, 0.80d, 0.01d, 0.10d, 0.05d, 130.0d, 0.70d)
        ).orElseThrow();

        assertThat(affinity.modeId()).isEqualTo("mode-a");
    }

    @Test
    void shouldReturnEmptyForNonHeavyProfile() {
        AudioTasteProfileService.Profile profile = profile(
            "strong",
            List.of(mode(
                "mode-1",
                "high_energy_bright_danceable",
                0.18d,
                0.76d,
                0.81d,
                0.03d,
                0.13d,
                0.06d,
                0.58d,
                0.72d,
                0.74d,
                78
            ))
        );

        assertThat(service.findNearestMode(
            profile,
            candidate("candidate-1", 0.20d, 0.78d, 0.82d, 0.02d, 0.12d, 0.05d, 142.0d, 0.70d)
        )).isEmpty();
    }

    @Test
    void shouldReturnEmptyForUnusableCandidate() {
        AudioTasteProfileService.Profile profile = profile(
            "heavy",
            List.of(mode(
                "mode-1",
                "high_energy_bright_danceable",
                0.18d,
                0.76d,
                0.81d,
                0.03d,
                0.13d,
                0.06d,
                0.58d,
                0.72d,
                0.74d,
                78
            ))
        );

        assertThat(service.findNearestMode(profile, new AudioTasteTrackFeature(
            "pms_user_track",
            "candidate-1",
            "Candidate",
            "Artist",
            "spotify",
            "unresolved",
            false,
            0.0d,
            "missing",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        ))).isEmpty();
    }

    private AudioTasteProfileService.Profile profile(String profileType, List<AudioTasteMode> tasteModes) {
        return new AudioTasteProfileService.Profile(
            "user-1",
            "ok",
            true,
            profileType,
            "balanced",
            0.82d,
            new AudioTasteProfileService.Diversity(12, "Artist A", 0.18d, 2),
            new AudioTasteProfileService.SourceQualityMix(1.0d, 0.0d, 0.0d, 0.0d, 0.0d),
            tasteModes,
            80,
            2,
            500,
            new AudioTasteProfileService.Centroid(0.20d, 0.70d, 0.80d, 0.01d, 0.10d, 0.05d, 0.50d, 0.70d),
            AudioTasteProfileService.Centroid.empty(),
            new AudioTasteProfileService.Coverage(220, 220, 1.0d, 0, 0),
            List.of(),
            Instant.parse("2026-05-21T00:00:00Z")
        );
    }

    private AudioTasteMode mode(
        String modeId,
        String label,
        double acousticness,
        double danceability,
        double energy,
        double instrumentalness,
        double liveness,
        double speechiness,
        double tempo,
        double valence,
        double confidence,
        int trackCount
    ) {
        return new AudioTasteMode(
            modeId,
            label,
            trackCount,
            confidence,
            new AudioTasteProfileService.Centroid(
                acousticness,
                danceability,
                energy,
                instrumentalness,
                liveness,
                speechiness,
                tempo,
                valence
            ),
            List.of(),
            List.of()
        );
    }

    private AudioTasteTrackFeature candidate(
        String trackId,
        double acousticness,
        double danceability,
        double energy,
        double instrumentalness,
        double liveness,
        double speechiness,
        double tempo,
        double valence
    ) {
        return new AudioTasteTrackFeature(
            "pms_user_track",
            trackId,
            "Candidate",
            "Artist",
            "spotify",
            "reccobeats_lookup",
            true,
            1.0d,
            "provider",
            acousticness,
            danceability,
            energy,
            instrumentalness,
            liveness,
            speechiness,
            tempo,
            valence
        );
    }
}
