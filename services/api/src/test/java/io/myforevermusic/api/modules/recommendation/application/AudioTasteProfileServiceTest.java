package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsTrackAudioFeatures;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsTrackAudioFeatures;
import io.myforevermusic.api.modules.recommendation.infrastructure.local.InMemoryTrackAudioFeatureEvidenceStore;
import io.myforevermusic.api.modules.recommendation.infrastructure.local.InMemoryUserMusicEventStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
    void shouldExposeTasteModesForHeavyProfile() {
        InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
        PmsUserLibraryStore libraryStore = libraryWithTracks(200, index -> {
            if (index <= 80) {
                return track("heavy-" + index, "Dance " + index, "Dance Artist", "tidal",
                    features(0.82d, 0.74d, 0.80d));
            }
            if (index <= 150) {
                return track("heavy-" + index, "Calm " + index, "Calm Artist", "tidal",
                    featuresWithShape(0.22d, 0.45d, 0.34d, 0.82d, 82.0d));
            }
            return track("heavy-" + index, "Mid " + index, "Mid Artist", "tidal",
                featuresWithShape(0.52d, 0.54d, 0.50d, 0.32d, 106.0d));
        });
        IntStream.rangeClosed(1, 20)
            .forEach(index -> eventStore.save(event("user-1", "track_saved", "heavy-" + index, 2.0d)));

        AudioTasteProfileService.Profile profile = new AudioTasteProfileService(
            libraryStore,
            eventStore,
            new InMemoryTrackAudioFeatureEvidenceStore(),
            new EventSignalWeights(),
            10,
            0.0d
        ).recompute("user-1", 500);

        assertThat(profile.profileType()).isEqualTo("heavy");
        assertThat(profile.tasteModes()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(profile.warnings()).contains("Heavy audio taste modes are available for admin inspection.");
    }

    @Test
    void shouldReturnEmptyTasteModesForStrongProfile() {
        AudioTasteProfileService.Profile profile = profileWithReadyTracks(199);

        assertThat(profile.profileType()).isEqualTo("strong");
        assertThat(profile.tasteModes()).isEmpty();
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

    @Test
    void shouldBuildPositiveCentroidFromEmsPlaybackCompletion() {
        InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
        InMemoryTrackAudioFeatureEvidenceStore evidenceStore = new InMemoryTrackAudioFeatureEvidenceStore();
        PmsUserLibraryStore libraryStore = libraryWithTracks(0, index ->
            track("unused-" + index, "Unused " + index, "Unused Artist", "spotify", features(0.50d, 0.50d, 0.50d))
        );
        EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
        when(emsTrackRepository.findAllById(any())).thenReturn(List.of(emsTrack(
            318283L,
            "Magic",
            "TOMORROW X TOGETHER",
            "tidal",
            emsFeatures(0.76d, 0.66d, 0.82d)
        )));
        eventStore.save(emsEvent("user-1", "play_completed", "ems-track:318283", 1.0d));

        AudioTasteProfileService.Profile profile = new AudioTasteProfileService(
            libraryStore,
            eventStore,
            evidenceStore,
            new EventSignalWeights(),
            Optional.of(emsTrackRepository),
            1,
            0.0d
        ).recompute("user-1", 100);

        assertThat(profile.positiveTrackCount()).isEqualTo(1);
        assertThat(profile.coverage().trackCount()).isEqualTo(1);
        assertThat(profile.coverage().usableTrackCount()).isEqualTo(1);
        assertThat(profile.positiveCentroid().energy())
            .isCloseTo(0.76d, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(profile.positiveCentroid().valence())
            .isCloseTo(0.66d, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void shouldUseEmsItemIdWhenTrackIdIsMissing() {
        InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
        InMemoryTrackAudioFeatureEvidenceStore evidenceStore = new InMemoryTrackAudioFeatureEvidenceStore();
        PmsUserLibraryStore libraryStore = libraryWithTracks(0, index ->
            track("unused-" + index, "Unused " + index, "Unused Artist", "spotify", features(0.50d, 0.50d, 0.50d))
        );
        EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
        when(emsTrackRepository.findAllById(any())).thenReturn(List.of(emsTrack(
            318284L,
            "However",
            "10CM",
            "tidal",
            emsFeatures(0.62d, 0.58d, 0.60d)
        )));
        eventStore.save(emsItemOnlyEvent("user-1", "play_completed", "ems-track:318284", 1.0d));

        AudioTasteProfileService.Profile profile = new AudioTasteProfileService(
            libraryStore,
            eventStore,
            evidenceStore,
            new EventSignalWeights(),
            Optional.of(emsTrackRepository),
            1,
            0.0d
        ).recompute("user-1", 100);

        assertThat(profile.positiveTrackCount()).isEqualTo(1);
        assertThat(profile.coverage().trackCount()).isEqualTo(1);
        assertThat(profile.positiveCentroid().energy())
            .isCloseTo(0.62d, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void shouldIgnoreMalformedAndNegativeEmsEventsForAudioTasteInput() {
        InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
        InMemoryTrackAudioFeatureEvidenceStore evidenceStore = new InMemoryTrackAudioFeatureEvidenceStore();
        PmsUserLibraryStore libraryStore = libraryWithTracks(0, index ->
            track("unused-" + index, "Unused " + index, "Unused Artist", "spotify", features(0.50d, 0.50d, 0.50d))
        );
        EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
        eventStore.save(emsEvent("user-1", "play_completed", "ems-track:not-a-number", 1.0d));
        eventStore.save(emsEvent("user-1", "skip_next", "ems-track:318283", -0.25d));

        AudioTasteProfileService.Profile profile = new AudioTasteProfileService(
            libraryStore,
            eventStore,
            evidenceStore,
            new EventSignalWeights(),
            Optional.of(emsTrackRepository),
            1,
            0.0d
        ).recompute("user-1", 100);

        assertThat(profile.positiveTrackCount()).isZero();
        assertThat(profile.coverage().trackCount()).isZero();
        verifyNoInteractions(emsTrackRepository);
    }

    @Test
    void shouldCountOnlyReferencedEmsRowsInAudioTasteCoverage() {
        InMemoryUserMusicEventStore eventStore = new InMemoryUserMusicEventStore();
        InMemoryTrackAudioFeatureEvidenceStore evidenceStore = new InMemoryTrackAudioFeatureEvidenceStore();
        PmsUserLibraryStore libraryStore = libraryWithTracks(1, index ->
            track("pms-ready-" + index, "PMS Ready " + index, "PMS Artist", "spotify", features(0.70d, 0.60d, 0.72d))
        );
        EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
        when(emsTrackRepository.findAllById(any())).thenReturn(List.of(emsTrack(
            318283L,
            "Magic",
            "TOMORROW X TOGETHER",
            "tidal",
            emsFeatures(0.76d, 0.66d, 0.82d)
        )));
        eventStore.save(event("user-1", "track_saved", "pms-ready-1", 2.0d));
        eventStore.save(emsEvent("user-1", "play_completed", "ems-track:318283", 1.0d));

        AudioTasteProfileService.Profile profile = new AudioTasteProfileService(
            libraryStore,
            eventStore,
            evidenceStore,
            new EventSignalWeights(),
            Optional.of(emsTrackRepository),
            1,
            0.0d
        ).recompute("user-1", 100);

        assertThat(profile.coverage().trackCount()).isEqualTo(2);
        assertThat(profile.coverage().usableTrackCount()).isEqualTo(2);
        assertThat(profile.coverage().featureReadyRatio()).isEqualTo(1.0d);
        assertThat(profile.positiveTrackCount()).isEqualTo(2);
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

    private UserMusicEventStore.EventDraft emsEvent(String userId, String type, String trackId, double weight) {
        return new UserMusicEventStore.EventDraft(
            userId,
            type,
            weight,
            "player",
            "tidal",
            "tidal",
            trackId,
            "track",
            trackId,
            null,
            trackId.substring("ems-track:".length()),
            "tidal:track:" + trackId.substring("ems-track:".length()),
            "EMS Title",
            "EMS Artist",
            "EMS Album",
            null,
            180000,
            180000,
            1.0d,
            null,
            1.0d,
            Instant.parse("2026-05-21T00:00:00Z")
        );
    }

    private UserMusicEventStore.EventDraft emsItemOnlyEvent(String userId, String type, String itemId, double weight) {
        return new UserMusicEventStore.EventDraft(
            userId,
            type,
            weight,
            "player",
            "tidal",
            "tidal",
            itemId,
            "track",
            null,
            null,
            itemId.substring("ems-track:".length()),
            "tidal:track:" + itemId.substring("ems-track:".length()),
            "EMS Title",
            "EMS Artist",
            "EMS Album",
            null,
            180000,
            180000,
            1.0d,
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

    private PmsTrackAudioFeatures featuresWithShape(
        double energy,
        double valence,
        double danceability,
        double acousticness,
        double tempo
    ) {
        return new PmsTrackAudioFeatures(
            "spotify-track",
            "reccobeats_lookup",
            true,
            null,
            null,
            null,
            "audio_features",
            180000,
            1,
            1,
            4,
            acousticness,
            danceability,
            energy,
            0.01d,
            0.12d,
            -8.0d,
            0.05d,
            tempo,
            valence,
            Instant.parse("2026-05-21T00:00:00Z")
        );
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

    private EmsCollectedTrackEntity emsTrack(
        long id,
        String title,
        String artistName,
        String sourcePlatform,
        EmsTrackAudioFeatures audioFeatures
    ) {
        EmsCollectedTrackEntity track = new EmsCollectedTrackEntity(
            String.valueOf(id),
            title,
            artistName,
            sourcePlatform,
            null,
            "EMS Album",
            null,
            null,
            null,
            null,
            180000,
            "gms_playlist",
            Instant.parse("2026-05-21T00:00:00Z"),
            audioFeatures
        );
        ReflectionTestUtils.setField(track, "id", id);
        return track;
    }

    private EmsTrackAudioFeatures emsFeatures(double energy, double valence, double danceability) {
        return new EmsTrackAudioFeatures(
            "tidal-track",
            "reccobeats_lookup",
            true,
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
