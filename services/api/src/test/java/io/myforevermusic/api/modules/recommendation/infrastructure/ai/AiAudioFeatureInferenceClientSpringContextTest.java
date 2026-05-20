package io.myforevermusic.api.modules.recommendation.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.gms.infrastructure.ai.AiServiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AiAudioFeatureInferenceClientSpringContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(AiServiceProperties.class, () -> new AiServiceProperties(
            "http://127.0.0.1:8000",
            "/v1/recommendations/preview",
            "/v1/ems/overview",
            "/v1/ems/acquisition-signals",
            "/v1/audio-features/infer",
            "/v1/recommendations/sasrec/train",
            "/v1/recommendations/sasrec/rank",
            "/v1/recommendations/sasrec/models/latest",
            null
        ))
        .withBean(AiAudioFeatureInferenceClient.class);

    @Test
    void shouldCreateClientWithSpringConstructorInjection() {
        contextRunner.run(context ->
            assertThat(context).hasSingleBean(AiAudioFeatureInferenceClient.class)
        );
    }
}
