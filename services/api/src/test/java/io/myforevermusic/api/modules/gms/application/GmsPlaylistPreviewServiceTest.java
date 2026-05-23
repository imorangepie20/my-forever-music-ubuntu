package io.myforevermusic.api.modules.gms.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.modules.auth.application.AuthRegisteredAccount;
import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistTrackEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistTrackRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackEntity;
import io.myforevermusic.api.modules.pms.application.PmsPersonalPlaylistStore;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.infrastructure.local.InMemoryPmsPersonalPlaylistStore;
import io.myforevermusic.api.modules.recommendation.application.EventSignalWeights;
import io.myforevermusic.api.modules.recommendation.application.PlaylistQualityEvaluator;
import io.myforevermusic.api.modules.recommendation.application.RecommendationAuditLogStore;
import io.myforevermusic.api.modules.recommendation.infrastructure.local.InMemoryRecommendationAuditLogStore;
import io.myforevermusic.api.modules.recommendation.infrastructure.local.InMemoryUserMusicEventStore;
import io.myforevermusic.api.modules.recommendation.application.UserMusicEventService;
import io.myforevermusic.api.modules.recommendation.presentation.UserMusicEventRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

class GmsPlaylistPreviewServiceTest {

    @Test
    void shouldExcludeRemovedPreviewTracksWhenSavingToPms() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
        EmsCollectedPlaylistRepository playlistRepository = mock(EmsCollectedPlaylistRepository.class);
        EmsCollectedPlaylistTrackRepository playlistTrackRepository = mock(EmsCollectedPlaylistTrackRepository.class);
        PmsPersonalPlaylistStore personalPlaylistStore = new InMemoryPmsPersonalPlaylistStore();
        UserMusicEventService userMusicEventService = mock(UserMusicEventService.class);
        InMemoryUserMusicEventStore userMusicEventStore = new InMemoryUserMusicEventStore();
        PlaylistQualityEvaluator playlistQualityEvaluator = mock(PlaylistQualityEvaluator.class);
        InMemoryRecommendationAuditLogStore auditLogStore = new InMemoryRecommendationAuditLogStore();

        EmsCollectedPlaylistEntity playlist = playlist(1L);
        EmsCollectedTrackEntity firstTrack = track(10L, "Keep Me");
        EmsCollectedTrackEntity removedTrack = track(20L, "Remove Me");
        when(pmsUserLibraryStore.findPlaylists("user-001")).thenReturn(List.of(pmsLibraryPlaylist()));
        when(playlistRepository.findById(1L)).thenReturn(Optional.of(playlist));
        when(playlistTrackRepository.findByPlaylistIdOrderBySortOrderAsc(1L)).thenReturn(List.of(
            new EmsCollectedPlaylistTrackEntity(playlist, firstTrack, 1),
            new EmsCollectedPlaylistTrackEntity(playlist, removedTrack, 2)
        ));

        GmsPlaylistPreviewService service = new GmsPlaylistPreviewService(
            authAccountStore,
            pmsUserLibraryStore,
            playlistRepository,
            playlistTrackRepository,
            personalPlaylistStore,
            userMusicEventService,
            userMusicEventStore,
            playlistQualityEvaluator,
            auditLogStore,
            new GmsAxisEvidenceAuditSummaryService(new com.fasterxml.jackson.databind.ObjectMapper())
        );

        GmsPlaylistPreviewService.SaveResult result = service.saveToPms(
            "user-001",
            1L,
            null,
            List.of(20L)
        );

        assertThat(result.addedTrackCount()).isEqualTo(1);
        PmsPersonalPlaylistStore.PersonalPlaylistState saved = personalPlaylistStore
            .findPlaylist("user-001", "gms-ems-1")
            .orElseThrow();
        assertThat(saved.tracks()).extracting(PmsPersonalPlaylistStore.PersonalTrackState::trackId)
            .containsExactly("ems-10");

