package io.myforevermusic.api.modules.recommendation.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AudioTasteModeAffinityService {

    private static final String PROFILE_HEAVY = "heavy";

    public Optional<TasteModeAffinity> findNearestMode(
        AudioTasteProfileService.Profile profile,
        AudioTasteTrackFeature candidate
    ) {
        if (profile == null || !PROFILE_HEAVY.equals(profile.profileType())) {
            return Optional.empty();
        }
        if (profile.tasteModes() == null || profile.tasteModes().isEmpty()) {
            return Optional.empty();
        }
        if (candidate == null || !candidate.usable()) {
            return Optional.empty();
        }

        return profile.tasteModes().stream()
            .map(mode -> new ModeDistance(mode, normalizedDistance(candidate, mode.centroid())))
            .min(Comparator
                .comparingDouble(ModeDistance::distance)
                .thenComparing((ModeDistance distance) -> distance.mode().confidence(), Comparator.reverseOrder())
                .thenComparing((ModeDistance distance) -> distance.mode().trackCount(), Comparator.reverseOrder())
                .thenComparing(distance -> normalizeText(distance.mode().modeId())))
            .map(distance -> toAffinity(distance.mode(), distance.distance(), candidate));
    }

    private TasteModeAffinity toAffinity(AudioTasteMode mode, double distance, AudioTasteTrackFeature candidate) {
        return new TasteModeAffinity(
            true,
            mode.modeId(),
            mode.label(),
            round(clamp(1.0d - distance)),
            round(clamp(distance)),
            tokens(candidate, mode.centroid())
        );
    }

    private List<String> tokens(AudioTasteTrackFeature candidate, AudioTasteProfileService.Centroid centroid) {
        List<String> tokens = new ArrayList<>();
        if (Math.abs(value(candidate.energy()) - centroid.energy()) <= 0.15d) {
            tokens.add("mode_energy_match");
        }
        if (Math.abs(value(candidate.valence()) - centroid.valence()) <= 0.15d) {
            tokens.add("mode_valence_match");
        }
        if (Math.abs(value(candidate.danceability()) - centroid.danceability()) <= 0.15d) {
            tokens.add("mode_danceability_match");
        }
        if (Math.abs(normalizeTempo(candidate.tempo()) - centroid.tempo()) <= 0.15d) {
            tokens.add("mode_tempo_match");
        }
        if (tokens.isEmpty()) {
            tokens.add("mode_profile_distance");
        }
        return List.copyOf(tokens);
    }

    private double normalizedDistance(AudioTasteTrackFeature candidate, AudioTasteProfileService.Centroid centroid) {
        double sum = 0.0d;
        sum += squared(candidate.acousticness(), centroid.acousticness());
        sum += squared(candidate.danceability(), centroid.danceability());
        sum += squared(candidate.energy(), centroid.energy());
        sum += squared(candidate.instrumentalness(), centroid.instrumentalness());
        sum += squared(candidate.liveness(), centroid.liveness());
        sum += squared(candidate.speechiness(), centroid.speechiness());
        sum += squared(normalizeTempo(candidate.tempo()), centroid.tempo());
        sum += squared(candidate.valence(), centroid.valence());
        return Math.min(1.0d, Math.sqrt(sum / 8.0d));
    }

    private double squared(Double left, double right) {
        double value = value(left);
        return (value - right) * (value - right);
    }

    private double squared(double left, double right) {
        return (left - right) * (left - right);
    }

    private double value(Double value) {
        return value == null ? 0.5d : Math.max(0.0d, Math.min(1.0d, value));
    }

    private double normalizeTempo(Double tempo) {
        if (tempo == null) {
            return 0.5d;
        }
        return Math.max(0.0d, Math.min(1.0d, (tempo - 60.0d) / 140.0d));
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private double round(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    public record TasteModeAffinity(
        boolean applied,
        String modeId,
        String label,
        double similarity,
        double distance,
        List<String> tokens
    ) {
        public TasteModeAffinity {
            tokens = tokens == null ? List.of() : List.copyOf(tokens);
        }
    }

    private record ModeDistance(AudioTasteMode mode, double distance) {
    }
}
