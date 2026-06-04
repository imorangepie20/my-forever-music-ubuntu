package io.myforevermusic.api.modules.ems.presentation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService.EmsAudioFeatureBackfillResult;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService.EmsAudioFeatureCoverage;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService.EmsCollectionSearchPlaylistTracksPreview;
import io.myforevermusic.api.modules.ems.application.EmsCollectionService.EmsCollectionSearchPreviewResult;
import io.myforevermusic.api.modules.ems.application.EmsPlaylistCurationService;
import io.myforevermusic.api.modules.ems.application.EmsPlaylistCurationService.EmsPlaylistCurationResult;
import io.myforevermusic.api.modules.ems.application.EmsPlaylistCurationService.EmsPlaylistSection;
import io.myforevermusic.api.modules.ems.application.EmsPlaylistCurationService.EmsPlaylistSectionItem;
import io.myforevermusic.api.modules.ems.application.EmsPlaylistCurationService.PlaylistAudioStats;
import io.myforevermusic.api.modules.ems.application.EmsLooseTrackPlaylistService;
import io.myforevermusic.api.modules.ems.application.EmsLooseTrackPlaylistService.LooseTrackPlaylistMaterializationResult;
import io.myforevermusic.api.modules.ems.application.EmsPoolIngestService;
import io.myforevermusic.api.modules.ems.application.EmsPoolIngestService.EmsPoolEntrySnapshot;
import io.myforevermusic.api.modules.ems.application.EmsPoolIngestService.EmsPoolRunDetailSnapshot;
import io.myforevermusic.api.modules.ems.application.EmsPoolIngestService.EmsPoolRunSnapshot;
import io.myforevermusic.api.modules.ems.application.EmsPublicPlaylistDiscoveryScheduler;
import io.myforevermusic.api.modules.ems.application.EmsPublicPlaylistDiscoveryScheduler.EmsPublicPlaylistDiscoveryRun;
import io.myforevermusic.api.modules.ems.application.FloSpecialCurationScheduler;
import io.myforevermusic.api.modules.ems.application.FloSpecialCurationScheduler.FloSpecialUpdateRun;
import io.myforevermusic.api.modules.ems.application.FloSpecialProperties;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackEntity;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@org.springframework.context.annotation.Profile("!local")
@RestController
@RequestMapping("/api/v1/ems/collection")
public class EmsCollectionController {

    private final EmsCollectionService emsCollectionService;
    private final EmsPlaylistCurationService emsPlaylistCurationService;
    private final EmsPublicPlaylistDiscoveryScheduler emsPublicPlaylistDiscoveryScheduler;
    private final EmsPoolIngestService emsPoolIngestService;
    private final EmsLooseTrackPlaylistService emsLooseTrackPlaylistService;
    private final FloSpecialCurationScheduler floSpecialCurationScheduler;
    private final FloSpecialProperties floSpecialProperties;
    private final io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebTokenStore tidalWebTokenStore;

    public EmsCollectionController(
        EmsCollectionService emsCollectionService,
        EmsPlaylistCurationService emsPlaylistCurationService,
        EmsPublicPlaylistDiscoveryScheduler emsPublicPlaylistDiscoveryScheduler,
        EmsPoolIngestService emsPoolIngestService,
        EmsLooseTrackPlaylistService emsLooseTrackPlaylistService,
        FloSpecialCurationScheduler floSpecialCurationScheduler,
        FloSpecialProperties floSpecialProperties,
        io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebTokenStore tidalWebTokenStore
    ) {
        this.emsCollectionService = emsCollectionService;
        this.emsPlaylistCurationService = emsPlaylistCurationService;
        this.emsPublicPlaylistDiscoveryScheduler = emsPublicPlaylistDiscoveryScheduler;
        this.emsPoolIngestService = emsPoolIngestService;
        this.emsLooseTrackPlaylistService = emsLooseTrackPlaylistService;
        this.floSpecialCurationScheduler = floSpecialCurationScheduler;
        this.floSpecialProperties = floSpecialProperties;
        this.tidalWebTokenStore = tidalWebTokenStore;
    }

    @Operation(summary = "Search a provider for public playlists and store results in the EMS search pool")
    @PostMapping("/search")
    public EmsCollectionSearchResponse search(@Valid @RequestBody EmsCollectionSearchRequest request) {
        EmsCollectionSearchPreviewResult result = emsCollectionService.previewSearch(
            request.userId(),
            request.platformId(),
            request.query()
        );
        return toSearchResponse(result);
    }

    @Operation(summary = "Queue Spotify homepage Featured Charts into the EMS pool")
    @PostMapping("/spotify-featured-charts/queue")
    public EmsCollectionSearchResponse queueSpotifyFeaturedCharts(@RequestParam("user_id") String userId) {
        EmsCollectionSearchPreviewResult result = emsCollectionService.queueSpotifyFeaturedChartsPool(userId);
        return toSearchResponse(result);
    }

