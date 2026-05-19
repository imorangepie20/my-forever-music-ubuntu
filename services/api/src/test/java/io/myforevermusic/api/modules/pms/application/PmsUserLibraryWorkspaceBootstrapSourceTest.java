package io.myforevermusic.api.modules.pms.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.myforevermusic.api.modules.pms.infrastructure.local.InMemoryPmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsTrackAudioFeatures;
import io.myforevermusic.api.modules.pms.presentation.PmsWorkspaceBootstrapResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PmsUserLibraryWorkspaceBootstrapSourceTest {

    @Test
    void shouldProjectSyncedUserLibraryIntoWorkspaceBootstrap() {
        InMemoryPmsUserLibraryStore userLibraryStore = new InMemoryPmsUserLibraryStore();
        userLibraryStore.savePlaylists(
            "user-001",
            List.of(
                new PmsUserLibraryStore.LibraryPlaylistState(
                    "user-001",
                    "playlist-001",
                    "spotify-playlist-001",
                    "Night Drive Archive",
                    "spotify",
                    "Forever Listener",
                    "Synced from imported playlists.",
                    null,
                    "https://open.spotify.com/playlist/spotify-playlist-001",
                    "spotify:playlist:spotify-playlist-001",
                    Instant.parse("2026-05-04T00:00:00Z"),
                    List.of(
                        new PmsUserLibraryStore.LibraryTrackState(
                            "track-001",
                            "spotify-track-001",
                            "Midnight Receiver",
                            "Neon Bloom",
                            "spotify",
                            "synth-pop",
                            "Signal Bloom",
                            null,
                            "https://open.spotify.com/track/spotify-track-001",
                            "spotify:track:spotify-track-001",
                            null,
                            1,
                            true,
                            sampleFeatures("spotify-track-001")
                        ),
                        new PmsUserLibraryStore.LibraryTrackState(
                            "track-002",
                            "spotify-track-002",
                            "Blue Static",
                            "Neon Bloom",
                            "spotify",
                            "synth-pop",
                            "Signal Bloom",
                            null,
                            "https://open.spotify.com/track/spotify-track-002",
                            "spotify:track:spotify-track-002",
                            null,
                            2,
                            false,
                            sampleFeatures("spotify-track-002")
                        ),
                        new PmsUserLibraryStore.LibraryTrackState(
                            "track-003",
                            "spotify-track-003",
                            "Slow Orbit",
                            "Soft Cascade",
                            "spotify",
                            "dream-pop",
                            "Soft Signal",
                            null,
                            "https://open.spotify.com/track/spotify-track-003",
                            "spotify:track:spotify-track-003",
                            null,
                            3,
                            true,
                            sampleFeatures("spotify-track-003")
                        )
                    )
                )
            )
        );

        PmsUserLibraryWorkspaceBootstrapSource source = new PmsUserLibraryWorkspaceBootstrapSource(userLibraryStore);

        Optional<PmsWorkspaceBootstrapResponse> result = source.load("user-001", null);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().workspaceDefaults().playlistId()).isEqualTo("playlist-001");
        assertThat(result.orElseThrow().workspaceDefaults().seedTrackIds())
            .containsExactly("track-001", "track-003");
        assertThat(result.orElseThrow().workspaceDefaults().seedArtistNames())
            .containsExactly("Neon Bloom", "Soft Cascade");
        assertThat(result.orElseThrow().workspaceDefaults().seedGenres())
            .containsExactly("synth-pop", "dream-pop");
        assertThat(result.orElseThrow().playlists().getFirst().sourceCollection())
            .isEqualTo("pms-user-library");
        assertThat(result.orElseThrow().suggestedTracks()).allMatch(
            PmsWorkspaceBootstrapResponse.TrackSeedSuggestion::audioFeaturesFilled
        );
        assertThat(result.orElseThrow().suggestedArtists().getFirst().artistName()).isEqualTo("Neon Bloom");
        assertThat(result.orElseThrow().suggestedGenres().getFirst().genre()).isEqualTo("synth-pop");
    }

    private PmsTrackAudioFeatures sampleFeatures(String spotifyTrackId) {
        return new PmsTrackAudioFeatures(
            spotifyTrackId,
            "spotify_api",
            true,
            "https://api.spotify.com/v1/audio-analysis/%s".formatted(spotifyTrackId),
            "https://api.spotify.com/v1/tracks/%s".formatted(spotifyTrackId),
            "spotify:track:%s".formatted(spotifyTrackId),
            "audio_features",
            218000,
            1,
            1,
            4,
            0.19,
            0.74,
            0.78,
            0.02,
            0.11,
            -7.8,
            0.05,
            116.2,
            0.67,
            Instant.parse("2026-05-04T00:00:00Z")
        );
    }
}
