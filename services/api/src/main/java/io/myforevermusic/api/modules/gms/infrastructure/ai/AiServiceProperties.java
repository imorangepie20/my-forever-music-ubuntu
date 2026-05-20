package io.myforevermusic.api.modules.gms.infrastructure.ai;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.ai")
public record AiServiceProperties(
    @NotBlank String baseUrl,
    @NotBlank String recommendationPreviewPath,
    @NotBlank String emsOverviewPath,
    @NotBlank String emsAcquisitionSignalsPath,
    @NotBlank String audioFeatureInferencePath,
    @NotBlank String sasrecTrainingPath,
    @NotBlank String sasrecRankingPath,
    @NotBlank String sasrecLatestModelPath,
    String sasrecModelVersion
) {
}
