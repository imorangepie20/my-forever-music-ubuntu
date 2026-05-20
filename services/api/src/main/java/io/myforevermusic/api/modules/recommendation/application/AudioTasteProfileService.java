package io.myforevermusic.api.modules.recommendation.application;

import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsTrackAudioFeatures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import org.springframework.stereotype.Service;

@Service
public class AudioTasteProfileService {

    private static final int DEFAULT_EVENT_LIMIT = 500;
    private static final int MIN_POSITIVE_READY_TRACKS = 10;

    private final PmsUserLibraryStore libraryStore;
    private final UserMusicEventStore eventStore;
    private final TrackAudioFeatureEvidenceStore evidenceStore;
    private final EventSignalWeights eventSignalWeights;

    public AudioTasteProfileService(
        PmsUserLibraryStore libraryStore,
        UserMusicEventStore eventStore,
        TrackAudioFeatureEvidenceStore evidenceStore,
        EventSignalWeights eventSignalWeights
    ) {
        this.libraryStore = libraryStore;
        this.eventStore = eventStore;
        this.evidenceStore = evidenceStore;
        this.eventSignalWeights = eventSignalWeights;
    }

    public Profile recompute(String userId, Integer eventLimit) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id is required to recompute audio taste profile.");
        }
        String normalizedUserId = userId.trim();
        int resolvedLimit = eventLimit == null ? DEFAULT_EVENT_LIMIT : Math.max(1, Math.min(2_000, eventLimit));
        Map<String, AudioTasteTrackFeature> featuresByTrackId = collectPmsFeatures(normalizedUserId);
        List<UserMusicEventStore.StoredEvent> events = eventStore.findRecentByUserId(normalizedUserId, resolvedLimit)
            .stream()
            .sorted(Comparator.comparing(UserMusicEventStore.StoredEvent::occurredAt))
            .toList();

        List<WeightedFeature> positives = new ArrayList<>();
        List<WeightedFeature> negatives = new ArrayList<>();
        for (UserMusicEventStore.StoredEvent event : events) {
            AudioTasteTrackFeature feature = featuresByTrackId.get(event.trackId());
            if (feature == null || !feature.usable()) {
                continue;
            }
            double eventWeight = event.eventWeight() == null
                ? eventSignalWeights.findWeight(event.eventType()).orElse(0.0d)
                : event.eventWeight();
            double finalWeight = Math.abs(eventWeight) * feature.featureWeight();
            if (eventWeight > 0.0d) {
                positives.add(new WeightedFeature(feature, finalWeight));
            } else if (eventWeight < 0.0d) {
                negatives.add(new WeightedFeature(feature, finalWeight));
            }
        }

        Centroid positive = centroid(positives);
        Centroid negative = centroid(negatives);
        Coverage coverage = coverage(featuresByTrackId.values().stream().toList());
        boolean applicable = positives.size() >= MIN_POSITIVE_READY_TRACKS && coverage.featureReadyRatio() >= 0.30d;
        List<String> warnings = new ArrayList<>();
        if (positives.size() < MIN_POSITIVE_READY_TRACKS) {
            warnings.add("Audio taste profile requires at least 10 positive feature-ready tracks.");
        }
        if (coverage.featureReadyRatio() < 0.30d) {
            warnings.add("Audio taste feature coverage is below 0.30.");
        }
        return new Profile(
            normalizedUserId,
            applicable ? "ok" : "insufficient_data",
            applicable,
            positives.size(),
            negatives.size(),
            resolvedLimit,
            positive,
            negative,
            coverage,
            List.copyOf(warnings),
            Instant.now()
        );
    }

    public Dataset dataset(String userId, Integer eventLimit) {
        Profile profile = recompute(userId, eventLimit);
        List<AudioTasteTrackFeature> rows = collectPmsFeatures(userId.trim()).values().stream()
            .sorted(Comparator.comparing(AudioTasteTrackFeature::trackId))
            .toList();
        return new Dataset("audio-taste-dataset-v1", userId.trim(), profile, rows);
    }

    private Map<String, AudioTasteTrackFeature> collectPmsFeatures(String userId) {
        Map<String, AudioTasteTrackFeature> result = new HashMap<>();
        for (PmsUserLibraryStore.LibraryPlaylistState playlist : libraryStore.findPlaylists(userId)) {
            if (playlist.tracks() == null) {
                continue;
            }
            for (PmsUserLibraryStore.LibraryTrackState track : playlist.tracks()) {
                PmsTrackAudioFeatures audio = track.audioFeatures();
                if (audio == null) {
                    continue;
                }
                EvidenceSummary evidence = evidenceSummary("pms_user_track", track.trackId());
                AudioTasteFeatureQuality.Quality quality = AudioTasteFeatureQuality.resolve(
                    audio.getAudioFeatureSource(),
                    audio.isAudioFeaturesFilled(),
                    evidence.maxConfidence(),
                    evidence.count()
                );
                result.putIfAbsent(track.trackId(), new AudioTasteTrackFeature(
                    "pms_user_track",
                    track.trackId(),
                    track.title(),
                    track.artistName(),
                    track.sourcePlatform(),
                    audio.getAudioFeatureSource(),
                    audio.isAudioFeaturesFilled(),
                    quality.weight(),
                    quality.tier(),
                    audio.getAcousticness(),
                    audio.getDanceability(),
                    audio.getEnergy(),
                    audio.getInstrumentalness(),
                    audio.getLiveness(),
                    audio.getSpeechiness(),
                    audio.getTempo(),
                    audio.getValence()
                ));
            }
        }
        return result;
    }

    private EvidenceSummary evidenceSummary(String trackScope, String trackId) {
        List<TrackAudioFeatureEvidenceStore.StoredEvidence> evidence = evidenceStore.findByTrack(trackScope, trackId, 10);
        OptionalDouble max = evidence.stream().mapToDouble(TrackAudioFeatureEvidenceStore.StoredEvidence::confidence).max();
        return new EvidenceSummary(evidence.size(), max.orElse(0.0d));
    }

    private Centroid centroid(List<WeightedFeature> rows) {
        double totalWeight = rows.stream().mapToDouble(WeightedFeature::weight).sum();
        if (totalWeight <= 0.0d) {
            return Centroid.empty();
        }
        return new Centroid(
            weighted(rows, totalWeight, "acousticness"),
            weighted(rows, totalWeight, "danceability"),
            weighted(rows, totalWeight, "energy"),
            weighted(rows, totalWeight, "instrumentalness"),
            weighted(rows, totalWeight, "liveness"),
            weighted(rows, totalWeight, "speechiness"),
            weightedTempo(rows, totalWeight),
            weighted(rows, totalWeight, "valence")
        );
    }

    private double weighted(List<WeightedFeature> rows, double totalWeight, String key) {
        return rows.stream().mapToDouble(row -> value(row.feature(), key) * row.weight()).sum() / totalWeight;
    }

    private double weightedTempo(List<WeightedFeature> rows, double totalWeight) {
        return rows.stream().mapToDouble(row -> normalizeTempo(row.feature().tempo()) * row.weight()).sum() / totalWeight;
    }

    private double value(AudioTasteTrackFeature feature, String key) {
        return switch (key) {
            case "acousticness" -> nullToMid(feature.acousticness());
            case "danceability" -> nullToMid(feature.danceability());
            case "energy" -> nullToMid(feature.energy());
            case "instrumentalness" -> nullToMid(feature.instrumentalness());
            case "liveness" -> nullToMid(feature.liveness());
            case "speechiness" -> nullToMid(feature.speechiness());
            case "valence" -> nullToMid(feature.valence());
            default -> 0.5d;
        };
    }

    private double normalizeTempo(Double tempo) {
        if (tempo == null) {
            return 0.5d;
        }
        return Math.max(0.0d, Math.min(1.0d, (tempo - 60.0d) / 140.0d));
    }

    private double nullToMid(Double value) {
        return value == null ? 0.5d : Math.max(0.0d, Math.min(1.0d, value));
    }

    private Coverage coverage(List<AudioTasteTrackFeature> rows) {
        if (rows.isEmpty()) {
            return new Coverage(0, 0, 0.0d, 0, 0);
        }
        long usable = rows.stream().filter(AudioTasteTrackFeature::usable).count();
        long weak = rows.stream().filter(row -> Objects.equals(row.featureTier(), "llm_weak")).count();
        long inferred = rows.stream().filter(row -> row.featureTier().startsWith("llm_")).count();
        return new Coverage(rows.size(), usable, round((double) usable / rows.size()), inferred, weak);
    }

    private double round(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private record WeightedFeature(AudioTasteTrackFeature feature, double weight) {
    }

    private record EvidenceSummary(long count, double maxConfidence) {
    }

    public record Dataset(String datasetVersion, String userId, Profile profile, List<AudioTasteTrackFeature> rows) {
    }

    public record Profile(
        String userId,
        String status,
        boolean audioTasteApplicable,
        int positiveTrackCount,
        int negativeTrackCount,
        int eventLimit,
        Centroid positiveCentroid,
        Centroid negativeCentroid,
        Coverage coverage,
        List<String> warnings,
        Instant recomputedAt
    ) {
    }

    public record Centroid(
        double acousticness,
        double danceability,
        double energy,
        double instrumentalness,
        double liveness,
        double speechiness,
        double tempo,
        double valence
    ) {
        public static Centroid empty() {
            return new Centroid(0.5d, 0.5d, 0.5d, 0.5d, 0.5d, 0.5d, 0.5d, 0.5d);
        }
    }

    public record Coverage(
        int trackCount,
        long usableTrackCount,
        double featureReadyRatio,
        long inferredTrackCount,
        long weakTrackCount
    ) {
    }
}
