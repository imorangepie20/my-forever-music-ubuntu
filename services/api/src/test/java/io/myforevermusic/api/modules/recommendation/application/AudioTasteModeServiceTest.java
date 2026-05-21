package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AudioTasteModeServiceTest {

    private final AudioTasteModeService service = new AudioTasteModeService();

    @Test
    void shouldReturnEmptyModesForNonHeavyProfile() {
        List<AudioTasteMode> modes = service.buildModes("strong", 0.80d, rows(250, "ready", 0.70d, 0.70d, 0.70d, "Artist"));

        assertThat(modes).isEmpty();
    }

    @Test
    void shouldBuildTasteModesForHeavyProfileRows() {
        List<AudioTasteTrackFeature> rows = new ArrayList<>();
        rows.addAll(rows(70, "bright-fast", 0.80d, 0.80d, 0.82d, "Artist A"));
        rows.addAll(rows(60, "dark-slow", 0.20d, 0.20d, 0.25d, "Artist B"));
        rows.addAll(rows(50, "dance", 0.50d, 0.72d, 0.45d, "Artist C"));
        rows.addAll(rows(40, "acoustic", 0.70d, 0.20d, 0.50d, "Artist D"));

        List<AudioTasteMode> modes = service.buildModes("heavy", 0.90d, rows);

        assertThat(modes).hasSize(4);
        assertThat(modes).extracting(AudioTasteMode::modeId)
            .containsExactly("mode-1", "mode-2", "mode-3", "mode-4");
        assertThat(modes.get(0).label()).contains("high_energy", "bright", "danceable", "fast");
        assertThat(modes.get(0).trackCount()).isEqualTo(70);
        assertThat(modes.get(0).centroid().tempo()).isCloseTo(0.7143d, org.assertj.core.data.Offset.offset(0.0001d));
        assertThat(modes.get(0).topArtists())
            .containsExactly(new AudioTasteMode.TopArtist("Artist A", 70));
        assertThat(modes.get(0).representativeTracks()).hasSize(5);
        assertThat(modes.get(0).confidence()).isEqualTo(0.63d);
    }

    @Test
    void shouldOmitTempoTokenForMidTempoRows() {
        List<AudioTasteMode> modes = service.buildModes("heavy", 0.90d, rows(
            200,
            "mid-neutral",
            0.50d,
            0.50d,
            0.50d,
            "Artist"
        ));

        assertThat(modes).hasSize(1);
        assertThat(modes.get(0).label()).isEqualTo("mid_energy_neutral");
    }

    @Test
    void shouldMergeSmallBucketsIntoNearestKeptMode() {
        List<AudioTasteTrackFeature> rows = new ArrayList<>();
        rows.addAll(rows(80, "big-bright", 0.80d, 0.80d, 0.50d, "Artist A"));
        rows.addAll(rows(60, "big-dark", 0.20d, 0.20d, 0.50d, "Artist B"));
        rows.addAll(rows(40, "big-neutral", 0.50d, 0.50d, 0.50d, "Artist C"));
        rows.addAll(rows(30, "big-acoustic", 0.72d, 0.20d, 0.50d, "Artist D"));
        rows.addAll(rows(12, "small-near-bright", 0.78d, 0.78d, 0.50d, "Artist E"));

        List<AudioTasteMode> modes = service.buildModes("heavy", 0.90d, rows);

        assertThat(modes).hasSize(4);
        assertThat(modes.get(0).label()).isEqualTo("high_energy_bright_fast");
        assertThat(modes.get(0).trackCount()).isEqualTo(92);
        assertThat(modes.get(0).topArtists())
            .contains(
                new AudioTasteMode.TopArtist("Artist A", 80),
                new AudioTasteMode.TopArtist("Artist E", 12)
            );
    }

    @Test
    void shouldRankBucketsByPositiveFeatureWeightBeforeRawTrackCount() {
        List<AudioTasteTrackFeature> rows = new ArrayList<>();
        rows.addAll(rows(90, "provider-bright", 0.80d, 0.80d, 0.50d, "Provider Artist", 1.0d));
        rows.addAll(rows(110, "weak-dark", 0.20d, 0.20d, 0.50d, "Weak Artist", 0.20d));

        List<AudioTasteMode> modes = service.buildModes("heavy", 0.90d, rows);

        assertThat(modes).extracting(AudioTasteMode::label)
            .containsExactly("high_energy_bright_fast", "low_energy_dark_slow");
        assertThat(modes).extracting(AudioTasteMode::trackCount)
            .containsExactly(90, 110);
    }

    @Test
    void shouldLowerConfidenceWhenModeIsDominatedByOneArtist() {
        List<AudioTasteTrackFeature> diverseRows = new ArrayList<>();
        diverseRows.addAll(rows(40, "diverse-a", 0.80d, 0.80d, 0.50d, "Artist A"));
        diverseRows.addAll(rows(40, "diverse-b", 0.80d, 0.80d, 0.50d, "Artist B"));
        diverseRows.addAll(rows(40, "diverse-c", 0.80d, 0.80d, 0.50d, "Artist C"));
        diverseRows.addAll(rows(40, "diverse-d", 0.80d, 0.80d, 0.50d, "Artist D"));
        diverseRows.addAll(rows(40, "diverse-e", 0.80d, 0.80d, 0.50d, "Artist E"));

        List<AudioTasteTrackFeature> dominatedRows = rows(200, "dominated", 0.80d, 0.80d, 0.50d, "Dominant Artist");

        AudioTasteMode diverse = service.buildModes("heavy", 0.90d, diverseRows).get(0);
        AudioTasteMode dominated = service.buildModes("heavy", 0.90d, dominatedRows).get(0);

        assertThat(dominated.confidence()).isLessThan(diverse.confidence());
        assertThat(dominated.confidence()).isEqualTo(0.63d);
        assertThat(diverse.confidence()).isEqualTo(0.90d);
    }

    @Test
    void shouldSortRepresentativeTracksByDistanceToCentroid() {
        List<AudioTasteTrackFeature> rows = new ArrayList<>();
        rows.addAll(rows(195, "filler", 0.50d, 0.50d, 0.50d, "Filler Artist", 0.01d));
        rows.add(feature("track-z", "Zed", "Artist", "spotify", 1.0d, 0.49d, 0.50d, 0.50d, 0.0d, 0.50d, 0.50d, 130.0d, 0.50d));
        rows.add(feature("track-a", "Alpha", "Artist", "spotify", 1.0d, 0.49d, 0.50d, 0.50d, 0.0d, 0.50d, 0.50d, 130.0d, 0.50d));
        rows.add(feature("track-near", "Near", "Artist", "spotify", 1.0d, 0.51d, 0.50d, 0.50d, 0.0d, 0.50d, 0.50d, 130.0d, 0.50d));
        rows.add(feature("track-far", "Far", "Artist", "spotify", 1.0d, 0.56d, 0.50d, 0.50d, 0.0d, 0.50d, 0.50d, 130.0d, 0.50d));
        rows.add(feature("track-farthest", "Farthest", "Artist", "spotify", 1.0d, 0.60d, 0.50d, 0.50d, 0.0d, 0.50d, 0.50d, 130.0d, 0.50d));

        List<AudioTasteMode.RepresentativeTrack> representatives = service
            .buildModes("heavy", 0.90d, rows)
            .get(0)
            .representativeTracks();

        assertThat(representatives).extracting(AudioTasteMode.RepresentativeTrack::trackId)
            .containsExactly("track-a", "track-z", "track-near", "track-far", "track-farthest");
        assertThat(representatives).extracting(AudioTasteMode.RepresentativeTrack::distanceToCentroid)
            .isSorted();
        assertThat(representatives.get(0).distanceToCentroid())
            .isEqualTo(representatives.get(1).distanceToCentroid());
    }

    private List<AudioTasteTrackFeature> rows(
        int count,
        String prefix,
        double energy,
        double valence,
        double danceability,
        String artistName
    ) {
        return rows(count, prefix, energy, valence, danceability, artistName, 1.0d);
    }

    private List<AudioTasteTrackFeature> rows(
        int count,
        String prefix,
        double energy,
        double valence,
        double danceability,
        String artistName,
        double featureWeight
    ) {
        return IntStream.rangeClosed(1, count)
            .mapToObj(index -> feature(
                "%s-%03d".formatted(prefix, index),
                "Title %03d".formatted(index),
                artistName,
                "spotify",
                featureWeight,
                0.20d,
                danceability,
                energy,
                0.01d,
                0.10d,
                0.05d,
                tempoFor(energy),
                valence
            ))
            .toList();
    }

    private double tempoFor(double energy) {
        if (energy >= 0.65d) {
            return 160.0d;
        }
        if (energy <= 0.35d) {
            return 90.0d;
        }
        return 130.0d;
    }

    private AudioTasteTrackFeature feature(
        String trackId,
        String title,
        String artistName,
        String sourcePlatform,
        double featureWeight,
        Double acousticness,
        Double danceability,
        Double energy,
        Double instrumentalness,
        Double liveness,
        Double speechiness,
        Double tempo,
        Double valence
    ) {
        return new AudioTasteTrackFeature(
            "pms_user_track",
            trackId,
            title,
            artistName,
            sourcePlatform,
            "reccobeats_lookup",
            true,
            featureWeight,
            "provider",
            acousticness,
            danceability,
            energy,
            instrumentalness,
            liveness,
            speechiness,
            tempo,
            valence
        );
    }
}
