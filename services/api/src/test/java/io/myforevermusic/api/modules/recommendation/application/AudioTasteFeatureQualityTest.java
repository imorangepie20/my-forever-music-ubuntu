package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AudioTasteFeatureQualityTest {

    @Test
    void shouldTreatFilledProviderFeaturesAsFullWeight() {
        AudioTasteFeatureQuality.Quality quality = AudioTasteFeatureQuality.resolve(
            "reccobeats_lookup",
            true,
            0.0d,
            0
        );

        assertThat(quality.tier()).isEqualTo("provider");
        assertThat(quality.weight()).isEqualTo(1.0d);
        assertThat(quality.usable()).isTrue();
    }

    @Test
    void shouldKeepLowConfidenceLlmAsWeakSignal() {
        AudioTasteFeatureQuality.Quality quality = AudioTasteFeatureQuality.resolve(
            "llm_search_low_confidence",
            false,
            0.56d,
            2
        );

        assertThat(quality.tier()).isEqualTo("llm_weak");
        assertThat(quality.weight()).isEqualTo(0.35d);
        assertThat(quality.usable()).isTrue();
    }

    @Test
    void shouldRejectMissingOrUnresolvedFeatures() {
        AudioTasteFeatureQuality.Quality quality = AudioTasteFeatureQuality.resolve(
            "unresolved",
            false,
            0.0d,
            0
        );

        assertThat(quality.tier()).isEqualTo("missing");
        assertThat(quality.weight()).isEqualTo(0.0d);
        assertThat(quality.usable()).isFalse();
    }
}
