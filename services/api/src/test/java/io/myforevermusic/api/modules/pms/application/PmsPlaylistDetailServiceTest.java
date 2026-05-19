package io.myforevermusic.api.modules.pms.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.myforevermusic.api.modules.pms.infrastructure.local.InMemoryPmsPersonalPlaylistStore;
import io.myforevermusic.api.modules.pms.infrastructure.local.InMemoryPmsPlaylistImportStore;
import io.myforevermusic.api.modules.pms.infrastructure.local.InMemoryPmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.presentation.PmsPlaylistDetailResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PmsPlaylistDetailServiceTest {

    @Test
    void shouldLabelGmsApprovedPlaylistsAsDedicatedSourceCollection() {
        InMemoryPmsPersonalPlaylistStore personalStore = new InMemoryPmsPersonalPlaylistStore();
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

        PmsPlaylistDetailService service = new PmsPlaylistDetailService(
            new InMemoryPmsUserLibraryStore(),
            new InMemoryPmsPlaylistImportStore(),
            personalStore
        );

        PmsPlaylistDetailResponse response = service.getPlaylistDetail("user-001", "gms-ems-101");

        assertThat(response.sourceCollection()).isEqualTo("pms-gms-approved-playlist");
        assertThat(response.playlist().curator()).isEqualTo("gms approved");
        assertThat(response.playlist().sourcePlatform()).isEqualTo("spotify");
    }

    @Test
    void shouldKeepSavedFromGmsAsPersonalPlaylist() {
        InMemoryPmsPersonalPlaylistStore personalStore = new InMemoryPmsPersonalPlaylistStore();
        personalStore.createPlaylist(new PmsPersonalPlaylistStore.CreatePlaylistDraft(
            "user-001",
            "personal-saved-gms-recommendations",
            "Saved from GMS",
            "Auto-saved track bucket"
        ));

        PmsPlaylistDetailService service = new PmsPlaylistDetailService(
            new InMemoryPmsUserLibraryStore(),
            new InMemoryPmsPlaylistImportStore(),
            personalStore
        );

        PmsPlaylistDetailResponse response = service.getPlaylistDetail(
            "user-001",
            "personal-saved-gms-recommendations"
        );

        assertThat(response.sourceCollection()).isEqualTo("pms-personal-playlist");
        assertThat(response.playlist().curator()).isEqualTo("personal playlist");
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
