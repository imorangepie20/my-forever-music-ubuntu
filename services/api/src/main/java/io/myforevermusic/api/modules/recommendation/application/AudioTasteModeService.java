package io.myforevermusic.api.modules.recommendation.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class AudioTasteModeService {

    private static final String PROFILE_HEAVY = "heavy";
    private static final int MIN_HEAVY_ROWS = 200;

    public List<AudioTasteMode> buildModes(
        String profileType,
        double profileConfidence,
        List<AudioTasteTrackFeature> rows
    ) {
        List<AudioTasteTrackFeature> usableRows = rows == null
            ? List.of()
            : rows.stream().filter(AudioTasteTrackFeature::usable).toList();
        if (!PROFILE_HEAVY.equals(profileType) || usableRows.size() < MIN_HEAVY_ROWS) {
            return List.of();
        }

        int targetModeCount = targetModeCount(usableRows.size());
        List<Bucket> buckets = usableRows.stream()
            .collect(Collectors.groupingBy(this::bucketLabel))
            .entrySet()
            .stream()
            .map(entry -> new Bucket(entry.getKey(), new ArrayList<>(entry.getValue())))
            .sorted(bucketComparator())
            .toList();
        List<Bucket> kept = buckets.stream()
            .limit(targetModeCount)
            .map(bucket -> new Bucket(bucket.label(), new ArrayList<>(bucket.rows())))
            .toList();
        Map<String, Bucket> keptByLabel = kept.stream()
            .collect(Collectors.toMap(Bucket::label, bucket -> bucket, (left, right) -> left, LinkedHashMap::new));

        buckets.stream()
            .skip(targetModeCount)
            .forEach(bucket -> nearestKeptBucket(bucket, kept).rows().addAll(bucket.rows()));

        List<Bucket> merged = new ArrayList<>(keptByLabel.values());
        merged.sort(bucketComparator());
        List<AudioTasteMode> modes = new ArrayList<>();
        for (int index = 0; index < merged.size(); index++) {
            Bucket bucket = merged.get(index);
            AudioTasteProfileService.Centroid centroid = centroid(bucket.rows());
            modes.add(new AudioTasteMode(
                "mode-" + (index + 1),
                bucket.label(),
                bucket.rows().size(),
                confidence(profileConfidence, bucket.rows()),
                centroid,
                topArtists(bucket.rows()),
                representativeTracks(bucket.rows(), centroid)
            ));
        }
        return List.copyOf(modes);
    }

    private int targetModeCount(int usableTrackCount) {
        if (usableTrackCount >= 800) {
            return 8;
        }
        if (usableTrackCount >= 400) {
            return 6;
        }
        return 4;
    }

    private String bucketLabel(AudioTasteTrackFeature row) {
        List<String> tokens = new ArrayList<>();
        double energy = feature(row.energy());
        if (energy >= 0.65d) {
            tokens.add("high_energy");
        } else if (energy <= 0.35d) {
            tokens.add("low_energy");
        } else {
            tokens.add("mid_energy");
        }

        double valence = feature(row.valence());
        if (valence >= 0.62d) {
            tokens.add("bright");
        } else if (valence <= 0.38d) {
            tokens.add("dark");
        } else {
            tokens.add("neutral");
        }

        if (feature(row.danceability()) >= 0.65d) {
            tokens.add("danceable");
        }
        if (feature(row.acousticness()) >= 0.65d) {
            tokens.add("acoustic");
        }

        double tempo = normalizeTempo(row.tempo());
        if (tempo >= 0.65d) {
            tokens.add("fast");
        } else if (tempo <= 0.35d) {
            tokens.add("slow");
        }
        return String.join("_", tokens);
    }

    private Bucket nearestKeptBucket(Bucket source, List<Bucket> kept) {
        AudioTasteProfileService.Centroid sourceCentroid = centroid(source.rows());
        return kept.stream()
            .min(Comparator
                .comparingDouble((Bucket bucket) -> distance(sourceCentroid, centroid(bucket.rows())))
                .thenComparing(Bucket::label))
            .orElseThrow();
    }

    private AudioTasteProfileService.Centroid centroid(List<AudioTasteTrackFeature> rows) {
        double totalWeight = rows.stream().mapToDouble(row -> Math.max(0.0d, row.featureWeight())).sum();
        if (totalWeight <= 0.0d) {
            return AudioTasteProfileService.Centroid.empty();
        }
        return new AudioTasteProfileService.Centroid(
            weighted(rows, totalWeight, FeatureKey.ACOUSTICNESS),
            weighted(rows, totalWeight, FeatureKey.DANCEABILITY),
            weighted(rows, totalWeight, FeatureKey.ENERGY),
            weighted(rows, totalWeight, FeatureKey.INSTRUMENTALNESS),
            weighted(rows, totalWeight, FeatureKey.LIVENESS),
            weighted(rows, totalWeight, FeatureKey.SPEECHINESS),
            rows.stream().mapToDouble(row -> normalizeTempo(row.tempo()) * Math.max(0.0d, row.featureWeight())).sum() / totalWeight,
            weighted(rows, totalWeight, FeatureKey.VALENCE)
        );
    }

    private double weighted(List<AudioTasteTrackFeature> rows, double totalWeight, FeatureKey key) {
        return rows.stream()
            .mapToDouble(row -> featureValue(row, key) * Math.max(0.0d, row.featureWeight()))
            .sum() / totalWeight;
    }

    private double confidence(double profileConfidence, List<AudioTasteTrackFeature> rows) {
        double averageFeatureWeight = rows.stream()
            .mapToDouble(row -> clamp(row.featureWeight()))
            .average()
            .orElse(0.0d);
        double topArtistShare = topArtistShare(rows);
        double artistDiversityMultiplier;
        if (topArtistShare >= 0.70d) {
            artistDiversityMultiplier = 0.70d;
        } else if (topArtistShare >= 0.50d) {
            artistDiversityMultiplier = 0.85d;
        } else {
            artistDiversityMultiplier = 1.0d;
        }
        double sizeMultiplier = Math.min(1.0d, Math.sqrt(rows.size() / 50.0d));
        return round(clamp(profileConfidence) * sizeMultiplier * averageFeatureWeight * artistDiversityMultiplier);
    }

    private double topArtistShare(List<AudioTasteTrackFeature> rows) {
        Map<String, Integer> counts = artistCounts(rows);
        return counts.values().stream()
            .mapToInt(Integer::intValue)
            .max()
            .stream()
            .mapToDouble(count -> (double) count / rows.size())
            .findFirst()
            .orElse(0.0d);
    }

    private List<AudioTasteMode.TopArtist> topArtists(List<AudioTasteTrackFeature> rows) {
        return artistCounts(rows).entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry::getKey))
            .limit(5)
            .map(entry -> new AudioTasteMode.TopArtist(entry.getKey(), entry.getValue()))
            .toList();
    }

    private Map<String, Integer> artistCounts(List<AudioTasteTrackFeature> rows) {
        Map<String, Integer> counts = new HashMap<>();
        for (AudioTasteTrackFeature row : rows) {
            String artist = normalizeText(row.artistName());
            if (!artist.isBlank()) {
                counts.merge(artist, 1, Integer::sum);
            }
        }
        return counts;
    }

    private List<AudioTasteMode.RepresentativeTrack> representativeTracks(
        List<AudioTasteTrackFeature> rows,
        AudioTasteProfileService.Centroid centroid
    ) {
        return rows.stream()
            .map(row -> new TrackDistance(row, distance(row, centroid)))
            .sorted(Comparator
                .comparingDouble(TrackDistance::distance)
                .thenComparing(distance -> normalizeText(distance.row().trackId())))
            .limit(5)
            .map(distance -> new AudioTasteMode.RepresentativeTrack(
                normalizeText(distance.row().trackId()),
                normalizeText(distance.row().title()),
                normalizeText(distance.row().artistName()),
                normalizeText(distance.row().sourcePlatform()),
                round(distance.distance())
            ))
            .toList();
    }

    private double distance(AudioTasteTrackFeature row, AudioTasteProfileService.Centroid centroid) {
        return distance(vector(row), vector(centroid));
    }

    private double distance(AudioTasteProfileService.Centroid left, AudioTasteProfileService.Centroid right) {
        return distance(vector(left), vector(right));
    }

    private double distance(double[] left, double[] right) {
        double sum = 0.0d;
        for (int index = 0; index < left.length; index++) {
            double delta = left[index] - right[index];
            sum += delta * delta;
        }
        return Math.sqrt(sum);
    }

    private double[] vector(AudioTasteTrackFeature row) {
        return new double[] {
            feature(row.acousticness()),
            feature(row.danceability()),
            feature(row.energy()),
            feature(row.instrumentalness()),
            feature(row.liveness()),
            feature(row.speechiness()),
            normalizeTempo(row.tempo()),
            feature(row.valence())
        };
    }

    private double[] vector(AudioTasteProfileService.Centroid centroid) {
        return new double[] {
            centroid.acousticness(),
            centroid.danceability(),
            centroid.energy(),
            centroid.instrumentalness(),
            centroid.liveness(),
            centroid.speechiness(),
            centroid.tempo(),
            centroid.valence()
        };
    }

    private double featureValue(AudioTasteTrackFeature row, FeatureKey key) {
        return switch (key) {
            case ACOUSTICNESS -> feature(row.acousticness());
            case DANCEABILITY -> feature(row.danceability());
            case ENERGY -> feature(row.energy());
            case INSTRUMENTALNESS -> feature(row.instrumentalness());
            case LIVENESS -> feature(row.liveness());
            case SPEECHINESS -> feature(row.speechiness());
            case VALENCE -> feature(row.valence());
        };
    }

    private double feature(Double value) {
        return value == null ? 0.5d : clamp(value);
    }

    private double normalizeTempo(Double tempo) {
        return tempo == null ? 0.5d : clamp((tempo - 60.0d) / 140.0d);
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private double round(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private Comparator<Bucket> bucketComparator() {
        return Comparator
            .comparingInt((Bucket bucket) -> bucket.rows().size())
            .reversed()
            .thenComparing(Bucket::label);
    }

    private enum FeatureKey {
        ACOUSTICNESS,
        DANCEABILITY,
        ENERGY,
        INSTRUMENTALNESS,
        LIVENESS,
        SPEECHINESS,
        VALENCE
    }

    private record Bucket(String label, List<AudioTasteTrackFeature> rows) {
    }

    private record TrackDistance(AudioTasteTrackFeature row, double distance) {
    }
}
