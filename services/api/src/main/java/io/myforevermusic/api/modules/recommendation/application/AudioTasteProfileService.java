package io.myforevermusic.api.modules.recommendation.application;

import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsTrackAudioFeatures;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsTrackAudioFeatures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AudioTasteProfileService {

    private static final int DEFAULT_EVENT_LIMIT = 500;
    private static final int DEFAULT_MIN_POSITIVE_READY_TRACKS = 10;
    private static final double DEFAULT_MIN_FEATURE_READY_RATIO = 0.30d;
    private static final String PROFILE_NONE = "none";
    private static final String PROFILE_WEAK = "weak";
    private static final String PROFILE_READY = "ready";
    private static final String PROFILE_STRONG = "strong";
    private static final String PROFILE_HEAVY = "heavy";

    private final PmsUserLibraryStore libraryStore;
    private final UserMusicEventStore eventStore;
    private final TrackAudioFeatureEvidenceStore evidenceStore;
    private final EventSignalWeights eventSignalWeights;
    private final Optional<EmsCollectedTrackRepository> emsTrackRepository;
    private final int minPositiveReadyTracks;
    private final double minFeatureReadyRatio;
    private final AudioTasteModeService audioTasteModeService;

    public AudioTasteProfileService(
        PmsUserLibraryStore libraryStore,
        UserMusicEventStore eventStore,
        TrackAudioFeatureEvidenceStore evidenceStore,
        EventSignalWeights eventSignalWeights
    ) {
        this(
            libraryStore,
            eventStore,
            evidenceStore,
            eventSignalWeights,
            Optional.empty(),
            DEFAULT_MIN_POSITIVE_READY_TRACKS,
            DEFAULT_MIN_FEATURE_READY_RATIO,
            new AudioTasteModeService()
        );
    }

    @Autowired
    public AudioTasteProfileService(
        PmsUserLibraryStore libraryStore,
        UserMusicEventStore eventStore,
        TrackAudioFeatureEvidenceStore evidenceStore,
        EventSignalWeights eventSignalWeights,
        Optional<EmsCollectedTrackRepository> emsTrackRepository,
        @Value("${app.recommendation.audio-taste.min-positive-ready-tracks:10}") int minPositiveReadyTracks,
        @Value("${app.recommendation.audio-taste.min-feature-ready-ratio:0.30}") double minFeatureReadyRatio,
        AudioTasteModeService audioTasteModeService
    ) {
        this.libraryStore = libraryStore;
        this.eventStore = eventStore;
        this.evidenceStore = evidenceStore;
        this.eventSignalWeights = eventSignalWeights;
        this.emsTrackRepository = emsTrackRepository == null ? Optional.empty() : emsTrackRepository;
        this.minPositiveReadyTracks = Math.max(1, minPositiveReadyTracks);
        this.minFeatureReadyRatio = Math.max(0.0d, Math.min(1.0d, minFeatureReadyRatio));
        this.audioTasteModeService = audioTasteModeService == null ? new AudioTasteModeService() : audioTasteModeService;
    }

    public AudioTasteProfileService(
        PmsUserLibraryStore libraryStore,
        UserMusicEventStore eventStore,
        TrackAudioFeatureEvidenceStore evidenceStore,
        EventSignalWeights eventSignalWeights,
        int minPositiveReadyTracks,
        double minFeatureReadyRatio
    ) {
        this(
            libraryStore,
            eventStore,
            evidenceStore,
            eventSignalWeights,
            Optional.empty(),
            minPositiveReadyTracks,
            minFeatureReadyRatio,
            new AudioTasteModeService()
        );
    }

    public AudioTasteProfileService(
        PmsUserLibraryStore libraryStore,
        UserMusicEventStore eventStore,
        TrackAudioFeatureEvidenceStore evidenceStore,
        EventSignalWeights eventSignalWeights,
        Optional<EmsCollectedTrackRepository> emsTrackRepository,
        int minPositiveReadyTracks,
        double minFeatureReadyRatio
    ) {
        this(
            libraryStore,
            eventStore,
            evidenceStore,
            eventSignalWeights,
            emsTrackRepository,
            minPositiveReadyTracks,
            minFeatureReadyRatio,
            new AudioTasteModeService()
        );
    }

    public AudioTasteProfileService(
        PmsUserLibraryStore libraryStore,
        UserMusicEventStore eventStore,
        TrackAudioFeatureEvidenceStore evidenceStore,
        EventSignalWeights eventSignalWeights,
        int minPositiveReadyTracks,
        double minFeatureReadyRatio,
        AudioTasteModeService audioTasteModeService
    ) {
        this(
            libraryStore,
            eventStore,
            evidenceStore,
            eventSignalWeights,
            Optional.empty(),
            minPositiveReadyTracks,
            minFeatureReadyRatio,
            audioTasteModeService
        );
    }

    public Profile recompute(String userId, Integer eventLimit) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id is required to recompute audio taste profile.");
        }
        String normalizedUserId = userId.trim();
        int resolvedLimit = resolveEventLimit(eventLimit);
        List<UserMusicEventStore.StoredEvent> events = recentEvents(normalizedUserId, resolvedLimit);
        Map<String, AudioTasteTrackFeature> featuresByTrackId = collectUserFeatures(normalizedUserId, events);

        List<WeightedFeature> positives = new ArrayList<>();
        List<WeightedFeature> negatives = new ArrayList<>();
        for (UserMusicEventStore.StoredEvent event : events) {
            AudioTasteTrackFeature feature = featuresByTrackId.get(featureLookupTrackId(event));
            if (feature == null || !feature.usable()) {
                continue;
            }
            double eventWeight = eventWeight(event);
            double finalWeight = Math.abs(eventWeight) * feature.featureWeight();
            if (eventWeight > 0.0d) {
                positives.add(new WeightedFeature(feature, finalWeight));
            } else if (eventWeight < 0.0d) {
                negatives.add(new WeightedFeature(feature, finalWeight));
            }
        }

        Centroid positive = centroid(positives);
        Centroid negative = centroid(negatives);
        List<AudioTasteTrackFeature> featureRows = featuresByTrackId.values().stream().toList();
        Coverage coverage = coverage(featureRows);
        String profileType = resolveProfileType(coverage.usableTrackCount());
        Diversity diversity = diversity(featureRows);
        SourceQualityMix sourceQualityMix = sourceQualityMix(featureRows);
        String profileFocus = resolveProfileFocus(diversity, sourceQualityMix);
        double profileConfidence = profileConfidence(
            profileType,
            coverage,
            sourceQualityMix,
            profileFocus,
            negatives.size()
        );
        boolean profileTypeAllowed = switch (profileType) {
            case PROFILE_WEAK -> minPositiveReadyTracks <= 5;
            case PROFILE_READY, PROFILE_STRONG, PROFILE_HEAVY -> true;
            default -> false;
        };
        boolean applicable = profileTypeAllowed
            && positives.size() >= minPositiveReadyTracks
            && coverage.featureReadyRatio() >= minFeatureReadyRatio;
        List<String> warnings = new ArrayList<>();
        if (PROFILE_WEAK.equals(profileType)) {
            warnings.add("Audio taste profile is weak until at least 10 positive feature-ready tracks.");
        }
        if (positives.size() < minPositiveReadyTracks) {
            warnings.add("Audio taste profile requires at least %d positive feature-ready tracks."
                .formatted(minPositiveReadyTracks));
        }
        if (coverage.featureReadyRatio() < minFeatureReadyRatio) {
            warnings.add("Audio taste feature coverage is below %.2f.".formatted(minFeatureReadyRatio));
        }
        if ("artist_narrow".equals(profileFocus)) {
            warnings.add("Audio taste profile is artist-narrow; boost will be dampened.");
        }
        if ("source_narrow".equals(profileFocus)) {
            warnings.add("Audio taste profile is source-narrow; boost will be dampened.");
        }
        if ("low_quality".equals(profileFocus)) {
            warnings.add("Audio taste profile is low-quality; weak inferred features dominate.");
        }
        List<AudioTasteMode> tasteModes = audioTasteModeService.buildModes(profileType, profileConfidence, featureRows);
        if (!tasteModes.isEmpty()) {
            warnings.add("Heavy audio taste modes are available for admin inspection.");
        }
        return new Profile(
            normalizedUserId,
            applicable ? "ok" : "insufficient_data",
            applicable,
            profileType,
            profileFocus,
            profileConfidence,
            diversity,
            sourceQualityMix,
            tasteModes,
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
        String normalizedUserId = userId.trim();
        int resolvedLimit = resolveEventLimit(eventLimit);
        Profile profile = recompute(normalizedUserId, resolvedLimit);
        List<AudioTasteTrackFeature> rows = collectUserFeatures(normalizedUserId, recentEvents(normalizedUserId, resolvedLimit))
            .values()
            .stream()
            .sorted(Comparator.comparing(AudioTasteTrackFeature::trackId))
            .toList();
        return new Dataset("audio-taste-dataset-v1", normalizedUserId, profile, rows);
    }

    private int resolveEventLimit(Integer eventLimit) {
        return eventLimit == null ? DEFAULT_EVENT_LIMIT : Math.max(1, Math.min(2_000, eventLimit));
    }

    private List<UserMusicEventStore.StoredEvent> recentEvents(String userId, int eventLimit) {
        return eventStore.findRecentByUserId(userId, eventLimit)
            .stream()
            .sorted(Comparator.comparing(UserMusicEventStore.StoredEvent::occurredAt))
            .toList();
    }

    private Map<String, AudioTasteTrackFeature> collectUserFeatures(
        String userId,
        List<UserMusicEventStore.StoredEvent> events
    ) {
        Map<String, AudioTasteTrackFeature> result = collectPmsFeatures(userId);
        collectEmsEventFeatures(events).forEach(result::putIfAbsent);
        return result;
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

    private Map<String, AudioTasteTrackFeature> collectEmsEventFeatures(
        List<UserMusicEventStore.StoredEvent> events
    ) {
        if (emsTrackRepository.isEmpty() || events == null || events.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> eventTrackIdsByEmsId = new LinkedHashMap<>();
        for (UserMusicEventStore.StoredEvent event : events) {
            if (event == null || eventWeight(event) <= 0.0d) {
                continue;
            }
            String featureKey = featureLookupTrackId(event);
            Optional<Long> emsId = parseEmsTrackId(featureKey);
            emsId.ifPresent(id -> eventTrackIdsByEmsId.putIfAbsent(id, featureKey));
        }
        if (eventTrackIdsByEmsId.isEmpty()) {
            return Map.of();
        }
        Map<String, AudioTasteTrackFeature> result = new HashMap<>();
        for (EmsCollectedTrackEntity track : emsTrackRepository.get().findAllById(eventTrackIdsByEmsId.keySet())) {
            if (track == null || track.getId() == null) {
                continue;
            }
            String eventTrackId = eventTrackIdsByEmsId.get(track.getId());
            AudioTasteTrackFeature feature = toEmsAudioTasteFeature(track, eventTrackId);
            if (feature != null) {
                result.putIfAbsent(eventTrackId, feature);
            }
        }
        return result;
    }

    private String featureLookupTrackId(UserMusicEventStore.StoredEvent event) {
        if (event == null) {
            return null;
        }
        if (event.trackId() != null && !event.trackId().isBlank()) {
            return event.trackId().trim();
        }
        if (event.itemId() != null && !event.itemId().isBlank()) {
            return event.itemId().trim();
        }
        return null;
    }

    private Optional<Long> parseEmsTrackId(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim();
        if (!normalized.startsWith("ems-track:")) {
            return Optional.empty();
        }
        String rawId = normalized.substring("ems-track:".length()).trim();
        if (rawId.isEmpty()) {
            return Optional.empty();
        }
        try {
            long parsed = Long.parseLong(rawId);
            return parsed > 0 ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private AudioTasteTrackFeature toEmsAudioTasteFeature(EmsCollectedTrackEntity track, String eventTrackId) {
        EmsTrackAudioFeatures audio = track.getAudioFeatures();
        if (audio == null || eventTrackId == null || eventTrackId.isBlank()) {
            return null;
        }
        String evidenceTrackId = String.valueOf(track.getId());
        EvidenceSummary evidence = evidenceSummary("ems_collected_track", evidenceTrackId);
        AudioTasteFeatureQuality.Quality quality = AudioTasteFeatureQuality.resolve(
            audio.getAudioFeatureSource(),
            audio.isAudioFeaturesFilled(),
            evidence.maxConfidence(),
            evidence.count()
        );
        return new AudioTasteTrackFeature(
            "ems_collected_track",
            eventTrackId,
            track.getTitle(),
            track.getArtistName(),
            track.getSourcePlatform(),
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
        );
    }

    private double eventWeight(UserMusicEventStore.StoredEvent event) {
        if (event == null) {
            return 0.0d;
        }
        return event.eventWeight() == null
            ? eventSignalWeights.findWeight(event.eventType()).orElse(0.0d)
            : event.eventWeight();
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

    private Diversity diversity(List<AudioTasteTrackFeature> rows) {
        List<AudioTasteTrackFeature> usableRows = rows.stream().filter(AudioTasteTrackFeature::usable).toList();
        if (usableRows.isEmpty()) {
            return new Diversity(0, null, 0.0d, 0);
        }
        Map<String, Long> byArtist = usableRows.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                row -> normalizeGroupValue(row.artistName()),
                java.util.stream.Collectors.counting()
            ));
        Map.Entry<String, Long> dominant = byArtist.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .orElse(Map.entry("", 0L));
        long sourceCount = usableRows.stream()
            .map(row -> normalizeGroupValue(row.sourcePlatform()))
            .filter(value -> !value.isBlank())
            .distinct()
            .count();
        return new Diversity(
            byArtist.size(),
            dominant.getKey().isBlank() ? null : dominant.getKey(),
            round((double) dominant.getValue() / usableRows.size()),
            Math.toIntExact(sourceCount)
        );
    }

    private SourceQualityMix sourceQualityMix(List<AudioTasteTrackFeature> rows) {
        if (rows.isEmpty()) {
            return new SourceQualityMix(0.0d, 0.0d, 0.0d, 0.0d, 0.0d);
        }
        return new SourceQualityMix(
            ratio(rows, "provider"),
            ratio(rows, "llm_accepted"),
            ratio(rows, "llm_weak"),
            ratio(rows, "lastfm_partial"),
            ratio(rows, "missing")
        );
    }

    private double ratio(List<AudioTasteTrackFeature> rows, String tier) {
        long count = rows.stream().filter(row -> Objects.equals(row.featureTier(), tier)).count();
        return round((double) count / rows.size());
    }

    private String normalizeGroupValue(String value) {
        return value == null ? "" : value.trim();
    }

    private String resolveProfileFocus(Diversity diversity, SourceQualityMix mix) {
        if (mix.llmWeak() >= 0.50d) {
            return "low_quality";
        }
        if (diversity.dominantArtistShare() >= 0.70d) {
            return "artist_narrow";
        }
        if (diversity.distinctSourcePlatformCount() <= 1) {
            return "source_narrow";
        }
        return "balanced";
    }

    private double profileConfidence(
        String profileType,
        Coverage coverage,
        SourceQualityMix mix,
        String profileFocus,
        int negativeTrackCount
    ) {
        double base = switch (profileType) {
            case PROFILE_HEAVY -> 0.90d;
            case PROFILE_STRONG -> 0.75d;
            case PROFILE_READY -> 0.55d;
            case PROFILE_WEAK -> 0.25d;
            default -> 0.0d;
        };
        double sourceQuality = (mix.provider() * 1.0d)
            + (mix.llmAccepted() * 0.70d)
            + (mix.llmWeak() * 0.35d)
            + (mix.lastfmPartial() * 0.25d);
        double diversityMultiplier = switch (profileFocus) {
            case "artist_narrow" -> 0.70d;
            case "source_narrow" -> 0.85d;
            case "low_quality" -> 0.65d;
            default -> 1.0d;
        };
        double negativeBonus = negativeTrackCount > 0 ? 0.03d : 0.0d;
        return round(clamp(
            (base * clamp(0.35d + coverage.featureReadyRatio(), 0.35d, 1.0d) * sourceQuality * diversityMultiplier)
                + negativeBonus
        ));
    }

    private String resolveProfileType(long usableTrackCount) {
        if (usableTrackCount >= 200L) {
            return PROFILE_HEAVY;
        }
        if (usableTrackCount >= 50L) {
            return PROFILE_STRONG;
        }
        if (usableTrackCount >= 10L) {
            return PROFILE_READY;
        }
        if (usableTrackCount >= 5L) {
            return PROFILE_WEAK;
        }
        return PROFILE_NONE;
    }

    private double round(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private double clamp(double value) {
        return clamp(value, 0.0d, 1.0d);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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
        String profileType,
        String profileFocus,
        double profileConfidence,
        Diversity diversity,
        SourceQualityMix sourceQualityMix,
        List<AudioTasteMode> tasteModes,
        int positiveTrackCount,
        int negativeTrackCount,
        int eventLimit,
        Centroid positiveCentroid,
        Centroid negativeCentroid,
        Coverage coverage,
        List<String> warnings,
        Instant recomputedAt
    ) {
        public Profile {
            tasteModes = tasteModes == null ? List.of() : List.copyOf(tasteModes);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
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

    public record Diversity(
        int distinctArtistCount,
        String dominantArtistName,
        double dominantArtistShare,
        int distinctSourcePlatformCount
    ) {
    }

    public record SourceQualityMix(
        double provider,
        double llmAccepted,
        double llmWeak,
        double lastfmPartial,
        double missing
    ) {
    }
}
