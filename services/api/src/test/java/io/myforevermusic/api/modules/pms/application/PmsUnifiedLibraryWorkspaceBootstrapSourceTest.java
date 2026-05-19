package io.myforevermusic.api.modules.pms.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.myforevermusic.api.modules.pms.infrastructure.local.InMemoryPmsPersonalPlaylistStore;
import io.myforevermusic.api.modules.pms.infrastructure.local.InMemoryPmsPlaylistImportStore;
import io.myforevermusic.api.modules.pms.infrastructure.local.InMemoryPmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.presentation.PmsWorkspaceBootstrapResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PmsUnifiedLibraryWorkspaceBootstrapSourceTest {

    @Test
    void shouldMergePlatformLibraryAndGmsApprovedPlaylistsIntoMainShelf() {
        InMemoryPmsUserLibraryStore userLibraryStore = new InMemoryPmsUserLibraryStore();
        InMemoryPmsPlaylistImportStore importStore = new InMemoryPmsPlaylistImportStore();
        InMemoryPmsPersonalPlaylistStore personalStore = new InMemoryPmsPersonalPlaylistStore();

        userLibraryStore.savePlaylists("user-001", List.of(libraryPlaylist()));
        personalStore.createPlaylist(new PmsPersonalPlaylistStore.CreatePlaylistDraft(
            "user-001",
            "gms-ems-101",
            "GMS Approved Mix",
            "Imported from EMS via GMS"
        ));
        personalStore.addTrack(new PmsPersonalPlaylistStore.AddTrackDraft(
            "user-001",
            "gms-ems-101",
            personalTrack("ems-9001", "Approved Track", "spotify", 1),
            "gms-playlist-import"
        ));

        PmsUnifiedLibraryWorkspaceBootstrapSource source = new PmsUnifiedLibraryWorkspaceBootstrapSource(
            userLibraryStore,
            importStore,
            personalStore
        );

        PmsWorkspaceBootstrapResponse response = source.load("user-001", "gms-ems-101").orElseThrow();

        assertThat(response.playlists())
            .extracting(PmsWorkspaceBootstrapResponse.PlaylistOption::playlistId)
            .containsExactly("playlist-001", "gms-ems-101");
        assertThat(response.playlists())
            .extracting(PmsWorkspaceBootstrapResponse.PlaylistOption::sourceCollection)
            .containsExactly("pms-user-library", "pms-gms-approved-playlist");
        assertThat(response.workspaceDefaults().playlistId()).isEqualTo("gms-ems-101");
        assertThat(response.suggestedTracks())
            .extracting(PmsWorkspaceBootstrapResponse.TrackSeedSuggestion::trackId)
            .containsExactly("ems-9001");
    }

    @Test
    void shouldFallBackToImportedPlaylistsWhenUserLibraryIsEmpty() {
        InMemoryPmsUserLibraryStore userLibraryStore = new InMemoryPmsUserLibraryStore();
        InMemoryPmsPlaylistImportStore importStore = new InMemoryPmsPlaylistImportStore();
        InMemoryPmsPersonalPlaylistStore personalStore = new InMemoryPmsPersonalPlaylistStore();

        importStore.saveImportedPlaylists("user-001", List.of(importedPlaylist()));

        PmsUnifiedLibraryWorkspaceBootstrapSource source = new PmsUnifiedLibraryWorkspaceBootstrapSource(
            userLibraryStore,
            importStore,
            personalStore
        );

        PmsWorkspaceBootstrapResponse response = source.load("user-001", null).orElseThrow();

        assertThat(response.playlists()).hasSize(1);
        assertThat(response.playlists().getFirst().sourceCollection()).isEqualTo("pms-imported-playlist");
    }

    private PmsUserLibraryStore.LibraryPlaylistState libraryPlaylist() {
        return new PmsUserLibraryStore.LibraryPlaylistState(
            "user-001",
            "playlist-001",
            "spotify-playlist-001",
            "Library Import",
            "spotify",
            "Forever Listener",
            "Imported from the connected platform.",
            null,
            "https://open.spotify.com/playlist/spotify-playlist-001",
            "spotify:playlist:spotify-playlist-001",
            Instant.parse("2026-05-19T00:00:00Z"),
            List.of(
                new PmsUserLibraryStore.LibraryTrackState(
                    "track-001",
                    "spotify-track-001",
                    "Library Track",
                    "Library Artist",
                    "spotify",
                    "synth-pop",
                    "Library Album",
                    null,
                    "https://open.spotify.com/track/spotify-track-001",
                    "spotify:track:spotify-track-001",
                    null,
                    1,
                    true,
                    null
                )
            )
        );
    }

    private PmsPlaylistImportStore.ImportedPlaylistState importedPlaylist() {
        return new PmsPlaylistImportStore.ImportedPlaylistState(
            "user-001",
            "playlist-imported-001",
            "spotify-playlist-imported-001",
            "Imported Fallback",
            "spotify",
            "Platform User",
            "Imported before full PMS library sync.",
            null,
            "https://open.spotify.com/playlist/spotify-playlist-imported-001",
            "spotify:playlist:spotify-playlist-imported-001",
            Instant.parse("2026-05-19T00:00:00Z"),
            List.of(
                new PmsPlaylistImportStore.ImportedTrackState(
                    "track-imported-001",
                    "spotify-track-imported-001",
                    "Imported Track",
                    "Imported Artist",
                    "spotify",
                    "indie-pop",
                    "Imported Album",
                    null,
                    "https://open.spotify.com/track/spotify-track-imported-001",
                    "spotify:track:spotify-track-imported-001",
                    null,
                    1,
                    true,
                    null
                )
            )
        );
    }

    private PmsPersonalPlaylistStore.PersonalTrackState personalTrack(
        String trackId,
        String title,
        String sourcePlatform,
        int sortOrder
    ) {
        return new PmsPersonalPlaylistStore.PersonalTrackState(
            trackId,
            trackId,
            title,
            "Approved Artist",
            sourcePlatform,
            "Approved Album",
            null,
            null,
            "spotify:track:approved-track",
            null,
            null,
            "approved-track",
            "spotify:track:approved-track",
            null,
            null,
            "spotify",
            "native",
            180000,
            sortOrder,
            "gms-playlist-import",
            Instant.parse("2026-05-19T00:00:00Z")
        );
    }
}
