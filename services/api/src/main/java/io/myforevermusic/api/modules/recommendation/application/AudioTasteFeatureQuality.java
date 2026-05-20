package io.myforevermusic.api.modules.recommendation.application;

import java.util.Locale;

public final class AudioTasteFeatureQuality {

    private AudioTasteFeatureQuality() {
    }

    public static Quality resolve(
        String audioFeatureSource,
        boolean audioFeaturesFilled,
        double maxEvidenceConfidence,
        long evidenceCount
    ) {
        String source = normalize(audioFeatureSource);
        if (audioFeaturesFilled) {
            if ("llm_search_inferred".equals(source)) {
                return new Quality("llm_accepted", 0.70d, true);
            }
            return new Quality("provider", 1.00d, true);
        }
        if ("llm_search_low_confidence".equals(source) && maxEvidenceConfidence >= 0.50d && evidenceCount > 0L) {
            return new Quality("llm_weak", 0.35d, true);
        }
        if (source.startsWith("lastfm_") && evidenceCount > 0L) {
            return new Quality("lastfm_partial", 0.25d, true);
        }
        return new Quality("missing", 0.0d, false);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record Quality(String tier, double weight, boolean usable) {
    }
}