        ArgumentCaptor<UserMusicEventRequest> eventCaptor = ArgumentCaptor.forClass(UserMusicEventRequest.class);
        verify(userMusicEventService).recordEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().trackId()).isEqualTo("ems-10");
    }

    @Test
    void shouldDismissPlaylistAndFilterItFromPreview() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
        EmsCollectedPlaylistRepository playlistRepository = mock(EmsCollectedPlaylistRepository.class);
        EmsCollectedPlaylistTrackRepository playlistTrackRepository = mock(EmsCollectedPlaylistTrackRepository.class);
        PmsPersonalPlaylistStore personalPlaylistStore = new InMemoryPmsPersonalPlaylistStore();
        InMemoryUserMusicEventStore userMusicEventStore = new InMemoryUserMusicEventStore();
        UserMusicEventService userMusicEventService = new UserMusicEventService(
            userMusicEventStore,
            new EventSignalWeights()
        );
        PlaylistQualityEvaluator playlistQualityEvaluator = mock(PlaylistQualityEvaluator.class);
        InMemoryRecommendationAuditLogStore auditLogStore = new InMemoryRecommendationAuditLogStore();

        EmsCollectedPlaylistEntity playlist = playlist(1L);
        EmsCollectedTrackEntity track = track(10L, "Candidate Track");
        when(authAccountStore.findByUserId("user-001")).thenReturn(Optional.empty());
        when(pmsUserLibraryStore.findPlaylists("user-001")).thenReturn(List.of(pmsLibraryPlaylist()));
        when(playlistRepository.findById(1L)).thenReturn(Optional.of(playlist));
        when(playlistRepository.findRecentWithTracks(PageRequest.of(0, 36))).thenReturn(List.of(playlist));
        when(playlistTrackRepository.findByPlaylistIdOrderBySortOrderAsc(1L)).thenReturn(List.of(
            new EmsCollectedPlaylistTrackEntity(playlist, track, 1)
        ));

        GmsPlaylistPreviewService service = new GmsPlaylistPreviewService(
            authAccountStore,
            pmsUserLibraryStore,
            playlistRepository,
            playlistTrackRepository,
            personalPlaylistStore,
            userMusicEventService,
            userMusicEventStore,
            playlistQualityEvaluator,
            auditLogStore,
            new GmsAxisEvidenceAuditSummaryService(new com.fasterxml.jackson.databind.ObjectMapper())
        );

        assertThat(service.preview("user-001", 12).candidates()).hasSize(1);

        GmsPlaylistPreviewService.DismissResult dismissed = service.dismissFromGms("user-001", 1L);

        assertThat(dismissed.emsPlaylistId()).isEqualTo(1L);
        assertThat(service.preview("user-001", 12).candidates()).isEmpty();
        assertThat(userMusicEventStore.findRecentByUserId("user-001", 10))
            .anySatisfy(event -> {
                assertThat(event.eventType()).isEqualTo("ignored_recommendation");
                assertThat(event.playlistId()).isEqualTo("ems-1");
                assertThat(event.itemKind()).isEqualTo("playlist");
            });
    }

    @Test
    void shouldFilterAlreadySavedPlaylistFromPreview() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
        EmsCollectedPlaylistRepository playlistRepository = mock(EmsCollectedPlaylistRepository.class);
        EmsCollectedPlaylistTrackRepository playlistTrackRepository = mock(EmsCollectedPlaylistTrackRepository.class);
        PmsPersonalPlaylistStore personalPlaylistStore = new InMemoryPmsPersonalPlaylistStore();
        InMemoryUserMusicEventStore userMusicEventStore = new InMemoryUserMusicEventStore();
        UserMusicEventService userMusicEventService = new UserMusicEventService(
            userMusicEventStore,
            new EventSignalWeights()
        );
        PlaylistQualityEvaluator playlistQualityEvaluator = mock(PlaylistQualityEvaluator.class);
        InMemoryRecommendationAuditLogStore auditLogStore = new InMemoryRecommendationAuditLogStore();

        EmsCollectedPlaylistEntity playlist = playlist(1L);
        personalPlaylistStore.createPlaylist(new PmsPersonalPlaylistStore.CreatePlaylistDraft(
            "user-001",
            "gms-ems-1",
            "EMS Playlist (GMS)",
            "saved"
        ));
        when(authAccountStore.findByUserId("user-001")).thenReturn(Optional.empty());
        when(pmsUserLibraryStore.findPlaylists("user-001")).thenReturn(List.of(pmsLibraryPlaylist()));
        when(playlistRepository.findRecentWithTracks(PageRequest.of(0, 36))).thenReturn(List.of(playlist));

        GmsPlaylistPreviewService service = new GmsPlaylistPreviewService(
            authAccountStore,
            pmsUserLibraryStore,
            playlistRepository,
            playlistTrackRepository,
            personalPlaylistStore,
            userMusicEventService,
            userMusicEventStore,
            playlistQualityEvaluator,
            auditLogStore,
            new GmsAxisEvidenceAuditSummaryService(new com.fasterxml.jackson.databind.ObjectMapper())
        );

        assertThat(service.preview("user-001", 12).candidates()).isEmpty();
    }

    @Test
    void shouldIncludeExplicitImportedPlaylistEvenWhenPreferredPlatformDiffers() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
        EmsCollectedPlaylistRepository playlistRepository = mock(EmsCollectedPlaylistRepository.class);
        EmsCollectedPlaylistTrackRepository playlistTrackRepository = mock(EmsCollectedPlaylistTrackRepository.class);
        PmsPersonalPlaylistStore personalPlaylistStore = new InMemoryPmsPersonalPlaylistStore();
        InMemoryUserMusicEventStore userMusicEventStore = new InMemoryUserMusicEventStore();
        UserMusicEventService userMusicEventService = new UserMusicEventService(
            userMusicEventStore,
            new EventSignalWeights()
        );
        PlaylistQualityEvaluator playlistQualityEvaluator = mock(PlaylistQualityEvaluator.class);
        InMemoryRecommendationAuditLogStore auditLogStore = new InMemoryRecommendationAuditLogStore();

        EmsCollectedPlaylistEntity spotifyPlaylist = playlist(1L, "spotify", "acquisition_pool");
        EmsCollectedPlaylistEntity tidalPlaylist = playlist(2L, "tidal", "user_tidal_url_import");
        EmsCollectedTrackEntity track = track(20L, "Imported TIDAL Track");
        when(authAccountStore.findByUserId("user-001")).thenReturn(Optional.of(account("spotify")));
        when(pmsUserLibraryStore.findPlaylists("user-001")).thenReturn(List.of(pmsLibraryPlaylist()));
        when(playlistRepository.findRecentWithTracksBySourcePlatforms(
            List.of("spotify"),
            PageRequest.of(0, 36)
        )).thenReturn(List.of(spotifyPlaylist));
        when(playlistRepository.findById(2L)).thenReturn(Optional.of(tidalPlaylist));
        when(playlistTrackRepository.findByPlaylistIdOrderBySortOrderAsc(1L)).thenReturn(List.of(
            new EmsCollectedPlaylistTrackEntity(spotifyPlaylist, track(10L, "Spotify Track"), 1)
        ));
        when(playlistTrackRepository.findByPlaylistIdOrderBySortOrderAsc(2L)).thenReturn(List.of(
            new EmsCollectedPlaylistTrackEntity(tidalPlaylist, track, 1)
        ));

        GmsPlaylistPreviewService service = new GmsPlaylistPreviewService(
            authAccountStore,
            pmsUserLibraryStore,
            playlistRepository,
            playlistTrackRepository,
            personalPlaylistStore,
            userMusicEventService,
            userMusicEventStore,
            playlistQualityEvaluator,
            auditLogStore,
            new GmsAxisEvidenceAuditSummaryService(new com.fasterxml.jackson.databind.ObjectMapper())
        );

        assertThat(service.preview("user-001", 12, 2L).candidates())
            .extracting(GmsPlaylistPreviewService.GmsPlaylistPreviewCandidate::playlistId)
            .contains(2L);
        RecommendationAuditLogStore.StoredAuditLog audit =
            auditLogStore.findRecentByUserId("user-001", 1).getFirst();

        assertThat(audit.sourceSpace()).isEqualTo("gms-playlists");
        assertThat(audit.axisEvidenceSummary())
            .contains("\"source\":\"gms-playlists\"")
            .contains("\"playlists\"")
            .contains("\"playlist_id\":")
            .contains("\"axis_evidence\"");
    }

    private static EmsCollectedPlaylistEntity playlist(Long id) {
        return playlist(id, "spotify", "acquisition_pool");
    }

    private static EmsCollectedPlaylistEntity playlist(Long id, String sourcePlatform, String collectionSource) {
        EmsCollectedPlaylistEntity playlist = new EmsCollectedPlaylistEntity(
            "playlist-001",
            "EMS Playlist",
            sourcePlatform,
            "curator",
            "description",
            null,
            null,
            null,
            2,
            collectionSource,
            null,
            Instant.parse("2026-05-15T00:00:00Z")
        );
        ReflectionTestUtils.setField(playlist, "id", id);
        return playlist;
    }

    private static EmsCollectedTrackEntity track(Long id, String title) {
        EmsCollectedTrackEntity track = new EmsCollectedTrackEntity(
            "track-%d".formatted(id),
            title,
            "Artist",
            "spotify",
            null,
            "Album",
            null,
            null,
            "spotify:track:%d".formatted(id),
            null,
            180_000,
            "acquisition_pool",
            Instant.parse("2026-05-15T00:00:00Z"),
            null
        );
        ReflectionTestUtils.setField(track, "id", id);
        return track;
    }

    private static PmsUserLibraryStore.LibraryPlaylistState pmsLibraryPlaylist() {
        return new PmsUserLibraryStore.LibraryPlaylistState(
            "user-001",
            "library-001",
            "external-library-001",
            "Imported Library",
            "spotify",
            "curator",
            null,
            null,
            null,
            null,
            Instant.parse("2026-05-15T00:00:00Z"),
            List.of(new PmsUserLibraryStore.LibraryTrackState(
                "library-track-001",
                "external-track-001",
                "Library Track",
                "Artist",
                "spotify",
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                false,
                null
            ))
        );
    }

    private static AuthRegisteredAccount account(String preferredPlatformId) {
        return new AuthRegisteredAccount(
            "user-001",
            "user@example.com",
            "user@example.com",
            "User",
            preferredPlatformId,
            null,
            null,
            false,
            "registered",
            Instant.parse("2026-05-09T00:00:00Z"),
            Instant.parse("2026-05-09T00:00:00Z"),
            Instant.parse("2026-05-09T00:00:00Z")
        );
    }
}
