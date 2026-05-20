package io.myforevermusic.api.modules.recommendation.application;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AudioTasteScoringService {

    public Score score(AudioTasteProfileService.Profile profile, AudioTasteTrackFeature candidate) {
        if (profile == null || !profile.audioTasteApplicable()) {
            return new Score(false, 0.0d, 0.0d, 0.0d, List.of("audio_taste_not_applicable"));
        }
        if (candidate == null || !candidate.usable()) {
            return new Score(false, 0.0d, 0.0d, 0.0d, List.of("candidate_audio_missing"));
        }

        double positiveSimilarity = 1.0d - normalizedDistance(candidate, profile.positiveCentroid());
        double negativeDistanceBonus = normalizedDistance(candidate, profile.negativeCentroid());
        double profileInfluence = profileInfluence(profile);
        double coverageWeight = Math.min(
            1.0d,
            profile.coverage().featureReadyRatio() * candidate.featureWeight() * profileInfluence
        );
        double score = clamp(coverageWeight * ((0.70d * positiveSimilarity) + (0.30d * negativeDistanceBonus)));
        List<String> tokens = explanationTokens(candidate, profile.positiveCentroid());
        if ("llm_weak".equals(candidate.featureTier())) {
            tokens.add("low_confidence_audio");
        }
        if ("artist_narrow".equals(profile.profileFocus())) {
            tokens.add("artist_narrow_audio_profile");
        }
        if ("low_quality".equals(profile.profileFocus())) {
            tokens.add("low_quality_audio_profile");
        }
        return new Score(
            true,
            round(score),
            round(coverageWeight),
            maxBoostWeight(profile.profileType()),
            List.copyOf(tokens)
        );
    }

    private double profileInfluence(AudioTasteProfileService.Profile profile) {
        return profile.profileConfidence() * switch (profile.profileType()) {
            case "weak" -> 0.50d;
            case "ready" -> 0.85d;
            case "strong", "heavy" -> 1.0d;
            default -> 0.0d;
        };
    }

    private double maxBoostWeight(String profileType) {
        return switch (profileType) {
            case "weak" -> 0.03d;
            case "ready" -> 0.08d;
            case "strong", "heavy" -> 0.12d;
            default -> 0.0d;
        };
    }

    private double normalizedDistance(AudioTasteTrackFeature feature, AudioTasteProfileService.Centroid centroid) {
        double sum = 0.0d;
        sum += squared(feature.acousticness(), centroid.acousticness());
        sum += squared(feature.danceability(), centroid.danceability());
        sum += squared(feature.energy(), centroid.energy());
        sum += squared(feature.instrumentalness(), centroid.instrumentalness());
        sum += squared(feature.liveness(), centroid.liveness());
        sum += squared(feature.speechiness(), centroid.speechiness());
        sum += squared(normalizeTempo(feature.tempo()), centroid.tempo());
        sum += squared(feature.valence(), centroid.valence());
        return Math.min(1.0d, Math.sqrt(sum / 8.0d));
    }

    private List<String> explanationTokens(AudioTasteTrackFeature feature, AudioTasteProfileService.Centroid centroid) {
        List<String> tokens = new ArrayList<>();
        if (Math.abs(value(feature.energy()) - centroid.energy()) <= 0.15d) {
            tokens.add("energy_match");
        }
        if (Math.abs(value(feature.valence()) - centroid.valence()) <= 0.15d) {
            tokens.add("valence_match");
        }
        if (Math.abs(value(feature.danceability()) - centroid.danceability()) <= 0.15d) {
            tokens.add("danceability_match");
        }
        if (Math.abs(normalizeTempo(feature.tempo()) - centroid.tempo()) <= 0.15d) {
            tokens.add("tempo_match");
        }
        if (tokens.isEmpty()) {
            tokens.add("audio_profile_distance");
        }
        return tokens;
    }

    private double squared(Double left, double right) {
        double value = value(left);
        return (value - right) * (value - right);
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

    public record Score(
        boolean applied,
        double score,
        double coverageWeight,
        double maxBoostWeight,
        List<String> explanationTokens
    ) {
    }
}