    private EmsCollectionSearchResponse toSearchResponse(EmsCollectionSearchPreviewResult result) {
        return new EmsCollectionSearchResponse(
            "api", "ems_search_pooled", Instant.now(),
            result.platformId(), result.query(),
            result.poolRunId(),
            result.resultPlaylistCount(), result.resultTrackCount(),
            result.playlists().stream()
                .map(playlist -> new EmsCollectionSearchPlaylistItem(
                    playlist.externalPlaylistId(),
                    playlist.title(),
                    playlist.sourcePlatform(),
                    playlist.curator(),
                    playlist.description(),
                    playlist.coverImageUrl(),
                    playlist.platformExternalUrl(),
                    playlist.platformUri(),
                    spotifyUriFor(playlist.sourcePlatform(), playlist.platformUri()),
                    playlist.trackCount()
                ))
                .toList(),
            result.tracks().stream()
                .map(track -> new EmsCollectionSearchTrackItem(
                    track.externalTrackId(),
                    track.title(),
                    track.artistName(),
                    track.sourcePlatform(),
                    track.isrc(),
                    track.albumTitle(),
                    track.albumImageUrl(),
                    track.platformExternalUrl(),
                    track.platformUri(),
                    spotifyUriFor(track.sourcePlatform(), track.platformUri()),
                    track.previewUrl(),
                    track.durationMs()
                ))
                .toList(),
            result.searchedAt()
        );
    }

    @Operation(summary = "List EMS pool ingest runs for the configured admin user")
    @GetMapping("/admin/pool/runs")
    public EmsPoolRunsResponse listPoolRuns(@RequestParam("user_id") String userId) {
        List<EmsPoolRunSnapshot> runs = emsPoolIngestService.listRunsForAdmin(userId);
        return new EmsPoolRunsResponse("api", "ok", Instant.now(), runs.stream().map(EmsPoolRunItem::from).toList());
    }

    @Operation(summary = "Get an EMS pool ingest run with recent entries for the configured admin user")
    @GetMapping("/admin/pool/runs/{runId}")
    public EmsPoolRunDetailResponse getPoolRun(
        @PathVariable Long runId,
        @RequestParam("user_id") String userId
    ) {
        EmsPoolRunDetailSnapshot detail = emsPoolIngestService.getRunForAdmin(userId, runId);
        return new EmsPoolRunDetailResponse(
            "api",
            "ok",
            Instant.now(),
            EmsPoolRunItem.from(detail.run()),
            detail.entries().stream().map(EmsPoolEntryItem::from).toList()
        );
    }

    @Operation(summary = "Retry an EMS pool ingest run for the configured admin user")
    @PostMapping("/admin/pool/runs/{runId}/process")
    public EmsPoolRunCommandResponse processPoolRun(
        @PathVariable Long runId,
        @RequestParam("user_id") String userId
    ) {
        EmsPoolRunSnapshot run = emsPoolIngestService.processRunForAdmin(userId, runId);
        return new EmsPoolRunCommandResponse("api", "ok", Instant.now(), EmsPoolRunItem.from(run));
    }

    @Operation(summary = "Retry a single EMS pool ingest entry for the configured admin user")
    @PostMapping("/admin/pool/runs/{runId}/entries/{entryId}/retry")
    public EmsPoolEntryRetryResponse retryPoolEntry(
        @PathVariable Long runId,
        @PathVariable Long entryId,
        @RequestParam("user_id") String userId
    ) {
        EmsPoolEntrySnapshot entry = emsPoolIngestService.retryEntryForAdmin(userId, runId, entryId);
        return new EmsPoolEntryRetryResponse("api", "ok", Instant.now(), EmsPoolEntryItem.from(entry));
    }

    @Operation(summary = "Delete an EMS pool ingest run for the configured admin user")
    @DeleteMapping("/admin/pool/runs/{runId}")
    public EmsPoolRunDeleteResponse deletePoolRun(
        @PathVariable Long runId,
        @RequestParam("user_id") String userId
    ) {
        emsPoolIngestService.deleteRunForAdmin(userId, runId);
        return new EmsPoolRunDeleteResponse("api", "ok", Instant.now(), runId);
    }

    @Operation(summary = "Delete EMS collected playlists that have no tracks (admin only)")
    @PostMapping("/admin/playlists/cleanup-empty")
    public EmsCollectedPlaylistsCleanupResponse cleanupEmptyCollectedPlaylists(
        @RequestParam("user_id") String userId
    ) {
        int deletedCount = emsPoolIngestService.cleanupEmptyPlaylistsForAdmin(userId);
        return new EmsCollectedPlaylistsCleanupResponse("api", "ok", Instant.now(), deletedCount);
    }

