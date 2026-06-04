package io.myforevermusic.api.modules.ems.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import io.myforevermusic.api.modules.platform.infrastructure.spotify.SpotifyAppTokenService;
import io.myforevermusic.api.modules.platform.infrastructure.spotify.SpotifyEmbedPlaylistScraper;
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
    private SpotifyAppTokenService spotifyAppTokenService;

    @Mock
    private SpotifyEmbedPlaylistScraper spotifyEmbedPlaylistScraper;

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
        when(spotifyAppTokenService.getAccessToken()).thenReturn("spotify-app-token");
        when(spotifyWebApiClient.searchPlaylists(any(PlatformAccountCredential.class), any()))
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
        when(spotifyWebApiClient.searchTracks(any(PlatformAccountCredential.class), any()))
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
        ArgumentCaptor<PlatformAccountCredential> credentialCaptor =
            ArgumentCaptor.forClass(PlatformAccountCredential.class);
        verify(spotifyWebApiClient).searchPlaylists(credentialCaptor.capture(), any());
        assertThat(credentialCaptor.getValue().authorizationMode()).isEqualTo("client_credentials");
        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("spotify-app-token");
        verify(eventPublisher).publishEvent(new EmsPoolRunQueuedEvent(55L));
        verifyNoInteractions(platformCredentialService, playlistRepository, trackRepository, playlistTrackRepository, reccoBeatsAudioFeaturesClient);
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
    void shouldQueueSpotifyFeaturedChartsIntoEmsPool() {
        EmsPoolIngestRunEntity run = poolRun("user-001", "spotify", "spotify:featured-charts", 4, 0);
        when(poolRunRepository.save(any(EmsPoolIngestRunEntity.class))).thenReturn(run);

        EmsCollectionService.EmsCollectionSearchPreviewResult result =
            service().queueSpotifyFeaturedChartsPool("user-001");

        assertThat(result.poolRunId()).isEqualTo(55L);
        assertThat(result.platformId()).isEqualTo("spotify");
        assertThat(result.query()).isEqualTo("spotify:featured-charts");
        assertThat(result.resultPlaylistCount()).isEqualTo(4);
        assertThat(result.resultTrackCount()).isZero();
        assertThat(result.playlists()).extracting(EmsCollectionService.EmsCollectionSearchPlaylistPreview::externalPlaylistId)
            .containsExactly(
                "37i9dQZEVXbMDoHDwVN2tF",
                "37i9dQZEVXbLRQDuF5jeBp",
                "37i9dQZEVXbNxXF4SkHj9F",
                "37i9dQZEVXbKXQ4mDTEBXq"
            );
        ArgumentCaptor<Iterable<EmsPoolEntryEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(poolEntryRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(4);
        verify(eventPublisher).publishEvent(new EmsPoolRunQueuedEvent(55L));
        verifyNoInteractions(platformCredentialService, spotifyWebApiClient, playlistRepository, trackRepository, playlistTrackRepository);
    }

    @Test
    void shouldStoreAllTidalHomePlaylistMetadataWithoutFetchingTracksInline() {
        io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistSummary playlist =
            tidalPlaylist("tidal-home-001", "TIDAL Home 001", 50);
        when(tidalWebApiClient.getAllPublicHomePagePlaylists("POPULAR_PLAYLISTS"))
            .thenReturn(List.of(playlist));
        when(playlistRepository.findBySourcePlatformAndExternalPlaylistId("tidal", "tidal-home-001"))
            .thenReturn(Optional.empty());
        when(playlistRepository.save(any(EmsCollectedPlaylistEntity.class)))
            .thenAnswer(invocation -> {
                EmsCollectedPlaylistEntity saved = invocation.getArgument(0);
                ReflectionTestUtils.setField(saved, "id", 101L);
                return saved;
            });

        EmsCollectionService.EmsCollectionSearchResult result =
            service().collectPublicPlaylistPool(null, "tidal", "POPULAR_PLAYLISTS", 5);

        assertThat(result.collectedPlaylistCount()).isEqualTo(1);
        assertThat(result.collectedTrackCount()).isZero();
        verify(tidalWebApiClient).getAllPublicHomePagePlaylists("POPULAR_PLAYLISTS");
        verify(tidalWebApiClient, never()).getPublicPlaylistTracks(any());
        verify(playlistRepository).deleteTidalHomeSources("tidal", "public_pool", "POPULAR_PLAYLISTS");
        verify(playlistRepository).upsertPlaylistSource(
            any(), eq("tidal"), eq("public_pool"), eq("POPULAR_PLAYLISTS"), any()
        );
    }

    @Test
    void shouldKeepMembershipWhenOneTidalPlaylistAppearsInMultipleHomeSources() {
        io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistSummary playlist =
            tidalPlaylist("shared-playlist", "Shared Playlist", 30);
        when(tidalWebApiClient.getAllPublicHomePagePlaylists("THE_HITS")).thenReturn(List.of(playlist));
        when(tidalWebApiClient.getAllPublicHomePagePlaylists("POPULAR_PLAYLISTS")).thenReturn(List.of(playlist));
        EmsCollectedPlaylistEntity stored = collectedPlaylist("shared-playlist", "tidal", "Shared Playlist", 30);
        ReflectionTestUtils.setField(stored, "id", 202L);
        when(playlistRepository.findBySourcePlatformAndExternalPlaylistId("tidal", "shared-playlist"))
            .thenReturn(Optional.of(stored));

        EmsCollectionService service = service();
        service.collectPublicPlaylistPool(null, "tidal", "THE_HITS", 5);
        service.collectPublicPlaylistPool(null, "tidal", "POPULAR_PLAYLISTS", 5);

        verify(playlistRepository).upsertPlaylistSource(
            eq(202L), eq("tidal"), eq("public_pool"), eq("THE_HITS"), any()
        );
        verify(playlistRepository).upsertPlaylistSource(
            eq(202L), eq("tidal"), eq("public_pool"), eq("POPULAR_PLAYLISTS"), any()
        );
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

        assertThat(result.playlistId()).isEqualTo(7L);
        assertThat(result.trackCount()).isEqualTo(1);
        assertThat(result.tracks()).extracting(EmsCollectionService.EmsCollectionSearchTrackPreview::externalTrackId)
            .containsExactly("tidal-track-001");
        verify(playlistTrackRepository).upsertPlaylistTrackLink(7L, 8L, 0);
        verifyNoInteractions(reccoBeatsAudioFeaturesClient);
    }

    @Test
    void shouldLoadPublicSpotifySearchPlaylistTracksWithoutUserSpotifyConnection() {
        EmsCollectedPlaylistEntity playlistEntity = collectedPlaylist(
            "37i9dQZEVXbMDoHDwVN2tF",
            "spotify",
            "Top 50 - Global",
            50
        );
        ReflectionTestUtils.setField(playlistEntity, "id", 17L);
        EmsCollectedTrackEntity trackEntity = collectedTrack(
            "spotify-track-001",
            "spotify",
            "Chart Track",
            "Chart Artist",
            "USRC17607839"
        );
        ReflectionTestUtils.setField(trackEntity, "id", 18L);

        when(spotifyAppTokenService.getAccessToken()).thenReturn("spotify-app-token");
        when(playlistRepository.findBySourcePlatformAndExternalPlaylistId("spotify", "37i9dQZEVXbMDoHDwVN2tF"))
            .thenReturn(Optional.of(playlistEntity));
        when(spotifyWebApiClient.getPlaylistTracks(any(PlatformAccountCredential.class), any()))
            .thenReturn(List.of(new SpotifyPlaylistTrack(
                "spotify-track-001",
                "Chart Track",
                "Chart Artist",
                "Chart Album",
                null,
                "https://open.spotify.com/track/spotify-track-001",
                "https://open.spotify.com/track/spotify-track-001",
                "spotify:track:spotify-track-001",
                null,
                "USRC17607839",
                180000
            )));
        when(trackRepository.findBySourcePlatformAndExternalTrackId("spotify", "spotify-track-001"))
            .thenReturn(Optional.of(trackEntity));

        EmsCollectionService.EmsCollectionSearchPlaylistTracksPreview result =
            service().getSearchPlaylistTracks("user-001", "spotify", "37i9dQZEVXbMDoHDwVN2tF");

        assertThat(result.playlistId()).isEqualTo(17L);
        assertThat(result.trackCount()).isEqualTo(1);
        assertThat(result.tracks()).extracting(EmsCollectionService.EmsCollectionSearchTrackPreview::externalTrackId)
            .containsExactly("spotify-track-001");
        ArgumentCaptor<PlatformAccountCredential> credentialCaptor =
            ArgumentCaptor.forClass(PlatformAccountCredential.class);
        verify(spotifyWebApiClient).getPlaylistTracks(credentialCaptor.capture(), any());
        assertThat(credentialCaptor.getValue().authorizationMode()).isEqualTo("client_credentials");
        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("spotify-app-token");
        verify(playlistTrackRepository).upsertPlaylistTrackLink(17L, 18L, 0);
    }

    @Test
    void shouldPreferSpotifyAppTokenForPublicSearchPlaylistTracksWithoutConsultingUserSpotifyToken() {
        EmsCollectedPlaylistEntity playlistEntity = collectedPlaylist(
            "37i9dQZEVXbMDoHDwVN2tF",
            "spotify",
            "Top 50 - Global",
            50
        );
        ReflectionTestUtils.setField(playlistEntity, "id", 17L);
        EmsCollectedTrackEntity trackEntity = collectedTrack(
            "spotify-track-001",
            "spotify",
            "Chart Track",
            "Chart Artist",
            "USRC17607839"
        );
        ReflectionTestUtils.setField(trackEntity, "id", 18L);

        when(spotifyAppTokenService.getAccessToken()).thenReturn("spotify-app-token");
        when(playlistRepository.findBySourcePlatformAndExternalPlaylistId("spotify", "37i9dQZEVXbMDoHDwVN2tF"))
            .thenReturn(Optional.of(playlistEntity));
        when(spotifyWebApiClient.getPlaylistTracks(any(PlatformAccountCredential.class), any()))
            .thenReturn(List.of(new SpotifyPlaylistTrack(
                "spotify-track-001",
                "Chart Track",
                "Chart Artist",
                "Chart Album",
                null,
                "https://open.spotify.com/track/spotify-track-001",
                "https://open.spotify.com/track/spotify-track-001",
                "spotify:track:spotify-track-001",
                null,
                "USRC17607839",
                180000
            )));
        when(trackRepository.findBySourcePlatformAndExternalTrackId("spotify", "spotify-track-001"))
            .thenReturn(Optional.of(trackEntity));

        service().getSearchPlaylistTracks("user-001", "spotify", "37i9dQZEVXbMDoHDwVN2tF");

        ArgumentCaptor<PlatformAccountCredential> credentialCaptor =
            ArgumentCaptor.forClass(PlatformAccountCredential.class);
        verify(spotifyWebApiClient).getPlaylistTracks(credentialCaptor.capture(), any());
        assertThat(credentialCaptor.getValue().authorizationMode()).isEqualTo("client_credentials");
        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("spotify-app-token");
        verifyNoInteractions(platformCredentialService);
    }

    @Test
    void shouldRefreshSpotifyAppTokenOnceWhenPublicPlaylistTrackRequestIsUnauthorized() {
        EmsCollectedPlaylistEntity playlistEntity = collectedPlaylist(
            "37i9dQZEVXbMDoHDwVN2tF",
            "spotify",
            "Top 50 - Global",
            50
        );
        ReflectionTestUtils.setField(playlistEntity, "id", 17L);
        EmsCollectedTrackEntity trackEntity = collectedTrack(
            "spotify-track-001",
            "spotify",
            "Chart Track",
            "Chart Artist",
            "USRC17607839"
        );
        ReflectionTestUtils.setField(trackEntity, "id", 18L);

        when(spotifyAppTokenService.getAccessToken())
            .thenReturn("stale-app-token")
            .thenReturn("fresh-app-token");
        when(playlistRepository.findBySourcePlatformAndExternalPlaylistId("spotify", "37i9dQZEVXbMDoHDwVN2tF"))
            .thenReturn(Optional.of(playlistEntity));
        when(spotifyWebApiClient.getPlaylistTracks(any(PlatformAccountCredential.class), any()))
            .thenThrow(new IllegalArgumentException("Spotify access token is invalid or expired. Reconnect Spotify and try again."))
            .thenReturn(List.of(new SpotifyPlaylistTrack(
                "spotify-track-001",
                "Chart Track",
                "Chart Artist",
                "Chart Album",
                null,
                "https://open.spotify.com/track/spotify-track-001",
                "https://open.spotify.com/track/spotify-track-001",
                "spotify:track:spotify-track-001",
                null,
                "USRC17607839",
                180000
            )));
        when(trackRepository.findBySourcePlatformAndExternalTrackId("spotify", "spotify-track-001"))
            .thenReturn(Optional.of(trackEntity));

        EmsCollectionService.EmsCollectionSearchPlaylistTracksPreview result =
            service().getSearchPlaylistTracks("user-001", "spotify", "37i9dQZEVXbMDoHDwVN2tF");

        assertThat(result.trackCount()).isEqualTo(1);
        verify(spotifyAppTokenService).invalidateCache();
        ArgumentCaptor<PlatformAccountCredential> credentialCaptor =
            ArgumentCaptor.forClass(PlatformAccountCredential.class);
        verify(spotifyWebApiClient, times(2)).getPlaylistTracks(credentialCaptor.capture(), any());
        assertThat(credentialCaptor.getAllValues())
            .extracting(PlatformAccountCredential::accessToken)
            .containsExactly("stale-app-token", "fresh-app-token");
    }

    @Test
    void shouldUseSpotifyEmbedFallbackWhenPublicPlaylistItemsRequireUserAuthentication() {
        EmsCollectedPlaylistEntity playlistEntity = collectedPlaylist(
            "37i9dQZEVXbMDoHDwVN2tF",
            "spotify",
            "Top 50 - Global",
            50
        );
        ReflectionTestUtils.setField(playlistEntity, "id", 17L);
        EmsCollectedTrackEntity trackEntity = collectedTrack(
            "spotify-track-001",
            "spotify",
            "Chart Track",
            "Chart Artist",
            "USRC17607839"
        );
        ReflectionTestUtils.setField(trackEntity, "id", 18L);

        when(spotifyAppTokenService.getAccessToken()).thenReturn("spotify-app-token");
        when(playlistRepository.findBySourcePlatformAndExternalPlaylistId("spotify", "37i9dQZEVXbMDoHDwVN2tF"))
            .thenReturn(Optional.of(playlistEntity));
        when(spotifyWebApiClient.getPlaylistTracks(any(PlatformAccountCredential.class), any()))
            .thenThrow(new IllegalArgumentException("Spotify API request failed (401): Valid user authentication required"));
        when(spotifyEmbedPlaylistScraper.getPlaylistTracks("37i9dQZEVXbMDoHDwVN2tF"))
            .thenReturn(List.of(new SpotifyPlaylistTrack(
                "spotify-track-001",
                "Chart Track",
                "Chart Artist",
                null,
                null,
                "https://api.spotify.com/v1/tracks/spotify-track-001",
                "https://open.spotify.com/track/spotify-track-001",
                "spotify:track:spotify-track-001",
                "https://p.scdn.co/mp3-preview/sample",
                null,
                180000
            )));
        when(trackRepository.findBySourcePlatformAndExternalTrackId("spotify", "spotify-track-001"))
            .thenReturn(Optional.of(trackEntity));

        EmsCollectionService.EmsCollectionSearchPlaylistTracksPreview result =
            service().getSearchPlaylistTracks("user-001", "spotify", "37i9dQZEVXbMDoHDwVN2tF");

        assertThat(result.trackCount()).isEqualTo(1);
        assertThat(result.tracks()).extracting(EmsCollectionService.EmsCollectionSearchTrackPreview::previewUrl)
            .containsExactly("https://p.scdn.co/mp3-preview/sample");
        verify(spotifyEmbedPlaylistScraper).getPlaylistTracks("37i9dQZEVXbMDoHDwVN2tF");
        verify(playlistTrackRepository).upsertPlaylistTrackLink(17L, 18L, 0);
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
        String longDescription = "k-pop ".repeat(260);
        when(spotifyAppTokenService.getAccessToken()).thenReturn("spotify-app-token");
        when(spotifyWebApiClient.searchPlaylists(any(PlatformAccountCredential.class), any()))
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
        when(spotifyWebApiClient.searchTracks(any(PlatformAccountCredential.class), any()))
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
            spotifyAppTokenService,
            spotifyEmbedPlaylistScraper,
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

    private io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistSummary tidalPlaylist(
        String id,
        String title,
        int trackCount
    ) {
        return new io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistSummary(
            id,
            title,
            "",
            trackCount,
            null,
            null,
            "https://tidal.com/browse/playlist/" + id,
            id
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
