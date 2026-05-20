package io.myforevermusic.api.modules.recommendation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.recommendation.infrastructure.ai.AiAudioFeatureInferenceClient;
import io.myforevermusic.api.modules.recommendation.infrastructure.ai.AiAudioFeatureInferenceClient.AiAudioFeatureInferenceEvidence;
import io.myforevermusic.api.modules.recommendation.infrastructure.ai.AiAudioFeatureInferenceClient.AiAudioFeatureInferenceRequest;
import io.myforevermusic.api.modules.recommendation.infrastructure.ai.AiAudioFeatureInferenceClient.AiAudioFeatureInferenceResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AudioFeatureLlmSearchInferenceService {

    private static final String SOURCE_NAME = "openai_web_search";
    private static final String SOURCE_CLASS = "llm_search_inferred";

    private final AiAudioFeatureInferenceClient aiClient;
    private final TrackAudioFeatureEvidenceStore evidenceStore;
    private final ObjectMapper objectMapper;

    public AudioFeatureLlmSearchInferenceService(
        AiAudioFeatureInferenceClient aiClient,
        TrackAudioFeatureEvidenceStore evidenceStore,
        ObjectMapper objectMapper
    ) {
        this.aiClient = aiClient;
        this.evidenceStore = evidenceStore;
        this.objectMapper = objectMapper;
    }

    public Optional<InferredAudioFeatureSnapshot> infer(AudioFeatureInferenceTarget target) {
        if (target == null || !hasText(target.trackScope()) || !hasText(target.trackId())
            || !hasText(target.title()) || !hasText(target.artistName())) {
            return Optional.empty();
        }

        AiAudioFeatureInferenceResponse response = aiClient.infer(new AiAudioFeatureInferenceRequest(
            target.trackScope(),
            target.trackId(),
            target.title(),
            target.artistName(),
            target.durationMs(),
            target.sourceUrl()
        ));
        if (response == null || !"ok".equals(response.status()) || !response.audioFeaturesFilled()) {
            return Optional.empty();
        }

        InferredAudioFeatureSnapshot snapshot = new InferredAudioFeatureSnapshot(
            firstNonBlank(response.source(), SOURCE_CLASS),
            SOURCE_CLASS,
            response.confidence(),
            response.modelVersion(),
            true,
            response.durationMs(),
            response.musicalKey(),
            response.mode(),
            response.acousticness(),
            response.danceability(),
            response.energy(),
            response.instrumentalness(),
            response.liveness(),
            response.loudness(),
            response.speechiness(),
            response.tempo(),
            response.valence(),
            response.generatedAt() == null ? Instant.now() : response.generatedAt()
        );
        evidenceStore.saveAll(toEvidenceDrafts(target, response, snapshot));
        return Optional.of(snapshot);
    }

    private List<TrackAudioFeatureEvidenceStore.Draft> toEvidenceDrafts(
        AudioFeatureInferenceTarget target,
        AiAudioFeatureInferenceResponse response,
        InferredAudioFeatureSnapshot snapshot
    ) {
        Instant collectedAt = snapshot.resolvedAt();
        List<TrackAudioFeatureEvidenceStore.Draft> drafts = new ArrayList<>();
        List<AiAudioFeatureInferenceEvidence> evidenceItems = response.evidence() == null ? List.of() : response.evidence();
        for (AiAudioFeatureInferenceEvidence evidence : evidenceItems) {
            drafts.add(new TrackAudioFeatureEvidenceStore.Draft(
                target.trackScope(),
                target.trackId(),
                firstNonBlank(evidence.sourceName(), SOURCE_NAME),
                SOURCE_CLASS,
                firstNonBlank(evidence.sourceUrl(), target.sourceUrl()),
                firstNonBlank(evidence.evidenceKind(), "web_search_result"),
                toJson(Map.of(
                    "evidence_text", evidence.evidenceText(),
                    "model_version", snapshot.modelVersion()
                )),
                evidence.confidence(),
                collectedAt,
                null
            ));
        }
        drafts.add(new TrackAudioFeatureEvidenceStore.Draft(
            target.trackScope(),
            target.trackId(),
            SOURCE_NAME,
            SOURCE_CLASS,
            target.sourceUrl(),
            "llm_search_inference_result",
            toJson(resultPayload(response, snapshot)),
            snapshot.confidence(),
            collectedAt,
            null
        ));
        return drafts;
    }

    private Map<String, Object> resultPayload(
        AiAudioFeatureInferenceResponse response,
        InferredAudioFeatureSnapshot snapshot
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request_id", response.requestId());
        payload.put("model", response.model());
        payload.put("source", snapshot.source());
        payload.put("source_class", snapshot.sourceClass());
        payload.put("confidence", snapshot.confidence());
        payload.put("model_version", snapshot.modelVersion());
        payload.put("rationale", response.rationale());
        payload.put("audio_features_filled", snapshot.audioFeaturesFilled());
        payload.put("duration_ms", snapshot.durationMs());
        payload.put("musical_key", snapshot.musicalKey());
        payload.put("mode", snapshot.mode());
        payload.put("acousticness", snapshot.acousticness());
        payload.put("danceability", snapshot.danceability());
        payload.put("energy", snapshot.energy());
        payload.put("instrumentalness", snapshot.instrumentalness());
        payload.put("liveness", snapshot.liveness());
        payload.put("loudness", snapshot.loudness());
        payload.put("speechiness", snapshot.speechiness());
        payload.put("tempo", snapshot.tempo());
        payload.put("valence", snapshot.valence());
        return payload;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Audio feature evidence payload could not be serialized.", exception);
        }
    }

    private static String firstNonBlank(String first, String second) {
        return hasText(first) ? first : second;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record AudioFeatureInferenceTarget(
        String trackScope,
        String trackId,
        String title,
        String artistName,
        Integer durationMs,
        String sourceUrl
    ) {}

    public record InferredAudioFeatureSnapshot(
        String source,
        String sourceClass,
        double confidence,
        String modelVersion,
        boolean audioFeaturesFilled,
        Integer durationMs,
        Integer musicalKey,
        Integer mode,
        Double acousticness,
        Double danceability,
        Double energy,
        Double instrumentalness,
        Double liveness,
        Double loudness,
        Double speechiness,
        Double tempo,
        Double valence,
        Instant resolvedAt
    ) {}
}