    @Operation(summary = "Materialize loose EMS tracks into synthetic playlists for recommendation candidates")
    @PostMapping("/admin/playlists/materialize-loose-tracks")
    public EmsLooseTrackPlaylistMaterializationResponse materializeLooseTrackPlaylists(
        @RequestParam("user_id") String userId,
        @RequestParam(value = "track_limit", required = false) Integer trackLimit,
        @RequestParam(value = "tracks_per_playlist", required = false) Integer tracksPerPlaylist
    ) {
        emsPoolIngestService.assertAdminAccess(userId);
        LooseTrackPlaylistMaterializationResult result = emsLooseTrackPlaylistService.materializeLooseTracks(
            trackLimit,
            tracksPerPlaylist
        );
        return new EmsLooseTrackPlaylistMaterializationResponse(
            "api",
            "ok",
            Instant.now(),
            result.unassignedTrackCountBefore(),
            result.selectedTrackCount(),
            result.createdPlaylistCount(),
            result.linkedTrackCount(),
            result.unassignedTrackCountAfter(),
            result.materializedAt(),
            result.playlists().stream()
                .map(playlist -> new EmsLooseTrackPlaylistItem(
                    playlist.playlistId(),
                    playlist.title(),
                    playlist.sourcePlatform(),
                    playlist.sourceCollection(),
                    playlist.trackCount()
                ))
                .toList()
        );
    }

    @Operation(summary = "Load tracks for a provider playlist search result and store them in the EMS search pool")
    @GetMapping("/search/playlists/{platformId}/{externalPlaylistId}/tracks")
    public EmsCollectionSearchPlaylistTracksResponse getSearchPlaylistTracks(
        @PathVariable String platformId,
        @PathVariable String externalPlaylistId,
        @RequestParam("user_id") String userId
    ) {
        EmsCollectionSearchPlaylistTracksPreview result = emsCollectionService.getSearchPlaylistTracks(
            userId,
            platformId,
            externalPlaylistId
        );
        return new EmsCollectionSearchPlaylistTracksResponse(
            "api",
            "ok",
            Instant.now(),
            result.platformId(),
            result.externalPlaylistId(),
            result.playlistId(),
            result.trackCount(),
            result.tracks().stream()
                .map(track -> new EmsCollectionSearchTrackItem(
                    track.externalTrackId(),
                    track.title(),
                    track.artistName(),
                    track.sourcePlatform(),
                    track.isrc(),
                    track.albumTitle(),
                    track.albumImageUrl(),
                    track.platformExternalUrl(),
                    track.platformUri(),
                    spotifyUriFor(track.sourcePlatform(), track.platformUri()),
                    track.previewUrl(),
                    track.durationMs()
                ))
                .toList(),
            result.searchedAt()
        );
    }

    @Operation(summary = "Run EMS public playlist discovery immediately")
    @PostMapping("/discovery/run")
    public EmsDiscoveryRunResponse runDiscovery(@RequestBody(required = false) EmsDiscoveryRunRequest request) {
        EmsDiscoveryRunRequest safeRequest = request == null ? new EmsDiscoveryRunRequest(null, null, null, null) : request;
        EmsPublicPlaylistDiscoveryRun run = emsPublicPlaylistDiscoveryScheduler.runNow(
            safeRequest.userId(),
            safeRequest.platforms(),
            safeRequest.seedQueries(),
            safeRequest.perQueryLimit()
        );
        return EmsDiscoveryRunResponse.from("api", run);
    }

    @Operation(summary = "Get the latest EMS public playlist discovery run status")
    @GetMapping("/discovery/status")
    public EmsDiscoveryRunResponse getDiscoveryStatus() {
        EmsPublicPlaylistDiscoveryRun run = emsPublicPlaylistDiscoveryScheduler.lastRun();
        if (run == null) {
            return new EmsDiscoveryRunResponse(
                "api",
                "not_run",
                Instant.now(),
                null,
                null,
                null,
                List.of(),
                List.of(),
                0,
                0,
                0,
                List.of(),
                "EMS public playlist discovery has not run in this API process."
            );
        }
        return EmsDiscoveryRunResponse.from("api", run);
    }

    @Operation(summary = "Set the operator-managed TIDAL web token used to fetch playlist tracks during discovery")
    @PostMapping("/discovery/tidal-web-token")
    public TidalWebTokenStatusResponse setTidalWebToken(@RequestBody TidalWebTokenRequest request) {
        return TidalWebTokenStatusResponse.from(tidalWebTokenStore.save(request.token(), request.userId()));
    }

    @Operation(summary = "Get the status of the operator-managed TIDAL web token (masked)")
    @GetMapping("/discovery/tidal-web-token")
    public TidalWebTokenStatusResponse getTidalWebTokenStatus() {
        return TidalWebTokenStatusResponse.from(tidalWebTokenStore.status());
    }

    public record TidalWebTokenRequest(String token, String userId) {}

