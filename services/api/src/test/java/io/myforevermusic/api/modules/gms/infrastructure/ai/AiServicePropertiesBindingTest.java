package io.myforevermusic.api.modules.gms.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AiServicePropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(TestConfiguration.class)
        .withPropertyValues(
            "app.ai.base-url=http://127.0.0.1:8000",
            "app.ai.recommendation-preview-path=/v1/recommendations/preview",
            "app.ai.ems-overview-path=/v1/ems/overview",
            "app.ai.ems-acquisition-signals-path=/v1/ems/acquisition/signals",
            "app.ai.audio-feature-inference-path=/v1/audio-features/infer",
            "app.ai.sasrec-training-path=/v1/recommendations/datasets/sasrec/train",
            "app.ai.sasrec-ranking-path=/v1/recommendations/datasets/sasrec/rank",
            "app.ai.sasrec-latest-model-path=/v1/recommendations/datasets/sasrec/models/latest",
            "app.ai.public-curation-score-path=/v1/public-curations/score",
            "app.ai.sasrec-model-version="
        );

    @Test
    void shouldBindAiServicePropertiesWithPublicCurationScorePath() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            AiServiceProperties properties = context.getBean(AiServiceProperties.class);

            assertThat(properties.baseUrl()).isEqualTo("http://127.0.0.1:8000");
            assertThat(properties.publicCurationScorePath()).isEqualTo("/v1/public-curations/score");
        });
    }

    @EnableConfigurationProperties(AiServiceProperties.class)
    static class TestConfiguration {
    }
}
