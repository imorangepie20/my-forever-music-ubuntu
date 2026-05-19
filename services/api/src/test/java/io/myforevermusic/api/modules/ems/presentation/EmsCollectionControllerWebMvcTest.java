package io.myforevermusic.api.modules.ems.presentation;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.myforevermusic.api.modules.ems.application.EmsCollectionService;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService.EmsAudioFeatureBackfillResult;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService.EmsAudioFeatureCoverage;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService.EmsCollectionSearchPlaylistPreview;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService.EmsCollectionSearchPlaylistTracksPreview;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService.EmsCollectionSearchPreviewResult;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService.EmsCollectionSearchTrackPreview;
import io.myforevermusic.api.modules.ems.application.EmsPlaylistCurationService;
import io.myforevermusic.api.modules.ems.application.EmsPlaylistCurationService.EmsPlaylistCurationResult;
import io.myforevermusic.api.modules.ems.application.EmsPlaylistCurationService.EmsPlaylistSection;
import io.myforevermusic.api.modules.ems.application.EmsPlaylistCurationService.EmsPlaylistSectionItem;
import io.myforevermusic.api.modules.ems.application.EmsPlaylistCurationService.PlaylistAudioStats;
import io.myforevermusic.api.modules.ems.application.EmsLooseTrackPlaylistService;
import io.myforevermusic.api.modules.ems.application.EmsLooseTrackPlaylistService.LooseTrackPlaylistMaterializationResult;
import io.myforevermusic.api.modules.ems.application.EmsLooseTrackPlaylistService.MaterializedPlaylist;
import io.myforevermusic.api.modules.ems.application.EmsPoolIngestService;
import io.myforevermusic.api.modules.ems.application.EmsPublicPlaylistDiscoveryScheduler;
import io.myforevermusic.api.modules.ems.application.EmsPublicPlaylistDiscoveryScheduler.EmsPublicPlaylistDiscoveryFailure;
import io.myforevermusic.api.modules.ems.application.EmsPublicPlaylistDiscoveryScheduler.EmsPublicPlaylistDiscoveryRun;
import io.myforevermusic.api.modules.ems.application.FloSpecialCurationScheduler;
import io.myforevermusic.api.modules.ems.application.FloSpecialProperties;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsTrackAudioFeatures;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EmsCollectionController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class EmsCollectionControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmsCollectionService emsCollectionService;

    @MockBean
    private EmsPlaylistCurationService emsPlaylistCurationService;

    @MockBean
    private EmsPublicPlaylistDiscoveryScheduler emsPublicPlaylistDiscoveryScheduler;

    @MockBean
    private EmsPoolIngestService emsPoolIngestService;

    @MockBean
    private EmsLooseTrackPlaylistService emsLooseTrackPlaylistService;

    @MockBean
    private FloSpecialCurationScheduler floSpecialCurationScheduler;

    @MockBean
    private FloSpecialProperties floSpecialProperties;

    @Test
    void shouldReturnEmsSearchResultsStoredInSearchPool() throws Exception {
        when(emsCollectionService.previewSearch("user-001", null, "jazz"))
            .thenReturn(new EmsCollectionSearchPreviewResult(
                "all",
                "jazz",
                44L,
                List.of(new EmsCollectionSearchPlaylistPreview(
                    "playlist-001",
                    "Stored Outside EMS",
                    "tidal",
                    "",
                    "Preview only",
                    null,
                    "https://tidal.com/browse/playlist/playlist-001",
                    null,
                    20
                )),
                List.of(new EmsCollectionSearchTrackPreview(
                    "track-001",
                    "Preview Track",
                    "Preview Artist",
                    "tidal",
                    "USRC17607839",
                    "Preview Album",
                    null,
                    "https://tidal.com/browse/track/track-001",
                    "tidal:track:track-001",
                    null,
                    210000
                )),
                5,
                9,
                Instant.parse("2026-05-10T00:00:00Z")
            ));

        mockMvc.perform(post("/api/v1/ems/collection/search")
                .contentType("application/json")
                .content("""
                    {
                      "user_id": "user-001",
                      "query": "jazz"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ems_search_pooled"))
            .andExpect(jsonPath("$.pool_run_id").value(44))
            .andExpect(jsonPath("$.result_playlist_count").value(5))
            .andExpect(jsonPath("$.result_track_count").value(9))
            .andExpect(jsonPath("$.playlists[0].title").value("Stored Outside EMS"))
            .andExpect(jsonPath("$.tracks[0].isrc").value("USRC17607839"));
    }

    @Test
    void shouldPreviewSearchPlaylistTracksWithoutCollection() throws Exception {
        when(emsCollectionService.getSearchPlaylistTracks("user-001", "tidal", "playlist-001"))
            .thenReturn(new EmsCollectionSearchPlaylistTracksPreview(
                "tidal",
                "playlist-001",
                List.of(new EmsCollectionSearchTrackPreview(
                    "track-001",
                    "Preview Track",
                    "Preview Artist",
                    "tidal",
                    "USRC17607839",
                    "Preview Album",
                    null,
                    "https://tidal.com/browse/track/track-001",
                    "tidal:track:track-001",
                    null,
                    210000
                )),
                12,
                Instant.parse("2026-05-10T00:00:00Z")
            ));

        mockMvc.perform(get("/api/v1/ems/collection/search/playlists/tidal/playlist-001/tracks")
                .param("user_id", "user-001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.platform_id").value("tidal"))
            .andExpect(jsonPath("$.external_playlist_id").value("playlist-001"))
            .andExpect(jsonPath("$.track_count").value(12))
            .andExpect(jsonPath("$.tracks[0].title").value("Preview Track"))
            .andExpect(jsonPath("$.tracks[0].platform_uri").value("tidal:track:track-001"));
    }

    @Test
    void shouldDisplayCollectedPlaylistTracksFromDatabaseOnly() throws Exception {
        EmsCollectedPlaylistEntity playlist = new EmsCollectedPlaylistEntity(
            "playlist-001",
            "Stored EMS Playlist",
            "tidal",
            "TIDAL curator",
            "Stored playlist description",
            null,
            "https://tidal.com/browse/playlist/playlist-001",
            null,
            1,
            "public_pool",
            "editorial",
            Instant.parse("2026-05-10T00:00:00Z")
        );
        ReflectionTestUtils.setField(playlist, "id", 77L);

        EmsCollectedTrackEntity track = new EmsCollectedTrackEntity(
            "track-001",
            "Stored Track",
            "Stored Artist",
            "tidal",
            "USRC17607839",
            "Stored Album",
            null,
            "https://tidal.com/browse/track/track-001",
            "tidal:track:track-001",
            null,
            211000,
            "public_pool",
            Instant.parse("2026-05-10T00:00:00Z"),
            new EmsTrackAudioFeatures(
                null,
                "unavailable",
                false,
                null,
                null,
                null,
                "audio_features",
                211000,
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
            )
        );
        ReflectionTestUtils.setField(track, "id", 88L);

        when(emsCollectionService.getCollectedPlaylist(77L)).thenReturn(playlist);
        when(emsCollectionService.getTracksForPlaylist(77L)).thenReturn(List.of(track));
        when(emsCollectionService.getAudioFeatureCoverage(77L)).thenReturn(new EmsAudioFeatureCoverage(1, 0, 1, 0.0));

        mockMvc.perform(get("/api/v1/ems/collection/playlists/77").param("user_id", "user-001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.playlist.id").value(77))
            .andExpect(jsonPath("$.tracks[0].id").value(88))
            .andExpect(jsonPath("$.tracks[0].isrc").value("USRC17607839"))
            .andExpect(jsonPath("$.tracks[0].platform_uri").value("tidal:track:track-001"));

        verify(emsCollectionService).getTracksForPlaylist(77L);
    }

    @Test
    void shouldMaterializeLooseTracksIntoEmsPlaylistsForAdmin() throws Exception {
        when(emsLooseTrackPlaylistService.materializeLooseTracks(120, 30))
            .thenReturn(new LooseTrackPlaylistMaterializationResult(
                199,
                120,
                4,
                120,
                79,
                Instant.parse("2026-05-10T04:00:00Z"),
                List.of(new MaterializedPlaylist(
                    901L,
                    "EMS 추천 후보 · Spotify · acquisition pool",
                    "spotify",
                    "acquisition_pool",
                    30
                ))
            ));

        mockMvc.perform(post("/api/v1/ems/collection/admin/playlists/materialize-loose-tracks")
                .param("user_id", "admin-user")
                .param("track_limit", "120")
                .param("tracks_per_playlist", "30"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unassigned_track_count_before").value(199))
            .andExpect(jsonPath("$.selected_track_count").value(120))
            .andExpect(jsonPath("$.created_playlist_count").value(4))
            .andExpect(jsonPath("$.linked_track_count").value(120))
            .andExpect(jsonPath("$.unassigned_track_count_after").value(79))
            .andExpect(jsonPath("$.playlists[0].playlist_id").value(901))
            .andExpect(jsonPath("$.playlists[0].source_collection").value("acquisition_pool"));

        verify(emsPoolIngestService).assertAdminAccess("admin-user");
    }

    @Test
    void shouldReturnCuratedPlaylistSections() throws Exception {
        EmsCollectedPlaylistEntity playlist = new EmsCollectedPlaylistEntity(
            "playlist-ems-001",
            "K-Pop Night Drive",
            "tidal",
            "TIDAL editors",
            "NewJeans and late night city pop",
            null,
            "https://tidal.com/browse/playlist/playlist-ems-001",
            null,
            25,
            "acquisition_pool",
            "newjeans",
            Instant.parse("2026-05-10T00:00:00Z")
        );
        ReflectionTestUtils.setField(playlist, "id", 91L);

        when(emsPlaylistCurationService.getPlaylistSections("user-001", List.of("tidal", "spotify"), 4))
            .thenReturn(new EmsPlaylistCurationResult(
                "user-001",
                List.of("tidal", "spotify"),
                EmsPlaylistCurationService.TITLE_MODEL,
                true,
                List.of(new EmsPlaylistSection(
                    "personalized-signal",
                    "NewJeans 근처에서 확장하는 EMS",
                    "최근 PMS 행동 신호와 EMS 공개 풀을 겹쳐서 고른 후보",
                    "personalized",
                    "NewJeans",
                    "hero",
                    EmsPlaylistCurationService.TITLE_MODEL,
                    List.of(new EmsPlaylistSectionItem(
                        playlist,
                        new PlaylistAudioStats(24, 18, 0.75, 0.42, 0.6, 0.7, 0.2, 0.1),
                        List.of("artist NewJeans", "source tidal")
                    ))
                ))
            ));

        mockMvc.perform(get("/api/v1/ems/collection/playlists/sections")
                .param("user_id", "user-001")
                .param("platform_id", "tidal,spotify")
                .param("limit", "4"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title_model").value(EmsPlaylistCurationService.TITLE_MODEL))
            .andExpect(jsonPath("$.personalized").value(true))
            .andExpect(jsonPath("$.sections[0].category_type").value("personalized"))
            .andExpect(jsonPath("$.sections[0].display_style").value("hero"))
            .andExpect(jsonPath("$.sections[0].playlists[0].playlist.title").value("K-Pop Night Drive"))
            .andExpect(jsonPath("$.sections[0].playlists[0].playlist.audio_feature_coverage.coverage_ratio").value(0.75))
            .andExpect(jsonPath("$.sections[0].playlists[0].match_signals[0]").value("artist NewJeans"));
    }

    @Test
    void shouldReturnStoredFloSpecialPlaylistsByTopic() throws Exception {
        EmsCollectedPlaylistEntity playlist = new EmsCollectedPlaylistEntity(
            "1777671282043761",
            "FLO Playlist",
            "flo",
            "FLO Special",
            "오늘의 FLO",
            "https://cdn.music-flo.com/playlist.jpg",
            "https://www.music-flo.com/detail/playlist/1777671282043761",
            null,
            15,
            EmsCollectionService.FLO_SPECIAL_SOURCE,
            "오늘의 FLO",
            Instant.parse("2026-05-10T00:00:00Z")
        );
        ReflectionTestUtils.setField(playlist, "id", 101L);

        when(floSpecialProperties.getDisplayLimit()).thenReturn(120);
        when(emsCollectionService.getFloSpecialPlaylists(120)).thenReturn(List.of(playlist));
        when(emsCollectionService.getAudioFeatureCoverage(101L)).thenReturn(new EmsAudioFeatureCoverage(15, 0, 15, 0.0));

        mockMvc.perform(get("/api/v1/ems/collection/flo-special"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.collection_source").value(EmsCollectionService.FLO_SPECIAL_SOURCE))
            .andExpect(jsonPath("$.sections[0].title").value("오늘의 FLO"))
            .andExpect(jsonPath("$.sections[0].playlists[0].id").value(101))
            .andExpect(jsonPath("$.sections[0].playlists[0].source_platform").value("flo"))
            .andExpect(jsonPath("$.sections[0].playlists[0].track_count").value(15));
    }

    @Test
    void shouldRunEmsDiscoveryManually() throws Exception {
        when(emsPublicPlaylistDiscoveryScheduler.runNow("user-001", List.of("tidal"), List.of("jazz"), 2))
            .thenReturn(new EmsPublicPlaylistDiscoveryRun(
                "manual",
                "completed_with_failures",
                Instant.parse("2026-05-10T01:00:00Z"),
                Instant.parse("2026-05-10T01:00:10Z"),
                List.of("tidal"),
                List.of("jazz"),
                2,
                1,
                24,
                List.of(new EmsPublicPlaylistDiscoveryFailure("tidal", "jazz", "token expired")),
                "EMS public playlist discovery completed_with_failures."
            ));

        mockMvc.perform(post("/api/v1/ems/collection/discovery/run")
                .contentType("application/json")
                .content("""
                    {
                      "user_id": "user-001",
                      "platforms": ["tidal"],
                      "seed_queries": ["jazz"],
                      "per_query_limit": 2
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("completed_with_failures"))
            .andExpect(jsonPath("$.trigger").value("manual"))
            .andExpect(jsonPath("$.platforms[0]").value("tidal"))
            .andExpect(jsonPath("$.seed_queries[0]").value("jazz"))
            .andExpect(jsonPath("$.per_query_limit").value(2))
            .andExpect(jsonPath("$.collected_playlist_count").value(1))
            .andExpect(jsonPath("$.collected_track_count").value(24))
            .andExpect(jsonPath("$.failures[0].platform_id").value("tidal"))
            .andExpect(jsonPath("$.failures[0].message").value("token expired"));
    }

    @Test
    void shouldBackfillCollectedPlaylistAudioFeatures() throws Exception {
        when(emsCollectionService.backfillAudioFeaturesForPlaylist(2L))
            .thenReturn(new EmsAudioFeatureBackfillResult(
                2L,
                "Pop Hits",
                "tidal",
                50,
                0,
                50,
                50,
                0,
                12,
                12,
                12,
                12,
                38,
                0.24,
                Instant.parse("2026-05-10T02:00:00Z")
            ));

        mockMvc.perform(post("/api/v1/ems/collection/playlists/2/audio-features/backfill"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.playlist_id").value(2))
            .andExpect(jsonPath("$.playlist_title").value("Pop Hits"))
            .andExpect(jsonPath("$.source_platform").value("tidal"))
            .andExpect(jsonPath("$.eligible_track_count").value(50))
            .andExpect(jsonPath("$.missing_isrc_track_count").value(0))
            .andExpect(jsonPath("$.newly_filled_track_count").value(12))
            .andExpect(jsonPath("$.coverage_ratio_after").value(0.24));
    }

    @Test
    void shouldExposeEmsDiscoveryStatusBeforeFirstRun() throws Exception {
        when(emsPublicPlaylistDiscoveryScheduler.lastRun()).thenReturn(null);

        mockMvc.perform(get("/api/v1/ems/collection/discovery/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("not_run"))
            .andExpect(jsonPath("$.message").value("EMS public playlist discovery has not run in this API process."));
    }
}
