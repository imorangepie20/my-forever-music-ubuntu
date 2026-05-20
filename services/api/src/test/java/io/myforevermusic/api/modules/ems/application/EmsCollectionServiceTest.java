package io.myforevermusic.api.modules.ems.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistTrackEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistTrackRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsPoolEntryEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsPoolEntryRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsPoolIngestRunEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsPoolIngestRunRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsTrackAudioFeatures;
import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import io.myforevermusic.api.modules.auth.application.AuthRegisteredAccount;
import io.myforevermusic.api.modules.platform.application.PlatformAccountCredential;
import io.myforevermusic.api.modules.platform.application.PlatformCredentialService;
import io.myforevermusic.api.modules.platform.infrastructure.reccobeats.ReccoBeatsAudioFeaturesClient;
import io.myforevermusic.api.modules.platform.infrastructure.reccobeats.ReccoBeatsAudioFeaturesClient.ReccoBeatsAudioFeaturesSnapshot;
import io.myforevermusic.api.modules.platform.infrastructure.reccobeats.ReccoBeatsAudioFeaturesClient.ReccoBeatsTrackLookupRequest;
import io.myforevermusic.api.modules.platform.infrastructure.spotify.SpotifyWebApiClient;
import io.myforevermusic.api.modules.platform.infrastructure.spotify.SpotifyWebApiClient.SpotifyPlaylistSummary;
import io.myforevermusic.api.modules.platform.infrastructure.spotify.SpotifyWebApiClient.SpotifyPlaylistTrack;
import io.myforevermusic.api.modules.platform.infrastructure.spotify.SpotifyWebApiClient.SpotifySearchResult;
import io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient;
import io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalSearchResult;
import io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionAutoEnqueueService;
import io.myforevermusic.api.modules.recommendation.infrastructure.local.InMemoryAudioFeatureCompletionJobStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EmsCollectionServiceTest {

    @Mock
    private SpotifyWebApiClient spotifyWebApiClient;

    @Mock
    private TidalWebApiClient tidalWebApiClient;

    @Mock
    private ReccoBeatsAudioFeaturesClient reccoBeatsAudioFeaturesClient;

    @Mock
    private PlatformCredentialService platformCredentialService;

    @Mock
    private AuthAccountStore authAccountStore;

    @Mock
    private EmsCollectedPlaylistRepository playlistRepository;

    @Mock
    private EmsCollectedTrackRepository trackRepository;

    @Mock
    private EmsCollectedPlaylistTrackRepository playlistTrackRepository;

    @Mock
    private EmsPoolIngestRunRepository poolRunRepository;

    @Mock
    private EmsPoolEntryRepository poolEntryRepository;

    @Mock
    private FloSpecialCurationService floSpecialCurationService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    void shouldQueueSpotifySearchResultsInEmsPool() {
        PlatformAccountCredential credential = credential("spotify");
        when(platformCredentialService.findUsableCredential("user-001", "spotify"))
            .thenReturn(Optional.of(credential));
        when(spotifyWebApiClient.searchPlaylists(credential, "vocal jazz"))
            .thenReturn(new SpotifySearchResult<>(List.of(
                new SpotifyPlaylistSummary(
                    "playlist-001",
                    "Vocal Jazz",
                    "Stored only after explicit collection",
                    "owner-001",
                    "Curator",
                    false,
                    12,
                    null,
                    "https://open.spotify.com/playlist/playlist-001",
                    "spotify:playlist:playlist-001"
                )
            ), 1));
        when(spotifyWebApiClient.searchTracks(credential, "vocal jazz"))
            .thenReturn(new SpotifySearchResult<>(List.of(
                new SpotifyPlaylistTrack(
                    "track-001",
                    "Search Preview Track",
                    "Preview Artist",
                    "Preview Album",
                    null,
                    null,
                    "https://open.spotify.com/track/track-001",
                    "spotify:track:track-001",
                    null,
                    null,
                    180000
                )
            ), 1));
        EmsPoolIngestRunEntity run = poolRun("user-001", "spotify", "vocal jazz", 1, 1);
        when(poolRunRepository.save(any(EmsPoolIngestRunEntity.class))).thenReturn(run);

        EmsCollectionService service = service();
        EmsCollectionService.EmsCollectionSearchPreviewResult result =
            service.previewSearch("user-001", "spotify", "vocal jazz");

        assertThat(result.poolRunId()).isEqualTo(55L);
        assertThat(result.resultPlaylistCount()).isEqualTo(1);
        assertThat(result.resultTrackCount()).isEqualTo(1);
        assertThat(result.playlists()).extracting(EmsCollectionService.EmsCollectionSearchPlaylistPreview::title)
            .containsExactly("Vocal Jazz");
        assertThat(result.tracks()).extracting(EmsCollectionService.EmsCollectionSearchTrackPreview::title)
            .containsExactly("Search Preview Track");
        ArgumentCaptor<Iterable<EmsPoolEntryEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(poolEntryRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        verify(eventPublisher).publishEvent(new EmsPoolRunQueuedEvent(55L));
        verifyNoInteractions(playlistRepository, trackRepository, playlistTrackRepository, reccoBeatsAudioFeaturesClient);
    }

    @Test
    void shouldQueuePreferredProviderSearchResultsWithoutCrossProviderLookup() {
        PlatformAccountCredential tidalCredential = credential("tidal");
        when(authAccountStore.findByUserId("user-001"))
            .thenReturn(Optional.of(account("tidal")));
        when(platformCredentialService.findUsableCredential("user-001", "tidal"))
            .thenReturn(Optional.of(tidalCredential));
        when(tidalWebApiClient.searchPlaylistResults(tidalCredential, "jazz"))
            .thenReturn(new TidalSearchResult<>(List.of(new io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistSummary(
                "tidal-playlist-001",
                "TIDAL Jazz",
                "TIDAL preview",
                20,
                null,
                null,
                "https://tidal.com/browse/playlist/tidal-playlist-001",
                "tidal-playlist-001"
            )), 7));
        when(tidalWebApiClient.searchTrackResults(tidalCredential, "jazz"))
            .thenReturn(new TidalSearchResult<>(List.of(new io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistTrack(
                "tidal-track-001",
                "TIDAL Track",
                "TIDAL Artist",
                "TIDAL Album",
                null,
                "https://tidal.com/browse/track/tidal-track-001",
                "tidal:track:tidal-track-001",
                null,
                "USRC17607839",
                180000
            )), 99));
        when(poolRunRepository.save(any(EmsPoolIngestRunEntity.class)))
            .thenReturn(poolRun("user-001", "tidal", "jazz", 1, 1));

        EmsCollectionService.EmsCollectionSearchPreviewResult result =
            service().previewSearch("user-001", null, "jazz");

        assertThat(result.poolRunId()).isEqualTo(55L);
        assertThat(result.platformId()).isEqualTo("tidal");
        assertThat(result.resultPlaylistCount()).isEqualTo(7);
        assertThat(result.resultTrackCount()).isEqualTo(99);
        assertThat(result.playlists()).extracting(EmsCollectionService.EmsCollectionSearchPlaylistPreview::sourcePlatform)
            .containsExactly("tidal");
        assertThat(result.tracks()).extracting(EmsCollectionService.EmsCollectionSearchTrackPreview::sourcePlatform)
            .containsExactly("tidal");
        verifyNoInteractions(spotifyWebApiClient);
        verify(poolEntryRepository).saveAll(any());
        verifyNoInteractions(playlistRepository, trackRepository, playlistTrackRepository, reccoBeatsAudioFeaturesClient);
    }

    @Test
    void shouldLinkSearchPlaylistTracksToStoredSearchPoolPlaylist() {
        PlatformAccountCredential credential = credential("tidal");
        EmsCollectedPlaylistEntity playlistEntity = collectedPlaylist(
            "tidal-playlist-001",
            "tidal",
            "Stored search playlist",
            1
        );
        ReflectionTestUtils.setField(playlistEntity, "id", 7L);
        EmsCollectedTrackEntity trackEntity = collectedTrack(
            "tidal-track-001",
            "tidal",
            "TIDAL Track",
            "TIDAL Artist",
            "USRC17607839"
        );
        ReflectionTestUtils.setField(trackEntity, "id", 8L);

        when(platformCredentialService.findUsableCredential("user-001", "tidal"))
            .thenReturn(Optional.of(credential));
        when(tidalWebApiClient.getPlaylistTracks(credential, "tidal-playlist-001"))
            .thenReturn(List.of(new io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistTrack(
                "tidal-track-001",
                "TIDAL Track",
                "TIDAL Artist",
                "TIDAL Album",
                null,
                "https://tidal.com/browse/track/tidal-track-001",
                "tidal:track:tidal-track-001",
                null,
                "USRC17607839",
                180000
            )));
        when(playlistRepository.findBySourcePlatformAndExternalPlaylistId("tidal", "tidal-playlist-001"))
            .thenReturn(Optional.of(playlistEntity));
        when(trackRepository.findBySourcePlatformAndExternalTrackId("tidal", "tidal-track-001"))
            .thenReturn(Optional.of(trackEntity));

        EmsCollectionService.EmsCollectionSearchPlaylistTracksPreview result =
            service().getSearchPlaylistTracks("user-001", "tidal", "tidal-playlist-001");

        assertThat(result.trackCount()).isEqualTo(1);
        assertThat(result.tracks()).extracting(EmsCollectionService.EmsCollectionSearchTrackPreview::externalTrackId)
            .containsExactly("tidal-track-001");
        verify(playlistTrackRepository).upsertPlaylistTrackLink(7L, 8L, 0);
        verifyNoInteractions(reccoBeatsAudioFeaturesClient);
    }

    @Test
    void shouldCollectTidalPlaylistUrlImportIntoEms() {
        PlatformAccountCredential credential = credential("tidal");
        io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistSummary playlist =
            new io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistSummary(
                "0a3d87d2-27dc-4edc-84b6-9f1eaa567f33",
                "Night Drive Imports",
                "Public TIDAL playlist",
                1,
                null,
                null,
                "https://tidal.com/playlist/0a3d87d2-27dc-4edc-84b6-9f1eaa567f33",
                "0a3d87d2-27dc-4edc-84b6-9f1eaa567f33"
            );
        io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistTrack track =
            new io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistTrack(
                "tidal-track-001",
                "Imported Track",
                "Imported Artist",
                "Imported Album",
                "https://resources.tidal.com/images/c6459799/cabd/4177/a42a/2b6c7432ee53/750x750.jpg",
                "https://tidal.com/browse/track/tidal-track-001",
                "tidal:track:tidal-track-001",
                null,
                "USRC17607839",
                180000
            );
        EmsCollectedPlaylistEntity savedPlaylist = collectedPlaylist(
            "0a3d87d2-27dc-4edc-84b6-9f1eaa567f33",
            "tidal",
            "Night Drive Imports",
            1
        );
        ReflectionTestUtils.setField(savedPlaylist, "id", 70L);
        EmsCollectedTrackEntity savedTrack = collectedTrack(
            "tidal-track-001",
            "tidal",
            "Imported Track",
            "Imported Artist",
            "USRC17607839"
        );
        ReflectionTestUtils.setField(savedTrack, "id", 80L);

        when(platformCredentialService.findUsableCredential("user-001", "tidal"))
            .thenReturn(Optional.of(credential));
        when(tidalWebApiClient.getPlaylist(credential, "0a3d87d2-27dc-4edc-84b6-9f1eaa567f33"))
            .thenReturn(playlist);
        when(tidalWebApiClient.getPlaylistTracks(credential, "0a3d87d2-27dc-4edc-84b6-9f1eaa567f33"))
            .thenReturn(List.of(track));
        when(reccoBeatsAudioFeaturesClient.getAudioFeaturesForExternalTracksByIsrc(any()))
            .thenReturn(Map.of());
        when(playlistRepository.findBySourcePlatformAndExternalPlaylistId("tidal", playlist.playlistId()))
            .thenReturn(Optional.empty());
        when(playlistRepository.save(any(EmsCollectedPlaylistEntity.class))).thenReturn(savedPlaylist);
        when(trackRepository.findBySourcePlatformAndExternalTrackId("tidal", "tidal-track-001"))
            .thenReturn(Optional.of(savedTrack));

        EmsCollectionService.EmsTidalPlaylistUrlImportCollection result =
            service().collectTidalPlaylistFromUrlImport("user-001", playlist.playlistId());

        assertThat(result.emsPlaylistId()).isEqualTo(70L);
        assertThat(result.externalPlaylistId()).isEqualTo(playlist.playlistId());
        assertThat(result.title()).isEqualTo("Night Drive Imports");
        assertThat(result.trackCount()).isEqualTo(1);
        assertThat(result.collectionSource()).isEqualTo("user_tidal_url_import");
        verify(playlistTrackRepository).upsertPlaylistTrackLink(70L, 80L, 0);
        ArgumentCaptor<EmsCollectedPlaylistEntity> playlistCaptor =
            ArgumentCaptor.forClass(EmsCollectedPlaylistEntity.class);
        verify(playlistRepository).save(playlistCaptor.capture());
        assertThat(playlistCaptor.getValue().getCoverImageUrl())
            .isEqualTo("https://resources.tidal.com/images/c6459799/cabd/4177/a42a/2b6c7432ee53/750x750.jpg");
    }

    @Test
    void shouldEnqueueMissingAudioFeaturesAfterEmsTrackCollection() {
        PlatformAccountCredential credential = credential("tidal");
        io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistSummary playlist =
            new io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistSummary(
                "0a3d87d2-27dc-4edc-84b6-9f1eaa567f33",
                "Night Drive Imports",
                "Public TIDAL playlist",
                1,
                null,
                null,
                "https://tidal.com/playlist/0a3d87d2-27dc-4edc-84b6-9f1eaa567f33",
                "0a3d87d2-27dc-4edc-84b6-9f1eaa567f33"
            );
        io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistTrack track =
            new io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistTrack(
                "tidal-track-001",
                "Imported Track",
                "Imported Artist",
                "Imported Album",
                null,
                "https://tidal.com/browse/track/tidal-track-001",
                "tidal:track:tidal-track-001",
                null,
                "USRC17607839",
                180000
            );
        EmsCollectedPlaylistEntity savedPlaylist = collectedPlaylist(
            "0a3d87d2-27dc-4edc-84b6-9f1eaa567f33",
            "tidal",
            "Night Drive Imports",
            1
        );
        ReflectionTestUtils.setField(savedPlaylist, "id", 70L);
        EmsCollectedTrackEntity savedTrack = collectedTrack(
            "tidal-track-001",
            "tidal",
            "Imported Track",
            "Imported Artist",
            "USRC17607839"
        );
        ReflectionTestUtils.setField(savedTrack, "id", 80L);
        InMemoryAudioFeatureCompletionJobStore jobStore = new InMemoryAudioFeatureCompletionJobStore();
        AudioFeatureCompletionAutoEnqueueService autoEnqueueService =
            new AudioFeatureCompletionAutoEnqueueService(jobStore);

        when(platformCredentialService.findUsableCredential("user-001", "tidal"))
            .thenReturn(Optional.of(credential));
        when(tidalWebApiClient.getPlaylist(credential, playlist.playlistId()))
            .thenReturn(playlist);
        when(tidalWebApiClient.getPlaylistTracks(credential, playlist.playlistId()))
            .thenReturn(List.of(track));
        when(reccoBeatsAudioFeaturesClient.getAudioFeaturesForExternalTracksByIsrc(any()))
            .thenReturn(Map.of());
        when(playlistRepository.findBySourcePlatformAndExternalPlaylistId("tidal", playlist.playlistId()))
            .thenReturn(Optional.empty());
        when(playlistRepository.save(any(EmsCollectedPlaylistEntity.class))).thenReturn(savedPlaylist);
        when(trackRepository.findBySourcePlatformAndExternalTrackId("tidal", "tidal-track-001"))
            .thenReturn(Optional.of(savedTrack));

        service(Optional.of(autoEnqueueService))
            .collectTidalPlaylistFromUrlImport("user-001", playlist.playlistId());

        assertThat(jobStore.findRecent(null, 10))
            .extracting("trackScope", "trackId", "requestedReason", "status")
            .containsExactly(org.assertj.core.groups.Tuple.tuple(
                "ems_collected_track",
                "80",
                "ems_collect",
                "queued"
            ));
    }

    @Test
    void shouldReplaceTidalPlaylistUrlImportLinksOnReimport() {
        PlatformAccountCredential credential = credential("tidal");
        io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistSummary playlist =
            new io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistSummary(
                "0a3d87d2-27dc-4edc-84b6-9f1eaa567f33",
                "Night Drive Imports",
                "Updated public TIDAL playlist",
                1,
                null,
                null,
                "https://tidal.com/playlist/0a3d87d2-27dc-4edc-84b6-9f1eaa567f33",
                "0a3d87d2-27dc-4edc-84b6-9f1eaa567f33"
            );
        io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistTrack track =
            new io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistTrack(
                "tidal-track-002",
                "Updated Track",
                "Updated Artist",
                "Updated Album",
                null,
                "https://tidal.com/browse/track/tidal-track-002",
                "tidal:track:tidal-track-002",
                null,
                "USRC17607840",
                181000
            );
        EmsCollectedPlaylistEntity existingPlaylist = collectedPlaylist(
            "0a3d87d2-27dc-4edc-84b6-9f1eaa567f33",
            "tidal",
            "Night Drive Imports",
            3
        );
        ReflectionTestUtils.setField(existingPlaylist, "id", 70L);
        EmsCollectedTrackEntity savedTrack = collectedTrack(
            "tidal-track-002",
            "tidal",
            "Updated Track",
            "Updated Artist",
            "USRC17607840"
        );
        ReflectionTestUtils.setField(savedTrack, "id", 81L);

        when(platformCredentialService.findUsableCredential("user-001", "tidal"))
            .thenReturn(Optional.of(credential));
        when(tidalWebApiClient.getPlaylist(credential, playlist.playlistId())).thenReturn(playlist);
        when(tidalWebApiClient.getPlaylistTracks(credential, playlist.playlistId()))
            .thenReturn(List.of(track));
        when(reccoBeatsAudioFeaturesClient.getAudioFeaturesForExternalTracksByIsrc(any()))
            .thenReturn(Map.of());
        when(playlistRepository.findBySourcePlatformAndExternalPlaylistId("tidal", playlist.playlistId()))
            .thenReturn(Optional.of(existingPlaylist));
        when(trackRepository.findBySourcePlatformAndExternalTrackId("tidal", "tidal-track-002"))
            .thenReturn(Optional.of(savedTrack));

        EmsCollectionService.EmsTidalPlaylistUrlImportCollection result =
            service().collectTidalPlaylistFromUrlImport("user-001", playlist.playlistId());

        assertThat(result.emsPlaylistId()).isEqualTo(70L);
        assertThat(result.trackCount()).isEqualTo(1);
        InOrder inOrder = inOrder(playlistTrackRepository);
        inOrder.verify(playlistTrackRepository).deleteByPlaylistId(70L);
        inOrder.verify(playlistTrackRepository).upsertPlaylistTrackLink(70L, 81L, 0);
    }

    @Test
    void shouldRejectTidalPlaylistUrlImportWhenPlaylistHasNoTracks() {
        PlatformAccountCredential credential = credential("tidal");
        io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistSummary playlist =
            new io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistSummary(
                "empty-playlist",
                "Empty Playlist",
                "",
                0,
                null,
                null,
                "https://tidal.com/playlist/empty-playlist",
                "empty-playlist"
            );

        when(platformCredentialService.findUsableCredential("user-001", "tidal"))
            .thenReturn(Optional.of(credential));
        when(tidalWebApiClient.getPlaylist(credential, "empty-playlist")).thenReturn(playlist);
        when(tidalWebApiClient.getPlaylistTracks(credential, "empty-playlist")).thenReturn(List.of());

        assertThatThrownBy(() -> service().collectTidalPlaylistFromUrlImport("user-001", "empty-playlist"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not contain importable tracks");
    }

    @Test
    void shouldCollectFloChannelTopicAsPlaylistWithTracks() {
        FloSpecialCurationService.FloSpecialPlaylist channel =
            new FloSpecialCurationService.FloSpecialPlaylist(
                "56903",
                "NOW THAT 해외 록/메탈",
                "CHNL",
                "https://cdn.music-flo.com/channel.jpg",
                List.of("https://cdn.music-flo.com/channel.jpg"),
                "https://www.music-flo.com/detail/channel/56903"
            );
        FloSpecialCurationService.FloSpecialSection section =
            new FloSpecialCurationService.FloSpecialSection(
                "CURATION3",
                "11781",
                "놓치면 아쉬운 주간 하이라이트",
                List.of(channel)
            );
        FloSpecialCurationService.FloSpecialTrack track =
            new FloSpecialCurationService.FloSpecialTrack(
                "588352795",
                "Can’t Miss You",
                "Sublime",
                "Can’t Miss You",
                "https://cdn.music-flo.com/album.jpg",
                "https://www.music-flo.com/detail/track/588352795",
                152000,
                "20260515"
            );
        EmsCollectedTrackEntity trackEntity = collectedTrack("588352795", "flo", "Can’t Miss You", "Sublime", null);
        ReflectionTestUtils.setField(trackEntity, "id", 18L);

        when(floSpecialCurationService.getSpecial(null))
            .thenReturn(new FloSpecialCurationService.FloSpecialCuration(List.of(section)));
        when(floSpecialCurationService.getTracks(channel))
            .thenReturn(new FloSpecialCurationService.FloSpecialPlaylistTracks("56903", 1, List.of(track)));
        when(playlistRepository.findBySourcePlatformAndExternalPlaylistId("flo", "56903"))
            .thenReturn(Optional.empty());
        when(playlistRepository.save(any(EmsCollectedPlaylistEntity.class))).thenAnswer(invocation -> {
            EmsCollectedPlaylistEntity playlist = invocation.getArgument(0);
            ReflectionTestUtils.setField(playlist, "id", 17L);
            return playlist;
        });
        when(trackRepository.findBySourcePlatformAndExternalTrackId("flo", "588352795"))
            .thenReturn(Optional.of(trackEntity));

        EmsCollectionService.FloSpecialCollectionResult result = service().collectFloSpecial();

        assertThat(result.sectionCount()).isEqualTo(1);
        assertThat(result.collectedPlaylistCount()).isEqualTo(1);
        assertThat(result.collectedTrackCount()).isEqualTo(1);
        assertThat(result.failures()).isEmpty();
        ArgumentCaptor<EmsCollectedPlaylistEntity> playlistCaptor =
            ArgumentCaptor.forClass(EmsCollectedPlaylistEntity.class);
        verify(playlistRepository).save(playlistCaptor.capture());
        assertThat(playlistCaptor.getValue().getCurator()).isEqualTo("FLO Channel");
        assertThat(playlistCaptor.getValue().getSearchQuery()).isEqualTo("놓치면 아쉬운 주간 하이라이트");
        verify(playlistTrackRepository).deleteByPlaylistId(17L);
        verify(playlistTrackRepository).upsertPlaylistTrackLink(17L, 18L, 0);
    }

    @Test
    void shouldMaterializeMelonHot100AsStoredEmsPlaylist() {
        EmsCollectionService.MelonHot100TrackSeed seed = new EmsCollectionService.MelonHot100TrackSeed(
            1,
            "424991128",
            "Riding",
            "하성운",
            "Riding",
            "https://cdnimg.melon.co.kr/album.jpg",
            "https://www.melon.com/song/detail.htm?songId=424991128",
            Instant.parse("2026-05-17T00:00:00Z")
        );
        EmsCollectedTrackEntity trackEntity = collectedTrack("424991128", "melon", "Riding", "하성운", null);
        ReflectionTestUtils.setField(trackEntity, "id", 28L);

        when(playlistRepository.findBySourcePlatformAndExternalPlaylistId("melon", "melon-hot-100"))
            .thenReturn(Optional.empty());
        when(playlistRepository.save(any(EmsCollectedPlaylistEntity.class))).thenAnswer(invocation -> {
            EmsCollectedPlaylistEntity playlist = invocation.getArgument(0);
            ReflectionTestUtils.setField(playlist, "id", 27L);
            return playlist;
        });
        when(trackRepository.findBySourcePlatformAndExternalTrackId("melon", "424991128"))
            .thenReturn(Optional.of(trackEntity));

        EmsCollectionService.MelonHot100CollectionResult result = service()
            .collectMelonHot100(List.of(seed), seed.snapshotAt());

        assertThat(result.playlistId()).isEqualTo(27L);
        assertThat(result.collectedPlaylistCount()).isEqualTo(1);
        assertThat(result.collectedTrackCount()).isEqualTo(1);
        ArgumentCaptor<EmsCollectedPlaylistEntity> playlistCaptor =
            ArgumentCaptor.forClass(EmsCollectedPlaylistEntity.class);
        verify(playlistRepository).save(playlistCaptor.capture());
        assertThat(playlistCaptor.getValue().getSourcePlatform()).isEqualTo("melon");
        assertThat(playlistCaptor.getValue().getCollectionSource()).isEqualTo(EmsCollectionService.MELON_HOT_100_SOURCE);
        assertThat(playlistCaptor.getValue().getTrackCount()).isEqualTo(1);
        verify(playlistTrackRepository).deleteByPlaylistId(27L);
        verify(playlistTrackRepository).upsertPlaylistTrackLink(27L, 28L, 0);
    }

    @Test
    void shouldClampSearchPoolPlaylistMetadataToDatabaseColumnLengths() {
        PlatformAccountCredential credential = credential("spotify");
        String longDescription = "k-pop ".repeat(260);
        when(platformCredentialService.findUsableCredential("user-001", "spotify"))
            .thenReturn(Optional.of(credential));
        when(spotifyWebApiClient.searchPlaylists(credential, "k-pop"))
            .thenReturn(new SpotifySearchResult<>(List.of(
                new SpotifyPlaylistSummary(
                    "playlist-kpop",
                    "K-Pop Search",
                    longDescription,
                    "owner-001",
                    "Curator",
                    false,
                    10,
                    null,
                    "https://open.spotify.com/playlist/playlist-kpop",
                    "spotify:playlist:playlist-kpop"
                )
            ), 1));
        when(spotifyWebApiClient.searchTracks(credential, "k-pop"))
            .thenReturn(new SpotifySearchResult<>(List.of(), 0));
        when(poolRunRepository.save(any(EmsPoolIngestRunEntity.class)))
            .thenReturn(poolRun("user-001", "spotify", "k-pop", 1, 0));

        service().previewSearch("user-001", "spotify", "k-pop");

        ArgumentCaptor<Iterable<EmsPoolEntryEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(poolEntryRepository).saveAll(captor.capture());
        EmsPoolEntryEntity entry = captor.getValue().iterator().next();
        assertThat(entry.getDescription()).hasSize(1000);
        assertThat(entry.getEntryType()).isEqualTo(EmsPoolEntryEntity.TYPE_PLAYLIST);
    }

    @Test
    void shouldBackfillTidalPlaylistAudioFeaturesByIsrc() {
        EmsCollectedPlaylistEntity playlist = new EmsCollectedPlaylistEntity(
            "playlist-001",
            "Pop Hits",
            "tidal",
            "",
            "",
            null,
            "https://tidal.com/browse/playlist/playlist-001",
            null,
            1,
            "tidal_home_page",
            "THE_HITS",
            Instant.parse("2026-05-10T00:00:00Z")
        );
        ReflectionTestUtils.setField(playlist, "id", 2L);
        EmsCollectedTrackEntity track = new EmsCollectedTrackEntity(
            "tidal-track-001",
            "Midnight Receiver",
            "Neon Bloom",
            "tidal",
            "USRC17607839",
            "Album",
            null,
            "https://tidal.com/browse/track/tidal-track-001",
            "tidal:track:tidal-track-001",
            null,
            218000,
            "tidal_home_page",
            Instant.parse("2026-05-10T00:00:00Z"),
            unavailableAudioFeatures()
        );
        EmsCollectedPlaylistTrackEntity link = new EmsCollectedPlaylistTrackEntity(playlist, track, 0);

        when(playlistRepository.findById(2L)).thenReturn(Optional.of(playlist));
        when(playlistTrackRepository.findByPlaylistIdOrderBySortOrderAsc(2L)).thenReturn(List.of(link));
        when(reccoBeatsAudioFeaturesClient.getAudioFeaturesForExternalTracksByIsrc(List.of(
            new ReccoBeatsTrackLookupRequest(
                "tidal-track-001",
                "Midnight Receiver",
                "Neon Bloom",
                218000,
                "USRC17607839"
            )
        ))).thenReturn(Map.of(
            "tidal-track-001",
            new ReccoBeatsAudioFeaturesSnapshot(
                "spotify-track-001",
                "recco-track-001",
                "https://open.spotify.com/track/spotify-track-001",
                "USRC17607839",
                0.211,
                0.702,
                0.744,
                0.013,
                8,
                0.094,
                -8.7,
                1,
                0.039,
                118.4,
                0.58,
                Instant.parse("2026-05-10T01:00:00Z")
            )
        ));

        EmsCollectionService.EmsAudioFeatureBackfillResult result = service()
            .backfillAudioFeaturesForPlaylist(2L);

        assertThat(result.newlyFilledTrackCount()).isEqualTo(1);
        assertThat(result.missingIsrcTrackCount()).isZero();
        assertThat(result.coverageRatioAfter()).isEqualTo(1.0);
        assertThat(track.getAudioFeatures().isAudioFeaturesFilled()).isTrue();
        assertThat(track.getAudioFeatures().getAudioFeatureSource()).isEqualTo("reccobeats_isrc_match");
        assertThat(track.getAudioFeatures().getTempo()).isEqualTo(118.4);
        verify(trackRepository).saveAll(List.of(track));
    }

    @Test
    void shouldReportTidalBackfillTracksMissingIsrc() {
        EmsCollectedPlaylistEntity playlist = new EmsCollectedPlaylistEntity(
            "playlist-002",
            "No ISRC",
            "tidal",
            "",
            "",
            null,
            "https://tidal.com/browse/playlist/playlist-002",
            null,
            1,
            "tidal_home_page",
            "THE_HITS",
            Instant.parse("2026-05-10T00:00:00Z")
        );
        ReflectionTestUtils.setField(playlist, "id", 3L);
        EmsCollectedTrackEntity track = new EmsCollectedTrackEntity(
            "tidal-track-002",
            "Missing Code",
            "Stored Artist",
            "tidal",
            null,
            "Album",
            null,
            "https://tidal.com/browse/track/tidal-track-002",
            "tidal:track:tidal-track-002",
            null,
            218000,
            "tidal_home_page",
            Instant.parse("2026-05-10T00:00:00Z"),
            unavailableAudioFeatures()
        );
        EmsCollectedPlaylistTrackEntity link = new EmsCollectedPlaylistTrackEntity(playlist, track, 0);

        when(playlistRepository.findById(3L)).thenReturn(Optional.of(playlist));
        when(playlistTrackRepository.findByPlaylistIdOrderBySortOrderAsc(3L)).thenReturn(List.of(link));

        EmsCollectionService.EmsAudioFeatureBackfillResult result = service()
            .backfillAudioFeaturesForPlaylist(3L);

        assertThat(result.eligibleTrackCount()).isZero();
        assertThat(result.missingIsrcTrackCount()).isEqualTo(1);
        assertThat(result.matchedSnapshotCount()).isZero();
        assertThat(result.newlyFilledTrackCount()).isZero();
        verifyNoInteractions(trackRepository, reccoBeatsAudioFeaturesClient);
    }

    private EmsCollectionService service() {
        return service(Optional.empty());
    }

    private EmsCollectionService service(Optional<AudioFeatureCompletionAutoEnqueueService> autoEnqueueService) {
        return new EmsCollectionService(
            spotifyWebApiClient,
            tidalWebApiClient,
            reccoBeatsAudioFeaturesClient,
            platformCredentialService,
            authAccountStore,
            playlistRepository,
            trackRepository,
            playlistTrackRepository,
            poolRunRepository,
            poolEntryRepository,
            floSpecialCurationService,
            eventPublisher,
            autoEnqueueService
        );
    }

    private EmsPoolIngestRunEntity poolRun(
        String userId,
        String platformId,
        String query,
        int playlistCount,
        int trackCount
    ) {
        EmsPoolIngestRunEntity run = new EmsPoolIngestRunEntity(
            userId,
            platformId,
            query,
            playlistCount,
            trackCount,
            Instant.parse("2026-05-10T00:00:00Z")
        );
        ReflectionTestUtils.setField(run, "id", 55L);
        return run;
    }

    private AuthRegisteredAccount account(String preferredPlatformId) {
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

    private PlatformAccountCredential credential(String platformId) {
        return new PlatformAccountCredential(
            "user-001",
            platformId,
            "oauth",
            "external-user-001",
            "External User",
            "access-token",
            "refresh-token",
            "Bearer",
            "playlist-read",
            Instant.parse("2026-05-10T00:00:00Z"),
            Instant.parse("2026-05-09T00:00:00Z"),
            Instant.parse("2026-05-09T00:00:00Z")
        );
    }

    private EmsCollectedPlaylistEntity collectedPlaylist(
        String externalPlaylistId,
        String platformId,
        String title,
        int trackCount
    ) {
        return new EmsCollectedPlaylistEntity(
            externalPlaylistId,
            title,
            platformId,
            "",
            "",
            null,
            null,
            "spotify".equals(platformId) ? "spotify:playlist:%s".formatted(externalPlaylistId) : null,
            trackCount,
            "public_pool",
            null,
            Instant.parse("2026-05-09T00:00:00Z")
        );
    }

    private EmsCollectedTrackEntity collectedTrack(
        String externalTrackId,
        String platformId,
        String title,
        String artistName,
        String isrc
    ) {
        return new EmsCollectedTrackEntity(
            externalTrackId,
            title,
            artistName,
            platformId,
            isrc,
            null,
            null,
            null,
            "spotify".equals(platformId) ? "spotify:track:%s".formatted(externalTrackId) : null,
            null,
            180000,
            "search_pool",
            Instant.parse("2026-05-09T00:00:00Z"),
            unavailableAudioFeatures()
        );
    }

    private EmsTrackAudioFeatures unavailableAudioFeatures() {
        return new EmsTrackAudioFeatures(
            null,
            "unavailable",
            false,
            null,
            null,
            null,
            "audio_features",
            218000,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Instant.parse("2026-05-10T00:00:00Z")
        );
    }
}