    public record TidalWebTokenStatusResponse(
        String service,
        boolean configured,
        String maskedToken,
        String updatedBy,
        Instant updatedAt
    ) {
        static TidalWebTokenStatusResponse from(
            io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebTokenStore.TidalWebTokenStatus status
        ) {
            return new TidalWebTokenStatusResponse(
                "api",
                status.configured(),
                status.maskedToken(),
                status.updatedBy(),
                status.updatedAt()
            );
        }
    }

    @Operation(summary = "Browse collected EMS playlists as personalized genre and mood sections")
    @GetMapping("/playlists/sections")
    public EmsCollectionPlaylistSectionsResponse browsePlaylistSections(
        @RequestParam(value = "user_id", required = false) String userId,
        @RequestParam(value = "platform_id", required = false) List<String> platformIds,
        @RequestParam(value = "limit", defaultValue = "6") int limit
    ) {
        EmsPlaylistCurationResult result = emsPlaylistCurationService.getPlaylistSections(
            userId,
            normalizePlatformIds(platformIds),
            limit
        );
        return new EmsCollectionPlaylistSectionsResponse(
            "api",
            "ok",
            Instant.now(),
            result.userId(),
            result.platformIds(),
            result.titleModel(),
            result.personalized(),
            result.sections().stream().map(this::toPlaylistSection).toList()
        );
    }

    @Operation(summary = "Browse stored FLO special playlist curations for the EMS page")
    @GetMapping("/flo-special")
    public EmsFloSpecialResponse browseFloSpecial(
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        int displayLimit = limit == null ? floSpecialProperties.getDisplayLimit() : limit;
        Map<String, List<EmsCollectedPlaylistEntity>> playlistsBySection = new LinkedHashMap<>();
        for (EmsCollectedPlaylistEntity playlist : emsCollectionService.getFloSpecialPlaylists(displayLimit)) {
            String sectionTitle = firstNonBlank(playlist.getSearchQuery(), "FLO Special");
            playlistsBySection.computeIfAbsent(sectionTitle, ignored -> new ArrayList<>()).add(playlist);
        }

        return new EmsFloSpecialResponse(
            "api",
            "ok",
            Instant.now(),
            "flo",
            EmsCollectionService.FLO_SPECIAL_SOURCE,
            playlistsBySection.entrySet().stream()
                .map(entry -> new EmsFloSpecialSection(
                    "CURATION",
                    entry.getKey(),
                    entry.getValue().stream().map(this::toPlaylistItem).toList()
                ))
                .toList(),
            toFloSpecialRunItem(floSpecialCurationScheduler.lastRun())
        );
    }

    @Operation(summary = "Browse the Melon Hot 100 playlist materialized into EMS")
    @GetMapping("/melon-hot-100")
    public EmsCollectionPlaylistBrowseResponse browseMelonHot100(
        @RequestParam(value = "limit", defaultValue = "1") int limit
    ) {
        List<EmsCollectedPlaylistEntity> playlists = emsCollectionService.getMelonHot100Playlists(limit);
        return new EmsCollectionPlaylistBrowseResponse(
            "api", "ok", Instant.now(), "melon",
            playlists.stream().map(this::toPlaylistItem).toList()
        );
    }

    @Operation(summary = "Refresh FLO special playlists into EMS immediately")
    @PostMapping("/flo-special/refresh")
    public EmsFloSpecialRefreshResponse refreshFloSpecial() {
        return EmsFloSpecialRefreshResponse.from("api", floSpecialCurationScheduler.refreshNow());
    }

    @Operation(summary = "Get latest FLO special refresh status")
    @GetMapping("/flo-special/status")
    public EmsFloSpecialRefreshResponse getFloSpecialStatus() {
        FloSpecialUpdateRun run = floSpecialCurationScheduler.lastRun();
        if (run == null) {
            return new EmsFloSpecialRefreshResponse(
                "api",
                "not_run",
                Instant.now(),
                null,
                null,
                null,
                0,
                0,
                0,
                List.of(),
                "FLO special refresh has not run in this API process."
            );
        }
        return EmsFloSpecialRefreshResponse.from("api", run);
    }

    @Operation(summary = "Browse collected EMS playlists for display")
    @GetMapping("/playlists")
    public EmsCollectionPlaylistBrowseResponse browsePlaylists(
        @RequestParam(value = "platform_id", defaultValue = "spotify") String platformId,
        @RequestParam(value = "limit", defaultValue = "12") int limit,
        @RequestParam(value = "random", defaultValue = "true") boolean random
    ) {
        List<EmsCollectedPlaylistEntity> playlists = emsCollectionService.getCollectedPlaylists(platformId, limit, random);
        return new EmsCollectionPlaylistBrowseResponse(
            "api", "ok", Instant.now(), platformId,
            playlists.stream().map(this::toPlaylistItem).toList()
        );
    }

