package io.myforevermusic.api.modules.gms.infrastructure.ai;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
    @NotBlank String publicCurationScorePath,
    String sasrecModelVersion
) {
    @ConstructorBinding
    public AiServiceProperties {
    }

    public AiServiceProperties(
        String baseUrl,
        String recommendationPreviewPath,
        String emsOverviewPath,
        String emsAcquisitionSignalsPath,
        String audioFeatureInferencePath,
        String sasrecTrainingPath,
        String sasrecRankingPath,
        String sasrecLatestModelPath,
        String sasrecModelVersion
    ) {
        this(
            baseUrl,
            recommendationPreviewPath,
            emsOverviewPath,
            emsAcquisitionSignalsPath,
            audioFeatureInferencePath,
            sasrecTrainingPath,
            sasrecRankingPath,
            sasrecLatestModelPath,
            "/v1/public-curations/score",
            sasrecModelVersion
        );
    }
}
