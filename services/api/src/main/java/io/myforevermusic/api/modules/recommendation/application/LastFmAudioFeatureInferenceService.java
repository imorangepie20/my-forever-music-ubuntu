package io.myforevermusic.api.modules.recommendation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.platform.infrastructure.lastfm.LastFmWebApiClient;
import io.myforevermusic.api.modules.platform.infrastructure.lastfm.LastFmWebApiClient.LastFmTag;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LastFmAudioFeatureInferenceService {

    private static final String SOURCE_NAME = "lastfm";
    private static final String SOURCE_CLASS = "tag_inferred";
    private static final String MODEL_VERSION = "lastfm-tag-rules-v1";
    private static final Map<String, TagProfile> TAG_PROFILES = buildTagProfiles();

    private final LastFmWebApiClient lastFmWebApiClient;
    private final TrackAudioFeatureEvidenceStore evidenceStore;
    private final ObjectMapper objectMapper;

    @Value("${app.audio-features.completion.lastfm.min-confidence:0.55}")
    private double minConfidence;

    public LastFmAudioFeatureInferenceService(
        LastFmWebApiClient lastFmWebApiClient,
        TrackAudioFeatureEvidenceStore evidenceStore,
        ObjectMapper objectMapper
    ) {
        this.lastFmWebApiClient = lastFmWebApiClient;
        this.evidenceStore = evidenceStore;
        this.objectMapper = objectMapper;
    }

    public Optional<InferredAudioFeatureSnapshot> infer(AudioFeatureInferenceTarget target) {
        if (target == null || !hasText(target.trackScope()) || !hasText(target.trackId())
            || !hasText(target.title()) || !hasText(target.artistName())) {
            return Optional.empty();
        }

        List<TagEvidence> tagEvidence = new ArrayList<>();
        addTags(tagEvidence, "track_tag", safeTrackTags(target), 1.0d);
        addTags(tagEvidence, "artist_tag", safeArtistTags(target), 0.45d);

        List<WeightedTagProfile> weightedProfiles = tagEvidence.stream()
            .map(evidence -> new WeightedTagProfile(evidence, TAG_PROFILES.get(normalizeTag(evidence.tagName()))))
            .filter(weighted -> weighted.profile() != null)
            .toList();
        if (weightedProfiles.isEmpty()) {
            return Optional.empty();
        }

        double totalWeight = weightedProfiles.stream().mapToDouble(WeightedTagProfile::weight).sum();
        if (totalWeight <= 0.0d) {
            return Optional.empty();
        }

        double confidence = confidence(weightedProfiles.size(), totalWeight);
        if (confidence < minConfidence) {
            return Optional.empty();
        }

        InferredAudioFeatureSnapshot snapshot = new InferredAudioFeatureSnapshot(
            tagEvidence.stream().anyMatch(evidence -> "track_tag".equals(evidence.kind()))
                ? "lastfm_track_tag_inferred"
                : "lastfm_artist_tag_inferred",
            SOURCE_CLASS,
            confidence,
            MODEL_VERSION,
            false,
            target.durationMs(),
            weightedAverage(weightedProfiles, TagProfile::acousticness),
            weightedAverage(weightedProfiles, TagProfile::danceability),
            weightedAverage(weightedProfiles, TagProfile::energy),
            weightedAverage(weightedProfiles, TagProfile::instrumentalness),
            weightedAverage(weightedProfiles, TagProfile::liveness),
            weightedAverage(weightedProfiles, TagProfile::speechiness),
            weightedAverage(weightedProfiles, TagProfile::tempo),
            weightedAverage(weightedProfiles, TagProfile::valence),
            Instant.now()
        );

        evidenceStore.saveAll(toEvidenceDrafts(target, tagEvidence, snapshot));
        return Optional.of(snapshot);
    }

    private List<LastFmTag> safeTrackTags(AudioFeatureInferenceTarget target) {
        try {
            return lastFmWebApiClient.getTrackTopTags(target.artistName(), target.title());
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private List<LastFmTag> safeArtistTags(AudioFeatureInferenceTarget target) {
        try {
            return lastFmWebApiClient.getArtistTopTags(target.artistName());
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private void addTags(List<TagEvidence> evidence, String kind, List<LastFmTag> tags, double sourceWeight) {
        if (tags == null) {
            return;
        }
        tags.stream()
            .filter(tag -> tag != null && hasText(tag.tagName()))
            .limit(8)
            .map(tag -> new TagEvidence(
                kind,
                tag.tagName(),
                tag.count() == null || tag.count() <= 0 ? 1.0d : tag.count().doubleValue(),
                sourceWeight,
                tag.tagUrl()
            ))
            .forEach(evidence::add);
    }

    private double confidence(int matchedTagCount, double totalWeight) {
        return clamp(0.35d + Math.min(0.25d, matchedTagCount * 0.07d) + Math.min(0.22d, totalWeight / 220.0d), 0.0d, 0.82d);
    }

    private double weightedAverage(List<WeightedTagProfile> weightedProfiles, java.util.function.ToDoubleFunction<TagProfile> extractor) {
        double weightedSum = weightedProfiles.stream()
            .mapToDouble(weighted -> extractor.applyAsDouble(weighted.profile()) * weighted.weight())
            .sum();
        double totalWeight = weightedProfiles.stream().mapToDouble(WeightedTagProfile::weight).sum();
        return round(weightedSum / totalWeight);
    }

    private List<TrackAudioFeatureEvidenceStore.Draft> toEvidenceDrafts(
        AudioFeatureInferenceTarget target,
        List<TagEvidence> tagEvidence,
        InferredAudioFeatureSnapshot snapshot
    ) {
        Instant now = snapshot.resolvedAt();
        List<TrackAudioFeatureEvidenceStore.Draft> drafts = new ArrayList<>();
        for (TagEvidence evidence : tagEvidence) {
            drafts.add(new TrackAudioFeatureEvidenceStore.Draft(
                target.trackScope(),
                target.trackId(),
                SOURCE_NAME,
                SOURCE_CLASS,
                firstNonBlank(evidence.tagUrl(), target.sourceUrl()),
                evidence.kind(),
                toJson(Map.of(
                    "tag", evidence.tagName(),
                    "raw_count", evidence.rawCount(),
                    "source_weight", evidence.sourceWeight(),
                    "model_version", MODEL_VERSION
                )),
                snapshot.confidence(),
                now,
                null
            ));
        }
        drafts.add(new TrackAudioFeatureEvidenceStore.Draft(
            target.trackScope(),
            target.trackId(),
            SOURCE_NAME,
            SOURCE_CLASS,
            target.sourceUrl(),
            "tag_inference_result",
            toJson(resultPayload(snapshot)),
            snapshot.confidence(),
            now,
            null
        ));
        return drafts;
    }

    private Map<String, Object> resultPayload(InferredAudioFeatureSnapshot snapshot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", snapshot.source());
        payload.put("source_class", snapshot.sourceClass());
        payload.put("confidence", snapshot.confidence());
        payload.put("model_version", snapshot.modelVersion());
        payload.put("audio_features_filled", snapshot.audioFeaturesFilled());
        payload.put("duration_ms", snapshot.durationMs());
        payload.put("acousticness", snapshot.acousticness());
        payload.put("danceability", snapshot.danceability());
        payload.put("energy", snapshot.energy());
        payload.put("instrumentalness", snapshot.instrumentalness());
        payload.put("liveness", snapshot.liveness());
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

    private static Map<String, TagProfile> buildTagProfiles() {
        Map<String, TagProfile> profiles = new LinkedHashMap<>();
        put(profiles, "electronic", 0.18d, 0.68d, 0.70d, 0.18d, 0.12d, 0.05d, 118.0d, 0.52d);
        put(profiles, "dance", 0.12d, 0.82d, 0.78d, 0.08d, 0.12d, 0.05d, 124.0d, 0.65d);
        put(profiles, "synthpop", 0.24d, 0.72d, 0.66d, 0.18d, 0.12d, 0.04d, 116.0d, 0.62d);
        put(profiles, "pop", 0.30d, 0.68d, 0.62d, 0.03d, 0.12d, 0.07d, 112.0d, 0.66d);
        put(profiles, "rock", 0.18d, 0.48d, 0.74d, 0.06d, 0.16d, 0.05d, 128.0d, 0.50d);
        put(profiles, "hip-hop", 0.22d, 0.70d, 0.64d, 0.02d, 0.13d, 0.36d, 94.0d, 0.52d);
        put(profiles, "rap", 0.20d, 0.72d, 0.66d, 0.02d, 0.13d, 0.42d, 92.0d, 0.50d);
        put(profiles, "ambient", 0.52d, 0.24d, 0.28d, 0.55d, 0.10d, 0.03d, 84.0d, 0.42d);
        put(profiles, "acoustic", 0.82d, 0.38d, 0.34d, 0.08d, 0.12d, 0.06d, 96.0d, 0.54d);
        put(profiles, "folk", 0.78d, 0.42d, 0.38d, 0.08d, 0.12d, 0.07d, 98.0d, 0.56d);
        put(profiles, "jazz", 0.54d, 0.45d, 0.42d, 0.32d, 0.15d, 0.05d, 104.0d, 0.50d);
        put(profiles, "classical", 0.72d, 0.20d, 0.30d, 0.70d, 0.12d, 0.02d, 88.0d, 0.44d);
        return Map.copyOf(profiles);
    }

    private static void put(
        Map<String, TagProfile> profiles,
        String tag,
        double acousticness,
        double danceability,
        double energy,
        double instrumentalness,
        double liveness,
        double speechiness,
        double tempo,
        double valence
    ) {
        profiles.put(tag, new TagProfile(
            acousticness,
            danceability,
            energy,
            instrumentalness,
            liveness,
            speechiness,
            tempo,
            valence
        ));
    }

    private static String normalizeTag(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String first, String second) {
        return hasText(first) ? first : second;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
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
        Double acousticness,
        Double danceability,
        Double energy,
        Double instrumentalness,
        Double liveness,
        Double speechiness,
        Double tempo,
        Double valence,
        Instant resolvedAt
    ) {}

    private record TagEvidence(
        String kind,
        String tagName,
        double rawCount,
        double sourceWeight,
        String tagUrl
    ) {}

    private record WeightedTagProfile(TagEvidence evidence, TagProfile profile) {
        double weight() {
            return evidence.rawCount() * evidence.sourceWeight();
        }
    }

    private record TagProfile(
        double acousticness,
        double danceability,
        double energy,
        double instrumentalness,
        double liveness,
        double speechiness,
        double tempo,
        double valence
    ) {}
}
