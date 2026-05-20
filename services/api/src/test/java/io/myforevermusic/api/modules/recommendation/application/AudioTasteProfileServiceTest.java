package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsTrackAudioFeatures;
import io.myforevermusic.api.modules.recommendation.infrastructure.local.InMemoryTrackAudioFeatureEvidenceStore;
import io.myforevermusic.api.modules.recommendation.infrastructure.local.InMemoryUserMusicEventStore;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AudioTasteProfileServiceTest {

    @Test
    void shouldBuildPositiveCentroidFromWeightedEvents() {
        InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
        InMemoryTrackAudioFeatureEvidenceStore evidenceStore = new InMemoryTrackAudioFeatureEvidenceStore();
        PmsUserLibraryStore libraryStore = new PmsUserLibraryStore() {
            @Override
            public List<PmsUserLibraryStore.LibraryPlaylistState> findPlaylists(String userId) {
                return List.of(new PmsUserLibraryStore.LibraryPlaylistState(
                    userId,
                    "playlist-1",
                    "external-1",
                    "Library",
                    "spotify",
                    "me",
                    "",
                    null,
                    null,
                    null,
                    Instant.parse("2026-05-21T00:00:00Z"),
                    IntStream.rangeClosed(1, 10)
                        .mapToObj(index -> track("track-" + index, "Bright " + index, 0.80d, 0.70d, 0.90d))
                        .toList()
                ));
            }

            @Override
            public List<PmsUserLibraryStore.LibraryPlaylistState> savePlaylists(
                String userId,
                List<PmsUserLibraryStore.LibraryPlaylistState> playlists
            ) {
                return playlists;
            }
        };
        IntStream.rangeClosed(1, 10)
            .forEach(index -> eventStore.save(event("user-1", "track_saved", "track-" + index, 2.0d)));

        AudioTasteProfileService.Profile profile = new AudioTasteProfileService(
            libraryStore,
            eventStore,
            evidenceStore,
            new EventSignalWeights()
        ).recompute("user-1", 100);

        assertThat(profile.status()).isEqualTo("ok");
        assertThat(profile.positiveTrackCount()).isEqualTo(10);
        assertThat(profile.coverage().featureReadyRatio()).isEqualTo(1.0d);
        assertThat(profile.positiveCentroid().energy()).isCloseTo(0.80d, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(profile.positiveCentroid().valence()).isCloseTo(0.70d, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void shouldReturnInsufficientDataWhenReadyTrackCountIsBelowGate() {
        InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
        InMemoryTrackAudioFeatureEvidenceStore evidenceStore = new InMemoryTrackAudioFeatureEvidenceStore();
        PmsUserLibraryStore libraryStore = new PmsUserLibraryStore() {
            @Override
            public List<PmsUserLibraryStore.LibraryPlaylistState> findPlaylists(String userId) {
                return List.of();
            }

            @Override
            public List<PmsUserLibraryStore.LibraryPlaylistState> savePlaylists(
                String userId,
                List<PmsUserLibraryStore.LibraryPlaylistState> playlists
            ) {
                return playlists;
            }
        };

        AudioTasteProfileService.Profile profile = new AudioTasteProfileService(
            libraryStore,
            eventStore,
            evidenceStore,
            new EventSignalWeights()
        ).recompute("user-1", 100);

        assertThat(profile.status()).isEqualTo("insufficient_data");
        assertThat(profile.audioTasteApplicable()).isFalse();
        assertThat(profile.warnings()).contains("Audio taste profile requires at least 10 positive feature-ready tracks.");
    }

    @Test
    void shouldAllowLowerFeatureCoverageGateForServerVerification() {
        InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
        InMemoryTrackAudioFeatureEvidenceStore evidenceStore = new InMemoryTrackAudioFeatureEvidenceStore();
        PmsUserLibraryStore libraryStore = new PmsUserLibraryStore() {
            @Override
            public List<PmsUserLibraryStore.LibraryPlaylistState> findPlaylists(String userId) {
                List<PmsUserLibraryStore.LibraryTrackState> tracks = new java.util.ArrayList<>();
                IntStream.rangeClosed(1, 10)
                    .mapToObj(index -> track("ready-" + index, "Ready " + index, 0.80d, 0.70d, 0.90d))
                    .forEach(tracks::add);
                IntStream.rangeClosed(1, 90)
                    .mapToObj(index -> track(
                        "missing-" + index,
                        "Missing " + index,
                        0.80d,
                        0.70d,
                        0.90d,
                        PmsTrackAudioFeatures.unresolved()
                    ))
                    .forEach(tracks::add);
                return List.of(new PmsUserLibraryStore.LibraryPlaylistState(
                    userId,
                    "playlist-coverage",
                    "external-coverage",
                    "Coverage",
                    "spotify",
                    "me",
                    "",
                    null,
                    null,
                    null,
                    Instant.parse("2026-05-21T00:00:00Z"),
                    tracks
                ));
            }

            @Override
            public List<PmsUserLibraryStore.LibraryPlaylistState> savePlaylists(
                String userId,
                List<PmsUserLibraryStore.LibraryPlaylistState> playlists
            ) {
                return playlists;
            }
        };
        IntStream.rangeClosed(1, 10)
            .forEach(index -> eventStore.save(event("user-1", "track_saved", "ready-" + index, 2.0d)));

        AudioTasteProfileService.Profile defaultProfile = new AudioTasteProfileService(
            libraryStore,
            eventStore,
            evidenceStore,
            new EventSignalWeights()
        ).recompute("user-1", 100);
        AudioTasteProfileService.Profile relaxedProfile = new AudioTasteProfileService(
            libraryStore,
            eventStore,
            evidenceStore,
            new EventSignalWeights(),
            10,
            0.07d
        ).recompute("user-1", 100);

        assertThat(defaultProfile.audioTasteApplicable()).isFalse();
        assertThat(defaultProfile.warnings()).contains("Audio taste feature coverage is below 0.30.");
        assertThat(relaxedProfile.audioTasteApplicable()).isTrue();
        assertThat(relaxedProfile.status()).isEqualTo("ok");
    }

    @Test
    void shouldClassifyProfileTypeFromFeatureReadyLibrarySize() {
        assertThat(profileWithReadyTracks(4).profileType()).isEqualTo("none");
        assertThat(profileWithReadyTracks(5).profileType()).isEqualTo("weak");
        assertThat(profileWithReadyTracks(10).profileType()).isEqualTo("ready");
        assertThat(profileWithReadyTracks(50).profileType()).isEqualTo("strong");
        assertThat(profileWithReadyTracks(200).profileType()).isEqualTo("heavy");
    }

    @Test
    void shouldKeepWeakProfileNotApplicableForServing() {
        AudioTasteProfileService.Profile profile = profileWithReadyTracks(5);

        assertThat(profile.profileType()).isEqualTo("weak");
        assertThat(profile.audioTasteApplicable()).isFalse();
        assertThat(profile.warnings())
            .contains("Audio taste profile is weak until at least 10 positive feature-ready tracks.");
    }

    @Test
    void shouldMarkReadyProfileAsArtistNarrowWhenOneArtistDominates() {
        InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
        PmsUserLibraryStore libraryStore = libraryWithTracks(10, index -> {
            String artist = index <= 8 ? "Dominant Artist" : "Other Artist " + index;
            return track("narrow-" + index, "Narrow " + index, artist, "spotify", features(0.75d, 0.65d, 0.70d));
        });
        IntStream.rangeClosed(1, 10)
            .forEach(index -> eventStore.save(event("user-1", "track_saved", "narrow-" + index, 2.0d)));

        AudioTasteProfileService.Profile profile = new AudioTasteProfileService(
            libraryStore,
            eventStore,
            new InMemoryTrackAudioFeatureEvidenceStore(),
            new EventSignalWeights(),
            10,
            0.0d
        ).recompute("user-1", 500);

        assertThat(profile.profileType()).isEqualTo("ready");
        assertThat(profile.profileFocus()).isEqualTo("artist_narrow");
        assertThat(profile.diversity().distinctArtistCount()).isEqualTo(3);
        assertThat(profile.diversity().dominantArtistName()).isEqualTo("Dominant Artist");
        assertThat(profile.diversity().dominantArtistShare()).isEqualTo(0.8d);
        assertThat(profile.warnings()).contains("Audio taste profile is artist-narrow; boost will be dampened.");
    }

    @Test
    void shouldMarkProfileAsLowQualityWhenWeakTierDominates() {
        InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
        InMemoryTrackAudioFeatureEvidenceStore evidenceStore = new InMemoryTrackAudioFeatureEvidenceStore();
        PmsUserLibraryStore libraryStore = libraryWithTracks(10, index ->
            track(
                "weak-quality-" + index,
                "Weak " + index,
                "Artist " + index,
                "spotify",
                featuresWithSource("llm_search_low_confidence", false, 0.75d, 0.65d, 0.70d)
            )
        );
        IntStream.rangeClosed(1, 10).forEach(index -> {
            String trackId = "weak-quality-" + index;
            eventStore.save(event("user-1", "track_saved", trackId, 2.0d));
            evidenceStore.saveAll(List.of(new TrackAudioFeatureEvidenceStore.Draft(
                "pms_user_track",
                trackId,
                "llm",
                "llm_search_inferred",
                null,
                "model_output",
                "{\"confidence\":0.55}",
                0.55d,
                Instant.parse("2026-05-21T00:00:00Z"),
                null
            )));
        });

        AudioTasteProfileService.Profile profile = new AudioTasteProfileService(
            libraryStore,
            eventStore,
            evidenceStore,
            new EventSignalWeights(),
            10,
            0.0d
        ).recompute("user-1", 500);

        assertThat(profile.profileFocus()).isEqualTo("low_quality");
        assertThat(profile.sourceQualityMix().llmWeak()).isEqualTo(1.0d);
        assertThat(profile.profileConfidence()).isLessThan(0.40d);
        assertThat(profile.warnings()).contains("Audio taste profile is low-quality; weak inferred features dominate.");
    }

    private AudioTasteProfileService.Profile profileWithReadyTracks(int count) {
        InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
        InMemoryTrackAudioFeatureEvidenceStore evidenceStore = new InMemoryTrackAudioFeatureEvidenceStore();
        PmsUserLibraryStore libraryStore = libraryWithTracks(count, index ->
            track(
                "ready-" + index,
                "Ready " + index,
                "Artist " + index,
                "spotify",
                features(0.75d, 0.65d, 0.70d)
            )
        );
        IntStream.rangeClosed(1, count)
            .forEach(index -> eventStore.save(event("user-1", "track_saved", "ready-" + index, 2.0d)));

        return new AudioTasteProfileService(
            libraryStore,
            eventStore,
            evidenceStore,
            new EventSignalWeights(),
            10,
            0.0d
        ).recompute("user-1", 500);
    }

    private interface TrackFactory {
        PmsUserLibraryStore.LibraryTrackState create(int index);
    }

    private PmsUserLibraryStore libraryWithTracks(int count, TrackFactory factory) {
        return new PmsUserLibraryStore() {
            @Override
            public List<PmsUserLibraryStore.LibraryPlaylistState> findPlaylists(String userId) {
                return List.of(new PmsUserLibraryStore.LibraryPlaylistState(
                    userId,
                    "playlist-profile-type",
                    "external-profile-type",
                    "Profile Type",
                    "spotify",
                    "me",
                    "",
                    null,
                    null,
                    null,
                    Instant.parse("2026-05-21T00:00:00Z"),
                    IntStream.rangeClosed(1, count).mapToObj(factory::create).toList()
                ));
            }

            @Override
            public List<PmsUserLibraryStore.LibraryPlaylistState> savePlaylists(
                String userId,
                List<PmsUserLibraryStore.LibraryPlaylistState> playlists
            ) {
                return playlists;
            }
        };
    }

    private UserMusicEventStore.EventDraft event(String userId, String type, String trackId, double weight) {
        return new UserMusicEventStore.EventDraft(
            userId,
            type,
            weight,
            "pms",
            "spotify",
            "spotify",
            trackId,
            "track",
            trackId,
            "playlist-1",
            trackId,
            null,
            "Title",
            "Artist",
            null,
            null,
            180000,
            null,
            null,
            null,
            1.0d,
            Instant.parse("2026-05-21T00:00:00Z")
        );
    }

    private PmsUserLibraryStore.LibraryTrackState track(
        String trackId,
        String title,
        double energy,
        double valence,
        double danceability
    ) {
        return track(trackId, title, energy, valence, danceability, features(energy, valence, danceability));
    }

    private PmsUserLibraryStore.LibraryTrackState track(
        String trackId,
        String title,
        double energy,
        double valence,
        double danceability,
        PmsTrackAudioFeatures audioFeatures
    ) {
        return new PmsUserLibraryStore.LibraryTrackState(
            trackId,
            trackId,
            title,
            "Artist",
            "spotify",
            "pop",
            null,
            null,
            null,
            null,
            null,
            1,
            false,
            audioFeatures
        );
    }

    private PmsUserLibraryStore.LibraryTrackState track(
        String trackId,
        String title,
        String artistName,
        String sourcePlatform,
        PmsTrackAudioFeatures audioFeatures
    ) {
        return new PmsUserLibraryStore.LibraryTrackState(
            trackId,
            trackId,
            title,
            artistName,
            sourcePlatform,
            "pop",
            null,
            null,
            null,
            null,
            null,
            1,
            false,
            audioFeatures
        );
    }

    private PmsTrackAudioFeatures features(double energy, double valence, double danceability) {
        return featuresWithSource("reccobeats_lookup", true, energy, valence, danceability);
    }

    private PmsTrackAudioFeatures featuresWithSource(
        String source,
        boolean filled,
        double energy,
        double valence,
        double danceability
    ) {
        return new PmsTrackAudioFeatures(
            "spotify-track",
            source,
            filled,
            null,
            null,
            null,
            "audio_features",
            180000,
            1,
            1,
            4,
            0.20d,
            danceability,
            energy,
            0.01d,
            0.12d,
            -8.0d,
            0.05d,
            120.0d,
            valence,
            Instant.parse("2026-05-21T00:00:00Z")
        );
    }
}
