package io.myforevermusic.api.modules.publiccuration.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.gms.infrastructure.ai.AiServiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AiPublicCurationScoringClientSpringContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(AiServiceProperties.class, () -> new AiServiceProperties(
            "http://127.0.0.1:8000",
            "/v1/recommendations/preview",
            "/v1/ems/overview",
            "/v1/ems/acquisition/signals",
            "/v1/audio-features/infer",
            "/v1/recommendations/datasets/sasrec/train",
            "/v1/recommendations/datasets/sasrec/rank",
            "/v1/recommendations/datasets/sasrec/models/latest",
            "/v1/public-curations/score",
            ""
        ))
        .withBean(AiPublicCurationScoringClient.class);

    @Test
    void shouldCreateAiPublicCurationScoringClientWithConstructorInjection() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AiPublicCurationScoringClient.class);
        });
    }
}