    @Operation(summary = "Browse stored TIDAL home playlists by source")
    @GetMapping("/tidal-home/playlists")
    public EmsTidalHomePlaylistPageResponse browseTidalHomePlaylists(
        @RequestParam("source_id") String sourceId,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "12") int size
    ) {
        if (!EmsCollectionService.TIDAL_HOME_PAGE_SOURCE_IDS.contains(sourceId)) {
            throw new IllegalArgumentException("Unsupported TIDAL home source: " + sourceId);
        }
        if (page < 0 || size < 1 || size > 12) {
            throw new IllegalArgumentException(
                "TIDAL home page must be >= 0 and size must be between 1 and 12."
            );
        }
        Page<EmsCollectedPlaylistEntity> result =
            emsCollectionService.getTidalHomePlaylists(sourceId, page, size);
        return new EmsTidalHomePlaylistPageResponse(
            "api",
            "ok",
            Instant.now(),
            sourceId,
            result.getNumber(),
            result.getSize(),
            result.getTotalElements(),
            result.getTotalPages(),
            result.getContent().stream().map(this::toPlaylistItem).toList()
        );
    }

    @Operation(summary = "Get a collected EMS playlist with ordered tracks")
    @GetMapping("/playlists/{playlistId}")
    public EmsCollectionPlaylistDetailResponse getPlaylistDetail(@PathVariable Long playlistId) {
        EmsCollectedPlaylistEntity playlist = emsCollectionService.getCollectedPlaylist(playlistId);
        List<EmsCollectedTrackEntity> tracks = emsCollectionService.getTracksForPlaylist(playlistId);
        return new EmsCollectionPlaylistDetailResponse(
            "api", "ok", Instant.now(),
            toPlaylistItem(playlist),
            tracks.stream().map(EmsCollectionController::toTrackItem).toList()
        );
    }

    @Operation(summary = "Backfill audio features for a collected EMS playlist")
    @PostMapping("/playlists/{playlistId}/audio-features/backfill")
    public EmsAudioFeatureBackfillResponse backfillPlaylistAudioFeatures(@PathVariable Long playlistId) {
        EmsAudioFeatureBackfillResult result = emsCollectionService.backfillAudioFeaturesForPlaylist(playlistId);
        return new EmsAudioFeatureBackfillResponse(
            "api",
            "ok",
            Instant.now(),
            result.playlistId(),
            result.playlistTitle(),
            result.sourcePlatform(),
            result.trackCount(),
            result.filledTrackCountBefore(),
            result.pendingTrackCountBefore(),
            result.eligibleTrackCount(),
            result.missingIsrcTrackCount(),
            result.matchedSnapshotCount(),
            result.updatedTrackCount(),
            result.newlyFilledTrackCount(),
            result.filledTrackCountAfter(),
            result.pendingTrackCountAfter(),
            result.coverageRatioAfter(),
            result.backfilledAt()
        );
    }

    @Operation(summary = "Get tracks for a collected EMS playlist")
    @GetMapping("/playlists/{playlistId}/tracks")
    public EmsCollectionTrackBrowseResponse getPlaylistTracks(@PathVariable Long playlistId) {
        List<EmsCollectedTrackEntity> tracks = emsCollectionService.getTracksForPlaylist(playlistId);
        return new EmsCollectionTrackBrowseResponse(
            "api", "ok", Instant.now(), playlistId,
            tracks.stream().map(EmsCollectionController::toTrackItem).toList()
        );
    }

    @Operation(summary = "Browse collected EMS tracks")
    @GetMapping("/tracks")
    public EmsCollectionTrackBrowseResponse browseTracks(
        @RequestParam(value = "platform_id", defaultValue = "spotify") String platformId
    ) {
        List<EmsCollectedTrackEntity> tracks = emsCollectionService.getCollectedTracks(platformId);
        return new EmsCollectionTrackBrowseResponse(
            "api", "ok", Instant.now(), null,
            tracks.stream().map(EmsCollectionController::toTrackItem).toList()
        );
    }

    private EmsCollectionPlaylistSection toPlaylistSection(EmsPlaylistSection section) {
        return new EmsCollectionPlaylistSection(
            section.sectionId(),
            section.title(),
            section.subtitle(),
            section.categoryType(),
            section.categoryLabel(),
            section.displayStyle(),
            section.titleSource(),
            section.playlists().stream().map(this::toPlaylistSectionItem).toList()
        );
    }

    private EmsCollectionPlaylistSectionItem toPlaylistSectionItem(EmsPlaylistSectionItem item) {
        return new EmsCollectionPlaylistSectionItem(
            toPlaylistItem(item.playlist(), item.audioStats()),
            item.matchSignals()
        );
    }

    private EmsCollectionPlaylistItem toPlaylistItem(EmsCollectedPlaylistEntity playlist) {
        EmsAudioFeatureCoverage coverage = emsCollectionService.getAudioFeatureCoverage(playlist.getId());
        return new EmsCollectionPlaylistItem(
            playlist.getId(), playlist.getExternalPlaylistId(), playlist.getTitle(),
            playlist.getSourcePlatform(), playlist.getCurator(), playlist.getDescription(),
            playlist.getCoverImageUrl(), playlist.getPlatformExternalUrl(), playlist.getSpotifyUri(), spotifyUriFor(playlist.getSourcePlatform(), playlist.getSpotifyUri()),
            playlist.getTrackCount(), playlist.getCollectionSource(), playlist.getSearchQuery(),
            playlist.getCollectedAt(),
            new EmsAudioFeatureCoverageItem(
                coverage.trackCount(),
                coverage.filledTrackCount(),
                coverage.pendingTrackCount(),
                coverage.coverageRatio()
            )
        );
    }

    private static EmsFloSpecialRunItem toFloSpecialRunItem(FloSpecialUpdateRun run) {
        if (run == null) {
            return null;
        }
        return new EmsFloSpecialRunItem(
            run.trigger(),
            run.status(),
            run.startedAt(),
            run.completedAt(),
            run.sectionCount(),
            run.collectedPlaylistCount(),
            run.collectedTrackCount(),
            run.failures().stream()
                .map(failure -> new EmsFloSpecialFailureItem(
                    failure.sectionTitle(),
                    failure.externalPlaylistId(),
                    failure.message()
                ))
                .toList(),
            run.message()
        );
    }

    private EmsCollectionPlaylistItem toPlaylistItem(EmsCollectedPlaylistEntity playlist, PlaylistAudioStats audioStats) {
        return new EmsCollectionPlaylistItem(
            playlist.getId(), playlist.getExternalPlaylistId(), playlist.getTitle(),
            playlist.getSourcePlatform(), playlist.getCurator(), playlist.getDescription(),
            playlist.getCoverImageUrl(), playlist.getPlatformExternalUrl(), playlist.getSpotifyUri(), spotifyUriFor(playlist.getSourcePlatform(), playlist.getSpotifyUri()),
            playlist.getTrackCount(), playlist.getCollectionSource(), playlist.getSearchQuery(),
            playlist.getCollectedAt(),
            new EmsAudioFeatureCoverageItem(
                audioStats.trackCount(),
                audioStats.filledTrackCount(),
                audioStats.pendingTrackCount(),
                audioStats.coverageRatio()
            )
        );
    }

    private static EmsCollectionTrackItem toTrackItem(EmsCollectedTrackEntity track) {
        return new EmsCollectionTrackItem(
            track.getId(), track.getExternalTrackId(), track.getTitle(),
            track.getArtistName(), track.getSourcePlatform(), track.getIsrc(), track.getAlbumTitle(),
            track.getAlbumImageUrl(), track.getPlatformExternalUrl(), track.getSpotifyUri(), spotifyUriFor(track.getSourcePlatform(), track.getSpotifyUri()),
            track.getPreviewUrl(), track.getDurationMs(), track.getCollectedAt(),
            toAudioFeatureItem(track)
        );
    }

    private static String spotifyUriFor(String sourcePlatform, String platformUri) {
        return "spotify".equals(sourcePlatform) ? platformUri : null;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static List<String> normalizePlatformIds(List<String> platformIds) {
        if (platformIds == null) {
            return List.of();
        }
        return platformIds.stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
    }

    private static EmsTrackAudioFeatureItem toAudioFeatureItem(EmsCollectedTrackEntity track) {
        if (track.getAudioFeatures() == null) {
            return new EmsTrackAudioFeatureItem(
                null, "unresolved", false, null, null, null, null, null, null, null, null, null, null, null, null
            );
        }
        return new EmsTrackAudioFeatureItem(
            track.getAudioFeatures().getAudioFeatureTrackId(),
            track.getAudioFeatures().getAudioFeatureSource(),
            track.getAudioFeatures().isAudioFeaturesFilled(),
            track.getAudioFeatures().getDurationMs(),
            track.getAudioFeatures().getMusicalKey(),
            track.getAudioFeatures().getMode(),
            track.getAudioFeatures().getAcousticness(),
            track.getAudioFeatures().getDanceability(),
            track.getAudioFeatures().getEnergy(),
            track.getAudioFeatures().getInstrumentalness(),
            track.getAudioFeatures().getLiveness(),
            track.getAudioFeatures().getLoudness(),
            track.getAudioFeatures().getSpeechiness(),
            track.getAudioFeatures().getTempo(),
            track.getAudioFeatures().getValence()
        );
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsCollectionSearchRequest(
        @NotBlank String userId,
        String platformId,
        @NotBlank String query
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsCollectionSearchResponse(
        String service, String status, Instant generatedAt,
        String platformId, String query,
        Long poolRunId,
        int resultPlaylistCount, int resultTrackCount,
        List<EmsCollectionSearchPlaylistItem> playlists,
        List<EmsCollectionSearchTrackItem> tracks,
        Instant searchedAt
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsPoolRunsResponse(
        String service,
        String status,
        Instant generatedAt,
        List<EmsPoolRunItem> runs
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsPoolRunDetailResponse(
        String service,
        String status,
        Instant generatedAt,
        EmsPoolRunItem run,
        List<EmsPoolEntryItem> entries
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsPoolRunCommandResponse(
        String service,
        String status,
        Instant generatedAt,
        EmsPoolRunItem run
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsPoolEntryRetryResponse(
        String service,
        String status,
        Instant generatedAt,
        EmsPoolEntryItem entry
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsPoolRunDeleteResponse(
        String service,
        String status,
        Instant generatedAt,
        Long runId
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsCollectedPlaylistsCleanupResponse(
        String service,
        String status,
        Instant generatedAt,
        int deletedCount
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsLooseTrackPlaylistMaterializationResponse(
        String service,
        String status,
        Instant generatedAt,
        long unassignedTrackCountBefore,
        int selectedTrackCount,
        int createdPlaylistCount,
        int linkedTrackCount,
        long unassignedTrackCountAfter,
        Instant materializedAt,
        List<EmsLooseTrackPlaylistItem> playlists
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsLooseTrackPlaylistItem(
        Long playlistId,
        String title,
        String sourcePlatform,
        String sourceCollection,
        int trackCount
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsPoolRunItem(
        Long runId,
        String requestedByUserId,
        String sourcePlatform,
        String searchQuery,
        String collectionSource,
        String status,
        int totalPlaylistEntries,
        int totalTrackEntries,
        int processedPlaylistEntries,
        int processedTrackEntries,
        int failedEntries,
        int collectedPlaylistCount,
        int collectedTrackCount,
        double progressRatio,
        String lastError,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt
    ) {
        static EmsPoolRunItem from(EmsPoolRunSnapshot run) {
            return new EmsPoolRunItem(
                run.runId(),
                run.requestedByUserId(),
                run.sourcePlatform(),
                run.searchQuery(),
                run.collectionSource(),
                run.status(),
                run.totalPlaylistEntries(),
                run.totalTrackEntries(),
                run.processedPlaylistEntries(),
                run.processedTrackEntries(),
                run.failedEntries(),
                run.collectedPlaylistCount(),
                run.collectedTrackCount(),
                run.progressRatio(),
                run.lastError(),
                run.createdAt(),
                run.startedAt(),
                run.completedAt(),
                run.updatedAt()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsPoolEntryItem(
        Long entryId,
        String entryType,
        String sourcePlatform,
        String externalId,
        String title,
        String artistName,
        String status,
        int attempts,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant processedAt
    ) {
        static EmsPoolEntryItem from(EmsPoolEntrySnapshot entry) {
            return new EmsPoolEntryItem(
                entry.entryId(),
                entry.entryType(),
                entry.sourcePlatform(),
                entry.externalId(),
                entry.title(),
                entry.artistName(),
                entry.status(),
                entry.attempts(),
                entry.lastError(),
                entry.createdAt(),
                entry.updatedAt(),
                entry.processedAt()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsCollectionSearchPlaylistItem(
        String externalPlaylistId,
        String title,
        String sourcePlatform,
        String curator,
        String description,
        String coverImageUrl,
        String platformExternalUrl,
        String platformUri,
        String spotifyUri,
        int trackCount
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsCollectionSearchTrackItem(
        String externalTrackId,
        String title,
        String artistName,
        String sourcePlatform,
        String isrc,
        String albumTitle,
        String albumImageUrl,
        String platformExternalUrl,
        String platformUri,
        String spotifyUri,
        String previewUrl,
        Integer durationMs
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsCollectionSearchPlaylistTracksResponse(
        String service,
        String status,
        Instant generatedAt,
        String platformId,
        String externalPlaylistId,
        Long playlistId,
        int trackCount,
        List<EmsCollectionSearchTrackItem> tracks,
        Instant searchedAt
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsDiscoveryRunRequest(
        String userId,
        List<String> platforms,
        List<String> seedQueries,
        Integer perQueryLimit
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsDiscoveryRunResponse(
        String service,
        String status,
        Instant generatedAt,
        String trigger,
        Instant startedAt,
        Instant completedAt,
        List<String> platforms,
        List<String> seedQueries,
        int perQueryLimit,
        int collectedPlaylistCount,
        int collectedTrackCount,
        List<EmsDiscoveryFailureItem> failures,
        String message
    ) {
        static EmsDiscoveryRunResponse from(String service, EmsPublicPlaylistDiscoveryRun run) {
            return new EmsDiscoveryRunResponse(
                service,
                run.status(),
                Instant.now(),
                run.trigger(),
                run.startedAt(),
                run.completedAt(),
                run.platforms(),
                run.seedQueries(),
                run.perQueryLimit(),
                run.collectedPlaylistCount(),
                run.collectedTrackCount(),
                run.failures().stream()
                    .map(failure -> new EmsDiscoveryFailureItem(
                        failure.platformId(),
                        failure.query(),
                        failure.message()
                    ))
                    .toList(),
                run.message()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsDiscoveryFailureItem(
        String platformId,
        String query,
        String message
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsCollectionPlaylistBrowseResponse(
        String service, String status, Instant generatedAt,
        String platformId,
        List<EmsCollectionPlaylistItem> playlists
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsTidalHomePlaylistPageResponse(
        String service,
        String status,
        Instant generatedAt,
        String sourceId,
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<EmsCollectionPlaylistItem> playlists
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsCollectionPlaylistSectionsResponse(
        String service,
        String status,
        Instant generatedAt,
        String userId,
        List<String> platformIds,
        String titleModel,
        boolean personalized,
        List<EmsCollectionPlaylistSection> sections
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsFloSpecialResponse(
        String service,
        String status,
        Instant generatedAt,
        String sourcePlatform,
        String collectionSource,
        List<EmsFloSpecialSection> sections,
        EmsFloSpecialRunItem lastRun
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsFloSpecialRefreshResponse(
        String service,
        String status,
        Instant generatedAt,
        String trigger,
        Instant startedAt,
        Instant completedAt,
        int sectionCount,
        int collectedPlaylistCount,
        int collectedTrackCount,
        List<EmsFloSpecialFailureItem> failures,
        String message
    ) {
        static EmsFloSpecialRefreshResponse from(String service, FloSpecialUpdateRun run) {
            return new EmsFloSpecialRefreshResponse(
                service,
                run.status(),
                Instant.now(),
                run.trigger(),
                run.startedAt(),
                run.completedAt(),
                run.sectionCount(),
                run.collectedPlaylistCount(),
                run.collectedTrackCount(),
                run.failures().stream()
                    .map(failure -> new EmsFloSpecialFailureItem(
                        failure.sectionTitle(),
                        failure.externalPlaylistId(),
                        failure.message()
                    ))
                    .toList(),
                run.message()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsFloSpecialRunItem(
        String trigger,
        String status,
        Instant startedAt,
        Instant completedAt,
        int sectionCount,
        int collectedPlaylistCount,
        int collectedTrackCount,
        List<EmsFloSpecialFailureItem> failures,
        String message
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsFloSpecialFailureItem(
        String sectionTitle,
        String externalPlaylistId,
        String message
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsFloSpecialSection(
        String sourceType,
        String title,
        List<EmsCollectionPlaylistItem> playlists
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsCollectionPlaylistSection(
        String sectionId,
        String title,
        String subtitle,
        String categoryType,
        String categoryLabel,
        String displayStyle,
        String titleSource,
        List<EmsCollectionPlaylistSectionItem> playlists
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsCollectionPlaylistSectionItem(
        EmsCollectionPlaylistItem playlist,
        List<String> matchSignals
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsCollectionPlaylistDetailResponse(
        String service, String status, Instant generatedAt,
        EmsCollectionPlaylistItem playlist,
        List<EmsCollectionTrackItem> tracks
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsCollectionPlaylistItem(
        Long id, String externalPlaylistId, String title,
        String sourcePlatform, String curator, String description,
        String coverImageUrl, String platformExternalUrl, String platformUri, String spotifyUri,
        int trackCount, String collectionSource, String searchQuery,
        Instant collectedAt,
        EmsAudioFeatureCoverageItem audioFeatureCoverage
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsAudioFeatureCoverageItem(
        long trackCount,
        long filledTrackCount,
        long pendingTrackCount,
        double coverageRatio
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsAudioFeatureBackfillResponse(
        String service,
        String status,
        Instant generatedAt,
        Long playlistId,
        String playlistTitle,
        String sourcePlatform,
        long trackCount,
        long filledTrackCountBefore,
        long pendingTrackCountBefore,
        int eligibleTrackCount,
        int missingIsrcTrackCount,
        int matchedSnapshotCount,
        int updatedTrackCount,
        int newlyFilledTrackCount,
        long filledTrackCountAfter,
        long pendingTrackCountAfter,
        double coverageRatioAfter,
        Instant backfilledAt
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsCollectionTrackBrowseResponse(
        String service, String status, Instant generatedAt,
        Long playlistId,
        List<EmsCollectionTrackItem> tracks
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsCollectionTrackItem(
        Long id, String externalTrackId, String title,
        String artistName, String sourcePlatform, String isrc, String albumTitle,
        String albumImageUrl, String platformExternalUrl, String platformUri, String spotifyUri,
        String previewUrl, Integer durationMs, Instant collectedAt,
        EmsTrackAudioFeatureItem audioFeatures
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmsTrackAudioFeatureItem(
        String audioFeatureTrackId,
        String audioFeatureSource,
        boolean audioFeaturesFilled,
        Integer durationMs,
        Integer musicalKey,
        Integer mode,
        Double acousticness,
        Double danceability,
        Double energy,
        Double instrumentalness,
        Double liveness,
        Double loudness,
        Double speechiness,
        Double tempo,
        Double valence
    ) {}
}
