package io.myforevermusic.api.modules.ems.application;

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
import io.myforevermusic.api.common.error.ApiResourceNotFoundException;
import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
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
import io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistSummary;
import io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalPlaylistTrack;
import io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient.TidalSearchResult;
import io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionAutoEnqueueService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.context.annotation.Profile("!local")
@Service
public class EmsCollectionService {

    private static final Logger log = LoggerFactory.getLogger(EmsCollectionService.class);
    private static final String SEARCH_POOL_SOURCE = "search_pool";
    public static final String USER_TIDAL_URL_IMPORT_SOURCE = "user_tidal_url_import";
    public static final String FLO_SPECIAL_SOURCE = "flo_special";
    public static final String MELON_HOT_100_SOURCE = "melon_hot_100";
    private static final String MELON_HOT_100_PLAYLIST_ID = "melon-hot-100";
    private static final List<String> TIDAL_HOME_PAGE_SOURCE_IDS = List.of(
        "THE_HITS",
        "POPULAR_MIXES",
        "POPULAR_PLAYLISTS",
        "FROM_OUR_EDITORS"
    );

    private final SpotifyWebApiClient spotifyWebApiClient;
    private final TidalWebApiClient tidalWebApiClient;
    private final ReccoBeatsAudioFeaturesClient reccoBeatsAudioFeaturesClient;
    private final PlatformCredentialService platformCredentialService;
    private final AuthAccountStore authAccountStore;
    private final EmsCollectedPlaylistRepository playlistRepository;
    private final EmsCollectedTrackRepository trackRepository;
    private final EmsCollectedPlaylistTrackRepository playlistTrackRepository;
    private final EmsPoolIngestRunRepository poolRunRepository;
    private final EmsPoolEntryRepository poolEntryRepository;
    private final FloSpecialCurationService floSpecialCurationService;
    private final ApplicationEventPublisher eventPublisher;
    private final Optional<AudioFeatureCompletionAutoEnqueueService> audioFeatureCompletionAutoEnqueueService;

    public EmsCollectionService(
        SpotifyWebApiClient spotifyWebApiClient,
        TidalWebApiClient tidalWebApiClient,
        ReccoBeatsAudioFeaturesClient reccoBeatsAudioFeaturesClient,
        PlatformCredentialService platformCredentialService,
        AuthAccountStore authAccountStore,
        EmsCollectedPlaylistRepository playlistRepository,
        EmsCollectedTrackRepository trackRepository,
        EmsCollectedPlaylistTrackRepository playlistTrackRepository,
        EmsPoolIngestRunRepository poolRunRepository,
        EmsPoolEntryRepository poolEntryRepository,
        FloSpecialCurationService floSpecialCurationService,
        ApplicationEventPublisher eventPublisher,
        Optional<AudioFeatureCompletionAutoEnqueueService> audioFeatureCompletionAutoEnqueueService
    ) {
        this.spotifyWebApiClient = spotifyWebApiClient;
        this.tidalWebApiClient = tidalWebApiClient;
        this.reccoBeatsAudioFeaturesClient = reccoBeatsAudioFeaturesClient;
        this.platformCredentialService = platformCredentialService;
        this.authAccountStore = authAccountStore;
        this.playlistRepository = playlistRepository;
        this.trackRepository = trackRepository;
        this.playlistTrackRepository = playlistTrackRepository;
        this.poolRunRepository = poolRunRepository;
        this.poolEntryRepository = poolEntryRepository;
        this.floSpecialCurationService = floSpecialCurationService;
        this.eventPublisher = eventPublisher;
        this.audioFeatureCompletionAutoEnqueueService = audioFeatureCompletionAutoEnqueueService;
    }

    @Transactional
    public EmsCollectionSearchPreviewResult previewSearch(String userId, String platformId, String query) {
        return queueProviderSearchPool(userId, platformId, query, SEARCH_POOL_SOURCE, null);
    }

    @Transactional
    public EmsCollectionSearchPreviewResult queueAcquisitionSearchPool(
        String userId,
        String platformId,
        String query,
        int limit
    ) {
        return queueProviderSearchPool(userId, platformId, query, "acquisition_pool", limit);
    }

    private EmsCollectionSearchPreviewResult queueProviderSearchPool(
        String userId,
        String platformId,
        String query,
        String collectionSource,
        Integer limitOverride
    ) {
        List<EmsCollectionSearchPlaylistPreview> playlists = new ArrayList<>();
        List<EmsCollectionSearchTrackPreview> tracks = new ArrayList<>();
        int totalPlaylistCount = 0;
        int totalTrackCount = 0;
        Instant searchedAt = Instant.now();

        String requestedPlatformId = resolveSearchPlatformId(userId, platformId);
        PlatformAccountCredential credential = platformCredentialService
            .findUsableCredential(userId, requestedPlatformId)
            .orElse(null);
        if (credential == null) {
            throw new IllegalArgumentException(
                "Connect %s before searching EMS public playlists.".formatted(requestedPlatformId)
            );
        }

        SearchTotals totals;
        if ("spotify".equals(requestedPlatformId)) {
            totals = appendSpotifySearchResults(credential, query, playlists, tracks, limitOverride);
        } else if ("tidal".equals(requestedPlatformId)) {
            totals = appendTidalSearchResults(credential, query, playlists, tracks, limitOverride);
        } else {
            throw new IllegalArgumentException("Unsupported EMS search platform: %s".formatted(requestedPlatformId));
        }
        totalPlaylistCount += totals.playlistCount();
        totalTrackCount += totals.trackCount();

        EmsPoolIngestRunEntity poolRun = queueSearchPoolCandidates(
            userId,
            requestedPlatformId,
            query,
            collectionSource,
            playlists,
            tracks,
            searchedAt
        );

        return new EmsCollectionSearchPreviewResult(
            requestedPlatformId,
            query,
            poolRun.getId(),
            playlists,
            tracks,
            totalPlaylistCount,
            totalTrackCount,
            searchedAt
        );
    }

    @Transactional
    public EmsCollectionSearchPlaylistTracksPreview getSearchPlaylistTracks(
        String userId,
        String platformId,
        String externalPlaylistId
    ) {
        Instant searchedAt = Instant.now();
        EmsCollectedPlaylistEntity playlist = ensureCollectedPlaylistShell(platformId, externalPlaylistId, searchedAt);
        return fetchAndStoreSearchPlaylistTracks(
            userId,
            playlist,
            platformId,
            externalPlaylistId,
            SEARCH_POOL_SOURCE,
            searchedAt
        );
    }

    private EmsCollectionSearchPlaylistTracksPreview fetchAndStoreSearchPlaylistTracks(
        String userId,
        EmsCollectedPlaylistEntity playlist,
        String platformId,
        String externalPlaylistId,
        String collectionSource,
        Instant collectedAt
    ) {
        PlatformAccountCredential credential = platformCredentialService
            .findUsableCredential(userId, platformId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Connect %s before loading EMS search playlist tracks.".formatted(platformId)
            ));

        List<EmsCollectionSearchTrackPreview> tracks;
        if ("spotify".equals(platformId)) {
            tracks = spotifyWebApiClient.getPlaylistTracks(credential, externalPlaylistId)
                .stream()
                .map(this::toSpotifySearchTrackPreview)
                .toList();
        } else if ("tidal".equals(platformId)) {
            tracks = tidalWebApiClient.getPlaylistTracks(credential, externalPlaylistId)
                .stream()
                .map(this::toTidalSearchTrackPreview)
                .toList();
        } else {
            throw new IllegalArgumentException("Unsupported EMS search platform: %s".formatted(platformId));
        }

        storeSearchPlaylistTracks(playlist, tracks, collectionSource, collectedAt);
        discardPlaylistIfEmpty(playlist);
        return new EmsCollectionSearchPlaylistTracksPreview(platformId, externalPlaylistId, tracks, tracks.size(), collectedAt);
    }

    private void discardPlaylistIfEmpty(EmsCollectedPlaylistEntity playlist) {
        if (playlist == null || playlist.getId() == null) {
            return;
        }
        long linkCount = playlistTrackRepository.countTracksByPlaylistId(playlist.getId());
        if (linkCount > 0) {
            return;
        }
        playlistRepository.delete(playlist);
        log.info(
            "EMS collected playlist {} ({}:{}) discarded because no tracks were stored.",
            playlist.getId(),
            playlist.getSourcePlatform(),
            playlist.getExternalPlaylistId()
        );
    }

    @Transactional
    public int cleanupEmptyCollectedPlaylists() {
        int deleted = playlistRepository.deletePlaylistsWithoutTracks();
        if (deleted > 0) {
            log.info("EMS cleanup removed {} collected playlists without tracks.", deleted);
        }
        return deleted;
    }

    private EmsPoolIngestRunEntity queueSearchPoolCandidates(
        String userId,
        String platformId,
        String query,
        String collectionSource,
        List<EmsCollectionSearchPlaylistPreview> playlists,
        List<EmsCollectionSearchTrackPreview> tracks,
        Instant queuedAt
    ) {
        EmsPoolIngestRunEntity run = poolRunRepository.save(new EmsPoolIngestRunEntity(
            truncate(userId, 100),
            truncate(platformId, 50),
            normalizeRequiredText(query, "search", 200),
            truncate(collectionSource, 50),
            playlists.size(),
            tracks.size(),
            queuedAt
        ));

        Map<String, EmsPoolEntryEntity> entriesByKey = new LinkedHashMap<>();
        for (EmsCollectionSearchPlaylistPreview playlist : playlists) {
            entriesByKey.putIfAbsent(
                "playlist:%s:%s".formatted(playlist.sourcePlatform(), playlist.externalPlaylistId()),
                new EmsPoolEntryEntity(
                    run,
                    EmsPoolEntryEntity.TYPE_PLAYLIST,
                    normalizeRequiredText(playlist.sourcePlatform(), platformId, 50),
                    normalizeRequiredText(playlist.externalPlaylistId(), "unknown-playlist", 160),
                    normalizeRequiredText(playlist.title(), "Untitled EMS Playlist", 200),
                    null,
                    normalizeOptionalText(playlist.curator(), 120),
                    normalizeDescription(playlist.description()),
                    truncate(playlist.coverImageUrl(), 500),
                    truncate(playlist.platformExternalUrl(), 500),
                    truncate(playlist.platformUri(), 200),
                    null,
                    null,
                    null,
                    null,
                    null,
                    playlist.trackCount(),
                    queuedAt
                )
            );
        }
        for (EmsCollectionSearchTrackPreview track : tracks) {
            entriesByKey.putIfAbsent(
                "track:%s:%s".formatted(track.sourcePlatform(), track.externalTrackId()),
                new EmsPoolEntryEntity(
                    run,
                    EmsPoolEntryEntity.TYPE_TRACK,
                    normalizeRequiredText(track.sourcePlatform(), platformId, 50),
                    normalizeRequiredText(track.externalTrackId(), "unknown-track", 160),
                    normalizeRequiredText(track.title(), "Untitled EMS Track", 200),
                    normalizeRequiredText(track.artistName(), "Unknown Artist", 200),
                    null,
                    null,
                    null,
                    truncate(track.platformExternalUrl(), 500),
                    truncate(track.platformUri(), 200),
                    truncate(track.isrc(), 32),
                    truncate(track.albumTitle(), 200),
                    truncate(track.albumImageUrl(), 500),
                    truncate(track.previewUrl(), 500),
                    track.durationMs(),
                    0,
                    queuedAt
                )
            );
        }
        poolEntryRepository.saveAll(entriesByKey.values());
        eventPublisher.publishEvent(new EmsPoolRunQueuedEvent(run.getId()));
        return run;
    }

    @Transactional
    public EmsSearchPoolCollectionResult collectSearchPoolEntry(String userId, EmsPoolEntryEntity entry) {
        Instant collectedAt = Instant.now();
        if (EmsPoolEntryEntity.TYPE_PLAYLIST.equals(entry.getEntryType())) {
            EmsCollectionSearchPlaylistPreview playlistPreview = new EmsCollectionSearchPlaylistPreview(
                entry.getExternalId(),
                entry.getTitle(),
                entry.getSourcePlatform(),
                entry.getCurator(),
                entry.getDescription(),
                entry.getCoverImageUrl(),
                entry.getPlatformExternalUrl(),
                entry.getPlatformUri(),
                entry.getTrackCount()
            );
            EmsCollectedPlaylistEntity playlist = upsertPlaylistFromSearchPreview(
                playlistPreview, entry.getRun().getSearchQuery(), entry.getRun().getCollectionSource(), collectedAt
            );
            EmsCollectionSearchPlaylistTracksPreview tracks = fetchAndStoreSearchPlaylistTracks(
                userId,
                playlist,
                entry.getSourcePlatform(),
                entry.getExternalId(),
                entry.getRun().getCollectionSource(),
                collectedAt
            );
            return new EmsSearchPoolCollectionResult(1, tracks.trackCount());
        }

        if (EmsPoolEntryEntity.TYPE_TRACK.equals(entry.getEntryType())) {
            EmsCollectionSearchTrackPreview track = new EmsCollectionSearchTrackPreview(
                entry.getExternalId(),
                entry.getTitle(),
                entry.getArtistName(),
                entry.getSourcePlatform(),
                entry.getIsrc(),
                entry.getAlbumTitle(),
                entry.getAlbumImageUrl(),
                entry.getPlatformExternalUrl(),
                entry.getPlatformUri(),
                entry.getPreviewUrl(),
                entry.getDurationMs()
            );
            upsertTrackFromSearchPreview(track, entry.getRun().getCollectionSource(), collectedAt);
            return new EmsSearchPoolCollectionResult(0, 1);
        }

        throw new IllegalArgumentException("Unsupported EMS pool entry type: %s".formatted(entry.getEntryType()));
    }

    @Transactional
    public EmsTidalPlaylistUrlImportCollection collectTidalPlaylistFromUrlImport(String userId, String playlistId) {
        Instant collectedAt = Instant.now();
        PlatformAccountCredential credential = platformCredentialService
            .findUsableCredential(userId, "tidal")
            .orElseThrow(() -> new IllegalArgumentException(
                "Connect TIDAL before importing a TIDAL playlist URL into GMS."
            ));

        TidalPlaylistSummary playlist = tidalWebApiClient.getPlaylist(credential, playlistId);
        List<TidalPlaylistTrack> tracks = tidalWebApiClient.getPlaylistTracks(credential, playlistId);
        if (tracks.isEmpty()) {
            throw new IllegalArgumentException(
                "TIDAL playlist does not contain importable tracks: %s".formatted(playlistId)
            );
        }

        EmsCollectedPlaylistEntity playlistEntity = upsertPlaylistFromTidal(
            playlist,
            fallbackPlaylistCoverImageUrl(playlist, tracks),
            USER_TIDAL_URL_IMPORT_SOURCE,
            playlistId,
            collectedAt
        );
        playlistTrackRepository.deleteByPlaylistId(playlistEntity.getId());
        Map<String, ReccoBeatsAudioFeaturesSnapshot> audioFeaturesByTrackId = resolveTidalAudioFeatures(tracks);
        Instant resolvedAt = Instant.now();
        for (int i = 0; i < tracks.size(); i++) {
            TidalPlaylistTrack track = tracks.get(i);
            EmsCollectedTrackEntity trackEntity = upsertTrackFromTidal(
                track,
                USER_TIDAL_URL_IMPORT_SOURCE,
                collectedAt,
                resolveTidalTrackAudioFeatures(track, audioFeaturesByTrackId.get(track.tidalTrackId()), resolvedAt)
            );
            linkPlaylistTrack(playlistEntity, trackEntity, i);
        }

        return new EmsTidalPlaylistUrlImportCollection(
            playlistEntity.getId(),
            playlist.playlistId(),
            "tidal",
            playlistEntity.getTitle(),
            tracks.size(),
            USER_TIDAL_URL_IMPORT_SOURCE,
            collectedAt
        );
    }

    private void storeSearchPlaylistTracks(
        EmsCollectedPlaylistEntity playlist,
        List<EmsCollectionSearchTrackPreview> tracks,
        String collectionSource,
        Instant collectedAt
    ) {
        if (playlist == null) {
            throw new IllegalStateException(
                "EMS collected playlist must be persisted before linking search tracks."
            );
        }
        for (int i = 0; i < tracks.size(); i++) {
            EmsCollectedTrackEntity track = upsertTrackFromSearchPreview(tracks.get(i), collectionSource, collectedAt);
            linkPlaylistTrack(playlist, track, i);
        }
    }

    private EmsCollectedPlaylistEntity ensureCollectedPlaylistShell(
        String platformId,
        String externalPlaylistId,
        Instant now
    ) {
        return playlistRepository.findBySourcePlatformAndExternalPlaylistId(platformId, externalPlaylistId)
            .orElseGet(() -> playlistRepository.save(new EmsCollectedPlaylistEntity(
                truncate(externalPlaylistId, 160),
                "Untitled EMS Playlist",
                truncate(platformId, 50),
                "",
                "",
                null,
                null,
                null,
                0,
                SEARCH_POOL_SOURCE,
                null,
                now
            )));
    }

    private String resolveSearchPlatformId(String userId, String platformId) {
        if (hasText(platformId) && !"all".equals(platformId)) {
            return platformId;
        }
        return authAccountStore.findByUserId(userId)
            .map(account -> account.preferredPlatformId())
            .filter(this::hasText)
            .orElseThrow(() -> new IllegalArgumentException(
                "A registered user with a preferred provider is required before searching EMS public playlists."
            ));
    }

    private SearchTotals appendSpotifySearchResults(
        PlatformAccountCredential credential,
        String query,
        List<EmsCollectionSearchPlaylistPreview> playlists,
        List<EmsCollectionSearchTrackPreview> tracks,
        Integer limitOverride
    ) {
        log.info("EMS search preview: calling Spotify search only query='{}'", query);
        SpotifySearchResult<SpotifyPlaylistSummary> playlistResults = limitOverride == null
            ? spotifyWebApiClient.searchPlaylists(credential, query)
            : spotifyWebApiClient.searchPlaylists(credential, query, limitOverride);
        playlists.addAll(playlistResults.items().stream()
            .map(playlist -> new EmsCollectionSearchPlaylistPreview(
                playlist.playlistId(),
                playlist.name(),
                "spotify",
                playlist.ownerDisplayName(),
                playlist.description(),
                playlist.coverImageUrl(),
                playlist.externalUrl(),
                playlist.spotifyUri(),
                playlist.trackCount()
            ))
            .toList());
        SpotifySearchResult<SpotifyPlaylistTrack> trackResults = limitOverride == null
            ? spotifyWebApiClient.searchTracks(credential, query)
            : spotifyWebApiClient.searchTracks(credential, query, limitOverride);
        tracks.addAll(trackResults.items().stream()
            .map(this::toSpotifySearchTrackPreview)
            .toList());
        return new SearchTotals(
            Math.max(playlistResults.total(), playlistResults.items().size()),
            Math.max(trackResults.total(), trackResults.items().size())
        );
    }

    private SearchTotals appendTidalSearchResults(
        PlatformAccountCredential credential,
        String query,
        List<EmsCollectionSearchPlaylistPreview> playlists,
        List<EmsCollectionSearchTrackPreview> tracks,
        Integer limitOverride
    ) {
        log.info("EMS search preview: calling TIDAL search only query='{}'", query);
        TidalSearchResult<TidalPlaylistSummary> playlistResults = limitOverride == null
            ? tidalWebApiClient.searchPlaylistResults(credential, query)
            : limitedTidalPlaylistSearch(credential, query, limitOverride);
        playlists.addAll(playlistResults.items().stream()
            .map(playlist -> new EmsCollectionSearchPlaylistPreview(
                playlist.playlistId(),
                playlist.name(),
                "tidal",
                "",
                playlist.description(),
                playlist.coverImageUrl(),
                playlist.externalUrl(),
                null,
                playlist.trackCount()
            ))
            .toList());
        TidalSearchResult<TidalPlaylistTrack> trackResults = limitOverride == null
            ? tidalWebApiClient.searchTrackResults(credential, query)
            : limitedTidalTrackSearch(credential, query, limitOverride);
        tracks.addAll(trackResults.items().stream()
            .map(this::toTidalSearchTrackPreview)
            .toList());
        return new SearchTotals(
            Math.max(playlistResults.total(), playlistResults.items().size()),
            Math.max(trackResults.total(), trackResults.items().size())
        );
    }

    private TidalSearchResult<TidalPlaylistSummary> limitedTidalPlaylistSearch(
        PlatformAccountCredential credential,
        String query,
        int limit
    ) {
        List<TidalPlaylistSummary> items = tidalWebApiClient.searchPlaylists(credential, query, limit);
        return new TidalSearchResult<>(items, items.size());
    }

    private TidalSearchResult<TidalPlaylistTrack> limitedTidalTrackSearch(
        PlatformAccountCredential credential,
        String query,
        int limit
    ) {
        List<TidalPlaylistTrack> items = tidalWebApiClient.searchTracks(credential, query, limit);
        return new TidalSearchResult<>(items, items.size());
    }

    private EmsCollectionSearchTrackPreview toSpotifySearchTrackPreview(SpotifyPlaylistTrack track) {
        return new EmsCollectionSearchTrackPreview(
            track.spotifyTrackId(),
            track.title(),
            track.artistName(),
            "spotify",
            track.isrc(),
            track.albumTitle(),
            track.albumImageUrl(),
            track.externalUrl(),
            track.spotifyUri(),
            track.previewUrl(),
            track.durationMs()
        );
    }

    private EmsCollectionSearchTrackPreview toTidalSearchTrackPreview(TidalPlaylistTrack track) {
        return new EmsCollectionSearchTrackPreview(
            track.tidalTrackId(),
            track.title(),
            track.artistName(),
            "tidal",
            track.isrc(),
            track.albumTitle(),
            track.albumImageUrl(),
            track.externalUrl(),
            track.tidalUri(),
            track.previewUrl(),
            track.durationMs() > 0 ? track.durationMs() : null
        );
    }

    @Transactional
    public EmsCollectionSearchResult collectPublicPlaylistPool(String userId, String platformId, String query, int limit) {
        return collectFromProvider(userId, platformId, query, limit, "public_pool");
    }

    @Transactional
    public FloSpecialCollectionResult collectFloSpecial() {
        Instant now = Instant.now();
        FloSpecialCurationService.FloSpecialCuration curation = floSpecialCurationService.getSpecial(null);
        int collectedPlaylistCount = 0;
        int collectedTrackCount = 0;
        List<FloSpecialCollectionFailure> failures = new ArrayList<>();

        for (FloSpecialCurationService.FloSpecialSection section : curation.sections()) {
            for (FloSpecialCurationService.FloSpecialPlaylist playlist : section.playlists()) {
                try {
                    FloSpecialCurationService.FloSpecialPlaylistTracks playlistTracks =
                        floSpecialCurationService.getTracks(playlist);
                    EmsCollectedPlaylistEntity playlistEntity = upsertPlaylistFromFlo(
                        playlist,
                        section,
                        playlistTracks.trackCount(),
                        now
                    );
                    collectedPlaylistCount++;
                    playlistTrackRepository.deleteByPlaylistId(playlistEntity.getId());

                    List<FloSpecialCurationService.FloSpecialTrack> tracks = playlistTracks.tracks();
                    for (int i = 0; i < tracks.size(); i++) {
                        EmsCollectedTrackEntity trackEntity = upsertTrackFromFlo(tracks.get(i), now);
                        linkPlaylistTrack(playlistEntity, trackEntity, i);
                        collectedTrackCount++;
                    }
                } catch (RuntimeException exception) {
                    failures.add(new FloSpecialCollectionFailure(
                        section.title(),
                        playlist.externalPlaylistId(),
                        exception.getMessage()
                    ));
                    log.warn(
                        "FLO special collection failed for section='{}' playlist={}: {}",
                        section.title(),
                        playlist.externalPlaylistId(),
                        exception.getMessage()
                    );
                }
            }
        }

        return new FloSpecialCollectionResult(
            curation.sections().size(),
            collectedPlaylistCount,
            collectedTrackCount,
            now,
            List.copyOf(failures)
        );
    }

    @Transactional
    public MelonHot100CollectionResult collectMelonHot100(List<MelonHot100TrackSeed> tracks, Instant snapshotAt) {
        List<MelonHot100TrackSeed> validTracks = tracks == null
            ? List.of()
            : tracks.stream()
                .filter(track -> track != null && hasText(track.title()) && hasText(track.artistName()))
                .sorted(java.util.Comparator.comparingInt(MelonHot100TrackSeed::rank))
                .limit(100)
                .toList();
        if (validTracks.isEmpty()) {
            return new MelonHot100CollectionResult(null, 0, 0, Instant.now());
        }

        Instant now = Instant.now();
        Instant effectiveSnapshotAt = snapshotAt == null ? now : snapshotAt;
        EmsCollectedPlaylistEntity playlistEntity = upsertPlaylistFromMelonHot100(
            validTracks,
            effectiveSnapshotAt,
            now
        );
        playlistTrackRepository.deleteByPlaylistId(playlistEntity.getId());

        int linkedTrackCount = 0;
        for (int i = 0; i < validTracks.size(); i++) {
            EmsCollectedTrackEntity trackEntity = upsertTrackFromMelonHot100(validTracks.get(i), now);
            linkPlaylistTrack(playlistEntity, trackEntity, i);
            linkedTrackCount++;
        }

        return new MelonHot100CollectionResult(playlistEntity.getId(), 1, linkedTrackCount, now);
    }

    private EmsCollectionSearchResult collectFromProvider(
        String userId,
        String platformId,
        String query,
        int limit,
        String collectionSource
    ) {
        PlatformAccountCredential credential = platformCredentialService
            .findUsableCredential(userId, platformId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Connect %s before collecting EMS public playlists.".formatted(platformId)
            ));

        Instant now = Instant.now();
        int collectedPlaylistCount = 0;
        int collectedTrackCount = 0;

        if ("spotify".equals(platformId)) {
            SpotifyPlaylistSource spotifySource = spotifyPlaylistSource(query);
            log.info(
                "EMS collection: calling Spotify playlist source={} source_id='{}' limit={}",
                collectionSource,
                query,
                limit
            );
            SpotifySearchResult<SpotifyPlaylistSummary> playlistResults =
                spotifySource.resolve(spotifyWebApiClient, credential, limit);

            for (SpotifyPlaylistSummary playlist : playlistResults.items()) {
                EmsCollectedPlaylistEntity playlistEntity = upsertPlaylist(playlist, collectionSource, query, now);
                collectedPlaylistCount++;

                try {
                    List<SpotifyPlaylistTrack> playlistTracks =
                        spotifyWebApiClient.getPlaylistTracks(credential, playlist.playlistId());
                    Map<String, ReccoBeatsAudioFeaturesSnapshot> audioFeaturesByTrackId =
                        resolveSpotifyAudioFeatures(playlistTracks);
                    Instant resolvedAt = Instant.now();
                    for (int i = 0; i < playlistTracks.size(); i++) {
                        SpotifyPlaylistTrack playlistTrack = playlistTracks.get(i);
                        EmsCollectedTrackEntity trackEntity = upsertTrack(
                            playlistTrack,
                            collectionSource,
                            now,
                            resolveSpotifyTrackAudioFeatures(
                                playlistTrack,
                                audioFeaturesByTrackId.get(playlistTrack.spotifyTrackId()),
                                resolvedAt
                            )
                        );
                        linkPlaylistTrack(playlistEntity, trackEntity, i);
                        collectedTrackCount++;
                    }
                } catch (Exception e) {
                    log.warn("EMS collection: could not fetch tracks for Spotify playlist {}: {}", playlist.playlistId(), e.getMessage());
                }
            }

            if (spotifySource.includeLooseTrackSearch()) {
                log.info("EMS collection: calling Spotify searchTracks source={} query='{}' limit={}", collectionSource, query, limit);
                SpotifySearchResult<SpotifyPlaylistTrack> trackResults =
                    spotifyWebApiClient.searchTracks(credential, query, limit);

                Map<String, ReccoBeatsAudioFeaturesSnapshot> audioFeaturesByTrackId =
                    resolveSpotifyAudioFeatures(trackResults.items());
                Instant resolvedAt = Instant.now();
                for (SpotifyPlaylistTrack track : trackResults.items()) {
                    upsertTrack(
                        track,
                        collectionSource,
                        now,
                        resolveSpotifyTrackAudioFeatures(track, audioFeaturesByTrackId.get(track.spotifyTrackId()), resolvedAt)
                    );
                    collectedTrackCount++;
                }
            }
        } else if ("tidal".equals(platformId)) {
            boolean homePageSource = isTidalHomePageSource(query);
            log.info(
                "EMS collection: calling TIDAL playlist source={} source_id='{}' limit={}",
                collectionSource,
                query,
                limit
            );
            List<TidalPlaylistSummary> playlistResults = homePageSource
                ? tidalWebApiClient.getHomePagePlaylists(credential, query, limit)
                : tidalWebApiClient.searchPlaylists(credential, query, limit);

            for (TidalPlaylistSummary playlist : playlistResults) {
                EmsCollectedPlaylistEntity playlistEntity = upsertPlaylistFromTidal(playlist, collectionSource, query, now);
                collectedPlaylistCount++;

                try {
                    List<TidalPlaylistTrack> playlistTracks =
                        tidalWebApiClient.getPlaylistTracks(credential, playlist.playlistId());
                    Map<String, ReccoBeatsAudioFeaturesSnapshot> audioFeaturesByTrackId =
                        resolveTidalAudioFeatures(playlistTracks);
                    Instant resolvedAt = Instant.now();
                    for (int i = 0; i < playlistTracks.size(); i++) {
                        TidalPlaylistTrack playlistTrack = playlistTracks.get(i);
                        EmsCollectedTrackEntity trackEntity = upsertTrackFromTidal(
                            playlistTrack,
                            collectionSource,
                            now,
                            resolveTidalTrackAudioFeatures(
                                playlistTrack,
                                audioFeaturesByTrackId.get(playlistTrack.tidalTrackId()),
                                resolvedAt
                            )
                        );
                        linkPlaylistTrack(playlistEntity, trackEntity, i);
                        collectedTrackCount++;
                    }
                } catch (Exception e) {
                    log.warn("EMS collection: could not fetch tracks for TIDAL playlist {}: {}", playlist.playlistId(), e.getMessage());
                }
            }

            if (!homePageSource) {
                log.info("EMS collection: calling TIDAL searchTracks source={} query='{}' limit={}", collectionSource, query, limit);
                List<TidalPlaylistTrack> trackResults = tidalWebApiClient.searchTracks(credential, query, limit);

                Map<String, ReccoBeatsAudioFeaturesSnapshot> audioFeaturesByTrackId =
                    resolveTidalAudioFeatures(trackResults);
                Instant resolvedAt = Instant.now();
                for (TidalPlaylistTrack track : trackResults) {
                    upsertTrackFromTidal(
                        track,
                        collectionSource,
                        now,
                        resolveTidalTrackAudioFeatures(track, audioFeaturesByTrackId.get(track.tidalTrackId()), resolvedAt)
                    );
                    collectedTrackCount++;
                }
            }
        } else {
            throw new IllegalArgumentException("Unsupported EMS collection platform: %s".formatted(platformId));
        }

        log.info(
            "EMS collection source={} collected {} playlists and {} tracks for platform={} query='{}'",
            collectionSource,
            collectedPlaylistCount,
            collectedTrackCount,
            platformId,
            query
        );

        return new EmsCollectionSearchResult(
            platformId, query, collectedPlaylistCount, collectedTrackCount, now
        );
    }

    public List<EmsCollectedPlaylistEntity> getCollectedPlaylists(String platformId, int limit, boolean randomize) {
        int clampedLimit = Math.min(Math.max(limit, 1), 50);
        if (randomize) {
            return playlistRepository.findRandomBySourcePlatform(platformId, clampedLimit);
        }
        return playlistRepository.findBySourcePlatformOrderByCollectedAtDesc(platformId, clampedLimit);
    }

    public List<EmsCollectedPlaylistEntity> getFloSpecialPlaylists(int limit) {
        int clampedLimit = Math.min(Math.max(limit, 1), 300);
        return playlistRepository.findRecentWithTracksByCollectionSource(FLO_SPECIAL_SOURCE, org.springframework.data.domain.PageRequest.of(0, clampedLimit));
    }

    public List<EmsCollectedPlaylistEntity> getMelonHot100Playlists(int limit) {
        int clampedLimit = Math.min(Math.max(limit, 1), 5);
        return playlistRepository.findRecentWithTracksByCollectionSource(MELON_HOT_100_SOURCE, org.springframework.data.domain.PageRequest.of(0, clampedLimit));
    }

    public EmsCollectedPlaylistEntity getCollectedPlaylist(Long playlistId) {
        return playlistRepository.findById(playlistId)
            .orElseThrow(() -> new ApiResourceNotFoundException(
                "EMS collected playlist was not found: %s".formatted(playlistId)
            ));
    }

    public List<EmsCollectedTrackEntity> getCollectedTracks(String platformId) {
        return trackRepository.findBySourcePlatformOrderByCollectedAtDesc(platformId);
    }

    public List<EmsCollectedTrackEntity> getTracksForPlaylist(Long playlistId) {
        return playlistTrackRepository.findByPlaylistIdOrderBySortOrderAsc(playlistId)
            .stream()
            .map(EmsCollectedPlaylistTrackEntity::getTrack)
            .toList();
    }

    public EmsAudioFeatureCoverage getAudioFeatureCoverage(Long playlistId) {
        long linkedTrackCount = playlistTrackRepository.countTracksByPlaylistId(playlistId);
        long filledTrackCount = playlistTrackRepository.countAudioFeatureFilledTracksByPlaylistId(playlistId);
        long pendingTrackCount = Math.max(0, linkedTrackCount - filledTrackCount);
        double coverageRatio = linkedTrackCount == 0 ? 0.0 : (double) filledTrackCount / linkedTrackCount;
        return new EmsAudioFeatureCoverage(linkedTrackCount, filledTrackCount, pendingTrackCount, coverageRatio);
    }

    @Transactional
    public EmsAudioFeatureBackfillResult backfillAudioFeaturesForPlaylist(Long playlistId) {
        EmsCollectedPlaylistEntity playlist = getCollectedPlaylist(playlistId);
        List<EmsCollectedTrackEntity> tracks = getTracksForPlaylist(playlistId);
        long filledBefore = tracks.stream().filter(this::hasFilledAudioFeatures).count();
        List<EmsCollectedTrackEntity> pendingTracks = tracks.stream()
            .filter(track -> !hasFilledAudioFeatures(track))
            .toList();

        Map<String, ReccoBeatsAudioFeaturesSnapshot> snapshots = resolveAudioFeaturesForBackfill(
            playlist.getSourcePlatform(),
            pendingTracks
        );

        Instant resolvedAt = Instant.now();
        int matchedSnapshotCount = 0;
        int updatedTrackCount = 0;
        int newlyFilledTrackCount = 0;
        List<EmsCollectedTrackEntity> updatedTracks = new java.util.ArrayList<>();
        for (EmsCollectedTrackEntity track : pendingTracks) {
            ReccoBeatsAudioFeaturesSnapshot snapshot = snapshots.get(track.getExternalTrackId());
            if (snapshot == null) {
                continue;
            }

            matchedSnapshotCount++;
            EmsTrackAudioFeatures audioFeatures = toBackfilledAudioFeatures(track, snapshot, resolvedAt);
            track.applyAudioFeatures(audioFeatures);
            updatedTrackCount++;
            updatedTracks.add(track);
            if (audioFeatures.isAudioFeaturesFilled()) {
                newlyFilledTrackCount++;
            }
        }

        if (updatedTrackCount > 0) {
            trackRepository.saveAll(updatedTracks);
        }

        long filledAfter = filledBefore + newlyFilledTrackCount;
        long pendingAfter = Math.max(0, tracks.size() - filledAfter);
        double coverageRatio = tracks.isEmpty() ? 0.0 : (double) filledAfter / tracks.size();
        return new EmsAudioFeatureBackfillResult(
            playlist.getId(),
            playlist.getTitle(),
            playlist.getSourcePlatform(),
            tracks.size(),
            filledBefore,
            pendingTracks.size(),
            eligibleBackfillTrackCount(playlist.getSourcePlatform(), pendingTracks),
            missingIsrcTrackCount(playlist.getSourcePlatform(), pendingTracks),
            matchedSnapshotCount,
            updatedTrackCount,
            newlyFilledTrackCount,
            filledAfter,
            pendingAfter,
            coverageRatio,
            resolvedAt
        );
    }

    private Map<String, ReccoBeatsAudioFeaturesSnapshot> resolveAudioFeaturesForBackfill(
        String sourcePlatform,
        List<EmsCollectedTrackEntity> tracks
    ) {
        if ("spotify".equals(sourcePlatform)) {
            List<String> spotifyTrackIds = tracks.stream()
                .map(EmsCollectedTrackEntity::getExternalTrackId)
                .filter(this::hasText)
                .distinct()
                .toList();
            if (spotifyTrackIds.isEmpty()) {
                return Map.of();
            }
            return reccoBeatsAudioFeaturesClient.getAudioFeaturesForSpotifyTrackIds(spotifyTrackIds);
        }

        if ("tidal".equals(sourcePlatform)) {
            List<ReccoBeatsTrackLookupRequest> lookupRequests = tracks.stream()
                .filter(track -> hasText(track.getExternalTrackId()) && hasText(track.getIsrc()))
                .map(track -> new ReccoBeatsTrackLookupRequest(
                    track.getExternalTrackId(),
                    track.getTitle(),
                    track.getArtistName(),
                    track.getDurationMs(),
                    track.getIsrc()
                ))
                .toList();
            if (lookupRequests.isEmpty()) {
                return Map.of();
            }
            return reccoBeatsAudioFeaturesClient.getAudioFeaturesForExternalTracksByIsrc(lookupRequests);
        }

        return Map.of();
    }

    private int eligibleBackfillTrackCount(String sourcePlatform, List<EmsCollectedTrackEntity> tracks) {
        if ("spotify".equals(sourcePlatform)) {
            return (int) tracks.stream()
                .map(EmsCollectedTrackEntity::getExternalTrackId)
                .filter(this::hasText)
                .distinct()
                .count();
        }
        if ("tidal".equals(sourcePlatform)) {
            return (int) tracks.stream()
                .filter(track -> hasText(track.getExternalTrackId()) && hasText(track.getIsrc()))
                .count();
        }
        return 0;
    }

    private int missingIsrcTrackCount(String sourcePlatform, List<EmsCollectedTrackEntity> tracks) {
        if (!"tidal".equals(sourcePlatform)) {
            return 0;
        }
        return (int) tracks.stream()
            .filter(track -> hasText(track.getExternalTrackId()) && !hasText(track.getIsrc()))
            .count();
    }

    private EmsTrackAudioFeatures toBackfilledAudioFeatures(
        EmsCollectedTrackEntity track,
        ReccoBeatsAudioFeaturesSnapshot snapshot,
        Instant resolvedAt
    ) {
        Integer durationMs = track.getDurationMs();
        String audioFeatureSource = "tidal".equals(track.getSourcePlatform())
            ? "reccobeats_isrc_match"
            : "reccobeats_lookup";
        return new EmsTrackAudioFeatures(
            firstNonBlank(snapshot.spotifyTrackId(), track.getExternalTrackId()),
            audioFeatureSource,
            hasCompleteAudioFeatures(snapshot, durationMs),
            null,
            firstNonBlank(snapshot.spotifyTrackHref(), track.getPlatformExternalUrl()),
            firstNonBlank(buildSpotifyUri(snapshot.spotifyTrackId()), track.getSpotifyUri()),
            "audio_features",
            durationMs,
            snapshot.musicalKey(),
            snapshot.mode(),
            null,
            snapshot.acousticness(),
            snapshot.danceability(),
            snapshot.energy(),
            snapshot.instrumentalness(),
            snapshot.liveness(),
            snapshot.loudness(),
            snapshot.speechiness(),
            snapshot.tempo(),
            snapshot.valence(),
            snapshot.resolvedAt() == null ? resolvedAt : snapshot.resolvedAt()
        );
    }

    private boolean hasFilledAudioFeatures(EmsCollectedTrackEntity track) {
        return track.getAudioFeatures() != null && track.getAudioFeatures().isAudioFeaturesFilled();
    }

    private Map<String, ReccoBeatsAudioFeaturesSnapshot> resolveSpotifyAudioFeatures(List<SpotifyPlaylistTrack> tracks) {
        List<String> spotifyTrackIds = tracks.stream()
            .map(SpotifyPlaylistTrack::spotifyTrackId)
            .filter(this::hasText)
            .distinct()
            .toList();

        if (spotifyTrackIds.isEmpty()) {
            return Map.of();
        }

        try {
            return reccoBeatsAudioFeaturesClient.getAudioFeaturesForSpotifyTrackIds(spotifyTrackIds);
        } catch (RuntimeException exception) {
            log.warn(
                "EMS collection: ReccoBeats Spotify audio-features lookup failed: {}. Tracks will be stored with unavailable audio features.",
                exception.getMessage()
            );
            return Map.of();
        }
    }

    private Map<String, ReccoBeatsAudioFeaturesSnapshot> resolveTidalAudioFeatures(List<TidalPlaylistTrack> tracks) {
        List<ReccoBeatsTrackLookupRequest> lookupRequests = tracks.stream()
            .filter(track -> track != null && hasText(track.tidalTrackId()) && hasText(track.isrc()))
            .map(track -> new ReccoBeatsTrackLookupRequest(
                track.tidalTrackId(),
                track.title(),
                track.artistName(),
                track.durationMs() > 0 ? track.durationMs() : null,
                track.isrc()
            ))
            .toList();

        if (lookupRequests.isEmpty()) {
            return Map.of();
        }

        try {
            return reccoBeatsAudioFeaturesClient.getAudioFeaturesForExternalTracksByIsrc(lookupRequests);
        } catch (RuntimeException exception) {
            log.warn(
                "EMS collection: ReccoBeats TIDAL ISRC audio-features lookup failed: {}. Tracks will be stored with unavailable audio features.",
                exception.getMessage()
            );
            return Map.of();
        }
    }

    private EmsTrackAudioFeatures resolveSpotifyTrackAudioFeatures(
        SpotifyPlaylistTrack track,
        ReccoBeatsAudioFeaturesSnapshot snapshot,
        Instant resolvedAt
    ) {
        if (snapshot != null) {
            return new EmsTrackAudioFeatures(
                track.spotifyTrackId(),
                "reccobeats_lookup",
                hasCompleteAudioFeatures(snapshot, track.durationMs()),
                null,
                snapshot.spotifyTrackHref() == null ? track.externalUrl() : snapshot.spotifyTrackHref(),
                track.spotifyUri(),
                "audio_features",
                track.durationMs(),
                snapshot.musicalKey(),
                snapshot.mode(),
                null,
                snapshot.acousticness(),
                snapshot.danceability(),
                snapshot.energy(),
                snapshot.instrumentalness(),
                snapshot.liveness(),
                snapshot.loudness(),
                snapshot.speechiness(),
                snapshot.tempo(),
                snapshot.valence(),
                snapshot.resolvedAt() == null ? resolvedAt : snapshot.resolvedAt()
            );
        }

        return unavailableAudioFeatures(track.spotifyTrackId(), track.externalUrl(), track.spotifyUri(), track.durationMs(), resolvedAt);
    }

    private EmsTrackAudioFeatures resolveTidalTrackAudioFeatures(
        TidalPlaylistTrack track,
        ReccoBeatsAudioFeaturesSnapshot snapshot,
        Instant resolvedAt
    ) {
        Integer durationMs = track.durationMs() > 0 ? track.durationMs() : null;
        if (snapshot != null) {
            return new EmsTrackAudioFeatures(
                snapshot.spotifyTrackId(),
                "reccobeats_isrc_match",
                hasCompleteAudioFeatures(snapshot, durationMs),
                null,
                snapshot.spotifyTrackHref(),
                buildSpotifyUri(snapshot.spotifyTrackId()),
                "audio_features",
                durationMs,
                snapshot.musicalKey(),
                snapshot.mode(),
                null,
                snapshot.acousticness(),
                snapshot.danceability(),
                snapshot.energy(),
                snapshot.instrumentalness(),
                snapshot.liveness(),
                snapshot.loudness(),
                snapshot.speechiness(),
                snapshot.tempo(),
                snapshot.valence(),
                snapshot.resolvedAt() == null ? resolvedAt : snapshot.resolvedAt()
            );
        }

        return unavailableAudioFeatures(null, null, null, durationMs, resolvedAt);
    }

    private EmsTrackAudioFeatures unavailableAudioFeatures(
        String audioFeatureTrackId,
        String trackHref,
        String audioTrackUri,
        Integer durationMs,
        Instant resolvedAt
    ) {
        return new EmsTrackAudioFeatures(
            audioFeatureTrackId,
            "unavailable",
            false,
            null,
            trackHref,
            audioTrackUri,
            "audio_features",
            durationMs,
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
            resolvedAt
        );
    }

    private boolean hasCompleteAudioFeatures(ReccoBeatsAudioFeaturesSnapshot snapshot, Integer durationMs) {
        return snapshot != null
            && durationMs != null
            && snapshot.musicalKey() != null
            && snapshot.mode() != null
            && snapshot.acousticness() != null
            && snapshot.danceability() != null
            && snapshot.energy() != null
            && snapshot.instrumentalness() != null
            && snapshot.liveness() != null
            && snapshot.loudness() != null
            && snapshot.speechiness() != null
            && snapshot.tempo() != null
            && snapshot.valence() != null;
    }

    private String buildSpotifyUri(String spotifyTrackId) {
        return hasText(spotifyTrackId) ? "spotify:track:%s".formatted(spotifyTrackId) : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(String first, String second) {
        return hasText(first) ? first : second;
    }

    private boolean isTidalHomePageSource(String value) {
        return value != null && TIDAL_HOME_PAGE_SOURCE_IDS.contains(value.trim());
    }

    private SpotifyPlaylistSource spotifyPlaylistSource(String sourceId) {
        if (sourceId != null) {
            String normalized = sourceId.trim();
            if ("featured-playlists".equals(normalized) || "spotify:featured-playlists".equals(normalized)) {
                return new SpotifyPlaylistSource(
                    false,
                    (client, credential, limit) -> client.getFeaturedPlaylists(credential, limit)
                );
            }
            String categoryPrefix = "category:";
            String namespacedCategoryPrefix = "spotify:category:";
            if (normalized.startsWith(categoryPrefix) && normalized.length() > categoryPrefix.length()) {
                String categoryId = normalized.substring(categoryPrefix.length());
                return new SpotifyPlaylistSource(
                    false,
                    (client, credential, limit) -> client.getCategoryPlaylists(credential, categoryId, limit)
                );
            }
            if (normalized.startsWith(namespacedCategoryPrefix) && normalized.length() > namespacedCategoryPrefix.length()) {
                String categoryId = normalized.substring(namespacedCategoryPrefix.length());
                return new SpotifyPlaylistSource(
                    false,
                    (client, credential, limit) -> client.getCategoryPlaylists(credential, categoryId, limit)
                );
            }
        }
        return new SpotifyPlaylistSource(
            true,
            (client, credential, limit) -> client.searchPlaylists(credential, sourceId, limit)
        );
    }

    private EmsCollectedPlaylistEntity upsertPlaylist(
        SpotifyPlaylistSummary playlist, String source, String query, Instant now
    ) {
        return playlistRepository.findBySourcePlatformAndExternalPlaylistId("spotify", playlist.playlistId())
            .map(existing -> {
                existing.applyCollectedMetadata(
                    normalizeRequiredText(playlist.name(), "Untitled Spotify Playlist", 200),
                    normalizeOptionalText(playlist.ownerDisplayName(), 120),
                    normalizeDescription(playlist.description()),
                    truncate(playlist.coverImageUrl(), 500),
                    truncate(playlist.externalUrl(), 500),
                    truncate(playlist.spotifyUri(), 200),
                    playlist.trackCount(),
                    truncate(source, 50),
                    truncate(query, 200),
                    now
                );
                return existing;
            })
            .orElseGet(() -> playlistRepository.save(new EmsCollectedPlaylistEntity(
                playlist.playlistId(),
                normalizeRequiredText(playlist.name(), "Untitled Spotify Playlist", 200),
                "spotify",
                normalizeOptionalText(playlist.ownerDisplayName(), 120),
                normalizeDescription(playlist.description()),
                truncate(playlist.coverImageUrl(), 500),
                truncate(playlist.externalUrl(), 500),
                truncate(playlist.spotifyUri(), 200),
                playlist.trackCount(),
                truncate(source, 50),
                truncate(query, 200),
                now
            )));
    }

    private EmsCollectedTrackEntity upsertTrack(
        SpotifyPlaylistTrack track, String source, Instant now, EmsTrackAudioFeatures audioFeatures
    ) {
        return upsertCollectedTrack(
            track.spotifyTrackId(),
            track.title(),
            track.artistName(),
            "spotify",
            null,
            track.albumTitle(),
            track.albumImageUrl(),
            track.externalUrl(),
            track.spotifyUri(),
            track.previewUrl(),
            track.durationMs(),
            source,
            now,
            audioFeatures
        );
    }

    private EmsCollectedPlaylistEntity upsertPlaylistFromTidal(
        TidalPlaylistSummary playlist, String source, String query, Instant now
    ) {
        return upsertPlaylistFromTidal(playlist, playlist.coverImageUrl(), source, query, now);
    }

    private EmsCollectedPlaylistEntity upsertPlaylistFromTidal(
        TidalPlaylistSummary playlist, String coverImageUrl, String source, String query, Instant now
    ) {
        return playlistRepository.findBySourcePlatformAndExternalPlaylistId("tidal", playlist.playlistId())
            .map(existing -> {
                existing.applyCollectedMetadata(
                    normalizeRequiredText(playlist.name(), "Untitled TIDAL Playlist", 200),
                    "",
                    normalizeDescription(playlist.description()),
                    truncate(coverImageUrl, 500),
                    truncate(playlist.externalUrl(), 500),
                    null,
                    playlist.trackCount(),
                    truncate(source, 50),
                    truncate(query, 200),
                    now
                );
                return existing;
            })
            .orElseGet(() -> playlistRepository.save(new EmsCollectedPlaylistEntity(
                playlist.playlistId(),
                normalizeRequiredText(playlist.name(), "Untitled TIDAL Playlist", 200),
                "tidal",
                "",
                normalizeDescription(playlist.description()),
                truncate(coverImageUrl, 500),
                truncate(playlist.externalUrl(), 500),
                null,
                playlist.trackCount(),
                truncate(source, 50),
                truncate(query, 200),
                now
            )));
    }

    private String fallbackPlaylistCoverImageUrl(TidalPlaylistSummary playlist, List<TidalPlaylistTrack> tracks) {
        if (hasText(playlist.coverImageUrl())) {
            return playlist.coverImageUrl();
        }
        return tracks.stream()
            .map(TidalPlaylistTrack::albumImageUrl)
            .filter(this::hasText)
            .findFirst()
            .orElse(null);
    }

    private EmsCollectedPlaylistEntity upsertPlaylistFromSearchPreview(
        EmsCollectionSearchPlaylistPreview playlist,
        String query,
        String collectionSource,
        Instant now
    ) {
        return playlistRepository.findBySourcePlatformAndExternalPlaylistId(
                playlist.sourcePlatform(),
                playlist.externalPlaylistId()
            )
            .map(existing -> {
                existing.applyCollectedMetadata(
                    normalizeRequiredText(playlist.title(), "Untitled EMS Playlist", 200),
                    normalizeOptionalText(playlist.curator(), 120),
                    normalizeDescription(playlist.description()),
                    truncate(playlist.coverImageUrl(), 500),
                    truncate(playlist.platformExternalUrl(), 500),
                    truncate(spotifyUriFor(playlist.sourcePlatform(), playlist.platformUri()), 200),
                    playlist.trackCount(),
                    truncate(collectionSource, 50),
                    truncate(query, 200),
                    now
                );
                return existing;
            })
            .orElseGet(() -> playlistRepository.save(new EmsCollectedPlaylistEntity(
                playlist.externalPlaylistId(),
                normalizeRequiredText(playlist.title(), "Untitled EMS Playlist", 200),
                playlist.sourcePlatform(),
                normalizeOptionalText(playlist.curator(), 120),
                normalizeDescription(playlist.description()),
                truncate(playlist.coverImageUrl(), 500),
                truncate(playlist.platformExternalUrl(), 500),
                truncate(spotifyUriFor(playlist.sourcePlatform(), playlist.platformUri()), 200),
                playlist.trackCount(),
                truncate(collectionSource, 50),
                truncate(query, 200),
                now
            )));
    }

    private EmsCollectedTrackEntity upsertTrackFromTidal(
        TidalPlaylistTrack track, String source, Instant now, EmsTrackAudioFeatures audioFeatures
    ) {
        return upsertCollectedTrack(
            track.tidalTrackId(),
            track.title(),
            track.artistName(),
            "tidal",
            track.isrc(),
            track.albumTitle(),
            track.albumImageUrl(),
            track.externalUrl(),
            track.tidalUri(),
            track.previewUrl(),
            track.durationMs(),
            source,
            now,
            audioFeatures
        );
    }

    private EmsCollectedTrackEntity upsertTrackFromSearchPreview(
        EmsCollectionSearchTrackPreview track,
        String collectionSource,
        Instant now
    ) {
        return upsertCollectedTrack(
            track.externalTrackId(),
            track.title(),
            track.artistName(),
            track.sourcePlatform(),
            track.isrc(),
            track.albumTitle(),
            track.albumImageUrl(),
            track.platformExternalUrl(),
            track.platformUri(),
            track.previewUrl(),
            track.durationMs(),
            collectionSource,
            now,
            unavailableAudioFeatures(
                "spotify".equals(track.sourcePlatform()) ? track.externalTrackId() : null,
                track.platformExternalUrl(),
                spotifyUriFor(track.sourcePlatform(), track.platformUri()),
                track.durationMs(),
                now
            )
        );
    }

    private EmsCollectedPlaylistEntity upsertPlaylistFromMelonHot100(
        List<MelonHot100TrackSeed> tracks,
        Instant snapshotAt,
        Instant now
    ) {
        String coverImageUrl = tracks.stream()
            .map(MelonHot100TrackSeed::imageUrl)
            .filter(this::hasText)
            .findFirst()
            .orElse(null);
        String description = "Melon Hot 100 chart snapshot from %s. Stored in EMS as a playable trend playlist."
            .formatted(snapshotAt);
        return playlistRepository.findBySourcePlatformAndExternalPlaylistId("melon", MELON_HOT_100_PLAYLIST_ID)
            .map(existing -> {
                existing.applyCollectedMetadata(
                    "Melon Hot 100",
                    "Melon",
                    normalizeDescription(description),
                    truncate(coverImageUrl, 500),
                    "https://www.melon.com/chart/index.htm",
                    null,
                    tracks.size(),
                    MELON_HOT_100_SOURCE,
                    "Melon Hot 100",
                    now
                );
                return existing;
            })
            .orElseGet(() -> playlistRepository.save(new EmsCollectedPlaylistEntity(
                MELON_HOT_100_PLAYLIST_ID,
                "Melon Hot 100",
                "melon",
                "Melon",
                normalizeDescription(description),
                truncate(coverImageUrl, 500),
                "https://www.melon.com/chart/index.htm",
                null,
                tracks.size(),
                MELON_HOT_100_SOURCE,
                "Melon Hot 100",
                now
            )));
    }

    private EmsCollectedTrackEntity upsertTrackFromMelonHot100(MelonHot100TrackSeed track, Instant now) {
        String externalTrackId = hasText(track.melonSongId())
            ? track.melonSongId()
            : "rank-%03d".formatted(track.rank());
        return upsertCollectedTrack(
            externalTrackId,
            track.title(),
            track.artistName(),
            "melon",
            null,
            track.albumTitle(),
            track.imageUrl(),
            track.songExternalUrl(),
            null,
            null,
            null,
            MELON_HOT_100_SOURCE,
            now,
            unavailableAudioFeatures(
                externalTrackId,
                track.songExternalUrl(),
                null,
                null,
                now
            )
        );
    }

    private EmsCollectedPlaylistEntity upsertPlaylistFromFlo(
        FloSpecialCurationService.FloSpecialPlaylist playlist,
        FloSpecialCurationService.FloSpecialSection section,
        int trackCount,
        Instant now
    ) {
        String sectionTitle = normalizeRequiredText(section.title(), "FLO Special", 200);
        String curator = "CHNL".equalsIgnoreCase(playlist.sourceType()) ? "FLO Channel" : "FLO Special";
        return playlistRepository.findBySourcePlatformAndExternalPlaylistId("flo", playlist.externalPlaylistId())
            .map(existing -> {
                existing.applyCollectedMetadata(
                    normalizeRequiredText(playlist.title(), "Untitled FLO Playlist", 200),
                    curator,
                    normalizeDescription(sectionTitle),
                    truncate(playlist.coverImageUrl(), 500),
                    truncate(playlist.platformExternalUrl(), 500),
                    null,
                    trackCount,
                    FLO_SPECIAL_SOURCE,
                    sectionTitle,
                    now
                );
                return existing;
            })
            .orElseGet(() -> playlistRepository.save(new EmsCollectedPlaylistEntity(
                truncate(playlist.externalPlaylistId(), 160),
                normalizeRequiredText(playlist.title(), "Untitled FLO Playlist", 200),
                "flo",
                curator,
                normalizeDescription(sectionTitle),
                truncate(playlist.coverImageUrl(), 500),
                truncate(playlist.platformExternalUrl(), 500),
                null,
                trackCount,
                FLO_SPECIAL_SOURCE,
                sectionTitle,
                now
            )));
    }

    private EmsCollectedTrackEntity upsertTrackFromFlo(FloSpecialCurationService.FloSpecialTrack track, Instant now) {
        return upsertCollectedTrack(
            track.externalTrackId(),
            track.title(),
            track.artistName(),
            "flo",
            null,
            track.albumTitle(),
            track.albumImageUrl(),
            track.platformExternalUrl(),
            null,
            null,
            track.durationMs(),
            FLO_SPECIAL_SOURCE,
            now,
            unavailableAudioFeatures(
                track.externalTrackId(),
                track.platformExternalUrl(),
                null,
                track.durationMs(),
                now
            )
        );
    }

    private EmsCollectedTrackEntity upsertCollectedTrack(
        String externalTrackId,
        String title,
        String artistName,
        String sourcePlatform,
        String isrc,
        String albumTitle,
        String albumImageUrl,
        String platformExternalUrl,
        String platformUri,
        String previewUrl,
        Integer durationMs,
        String collectionSource,
        Instant collectedAt,
        EmsTrackAudioFeatures audioFeatures
    ) {
        trackRepository.upsertByExternalTrack(
            truncate(externalTrackId, 160),
            normalizeRequiredText(title, "Untitled EMS Track", 200),
            normalizeRequiredText(artistName, "Unknown Artist", 200),
            truncate(sourcePlatform, 50),
            truncate(isrc, 32),
            truncate(albumTitle, 200),
            truncate(albumImageUrl, 500),
            truncate(platformExternalUrl, 500),
            truncate(platformUri, 200),
            truncate(previewUrl, 500),
            durationMs,
            truncate(collectionSource, 50),
            collectedAt,
            audioFeatures.getAudioFeatureTrackId(),
            audioFeatures.getAudioFeatureSource(),
            audioFeatures.isAudioFeaturesFilled(),
            audioFeatures.getAnalysisUrl(),
            audioFeatures.getTrackHref(),
            audioFeatures.getAudioTrackUri(),
            audioFeatures.getFeatureType(),
            audioFeatures.getDurationMs(),
            audioFeatures.getMusicalKey(),
            audioFeatures.getMode(),
            audioFeatures.getTimeSignature(),
            audioFeatures.getAcousticness(),
            audioFeatures.getDanceability(),
            audioFeatures.getEnergy(),
            audioFeatures.getInstrumentalness(),
            audioFeatures.getLiveness(),
            audioFeatures.getLoudness(),
            audioFeatures.getSpeechiness(),
            audioFeatures.getTempo(),
            audioFeatures.getValence(),
            audioFeatures.getResolvedAt()
        );
        EmsCollectedTrackEntity storedTrack = trackRepository.findBySourcePlatformAndExternalTrackId(sourcePlatform, externalTrackId)
            .orElseThrow(() -> new IllegalStateException(
                "EMS collected track upsert did not return a row: %s:%s".formatted(sourcePlatform, externalTrackId)
            ));
        audioFeatureCompletionAutoEnqueueService.ifPresent(service -> service.enqueueEmsCollectedTrack(storedTrack));
        return storedTrack;
    }

    private void linkPlaylistTrack(EmsCollectedPlaylistEntity playlist, EmsCollectedTrackEntity track, int order) {
        playlistTrackRepository.upsertPlaylistTrackLink(playlist.getId(), track.getId(), order);
    }

    private String spotifyUriFor(String sourcePlatform, String platformUri) {
        return "spotify".equals(sourcePlatform) ? platformUri : null;
    }

    private String normalizeRequiredText(String value, String fallback, int maxLength) {
        String normalized = hasText(value) ? value : fallback;
        return truncate(normalized, maxLength);
    }

    private String normalizeOptionalText(String value, int maxLength) {
        return truncate(hasText(value) ? value : "", maxLength);
    }

    private String normalizeDescription(String value) {
        return normalizeOptionalText(value, 1000);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record EmsCollectionSearchResult(
        String platformId,
        String query,
        int collectedPlaylistCount,
        int collectedTrackCount,
        Instant collectedAt
    ) {}

    public record EmsCollectionSearchPreviewResult(
        String platformId,
        String query,
        Long poolRunId,
        List<EmsCollectionSearchPlaylistPreview> playlists,
        List<EmsCollectionSearchTrackPreview> tracks,
        int resultPlaylistCount,
        int resultTrackCount,
        Instant searchedAt
    ) {}

    public record EmsCollectionSearchPlaylistPreview(
        String externalPlaylistId,
        String title,
        String sourcePlatform,
        String curator,
        String description,
        String coverImageUrl,
        String platformExternalUrl,
        String platformUri,
        int trackCount
    ) {}

    public record EmsCollectionSearchTrackPreview(
        String externalTrackId,
        String title,
        String artistName,
        String sourcePlatform,
        String isrc,
        String albumTitle,
        String albumImageUrl,
        String platformExternalUrl,
        String platformUri,
        String previewUrl,
        Integer durationMs
    ) {}

    public record EmsCollectionSearchPlaylistTracksPreview(
        String platformId,
        String externalPlaylistId,
        List<EmsCollectionSearchTrackPreview> tracks,
        int trackCount,
        Instant searchedAt
    ) {}

    public record EmsSearchPoolCollectionResult(
        int collectedPlaylistCount,
        int collectedTrackCount
    ) {}

    public record EmsTidalPlaylistUrlImportCollection(
        Long emsPlaylistId,
        String externalPlaylistId,
        String sourcePlatform,
        String title,
        int trackCount,
        String collectionSource,
        Instant collectedAt
    ) {}

    public record EmsAudioFeatureCoverage(
        long trackCount,
        long filledTrackCount,
        long pendingTrackCount,
        double coverageRatio
    ) {}

    public record EmsAudioFeatureBackfillResult(
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

    public record MelonHot100TrackSeed(
        int rank,
        String melonSongId,
        String title,
        String artistName,
        String albumTitle,
        String imageUrl,
        String songExternalUrl,
        Instant snapshotAt
    ) {}

    public record MelonHot100CollectionResult(
        Long playlistId,
        int collectedPlaylistCount,
        int collectedTrackCount,
        Instant collectedAt
    ) {}

    public record FloSpecialCollectionResult(
        int sectionCount,
        int collectedPlaylistCount,
        int collectedTrackCount,
        Instant collectedAt,
        List<FloSpecialCollectionFailure> failures
    ) {}

    public record FloSpecialCollectionFailure(
        String sectionTitle,
        String externalPlaylistId,
        String message
    ) {}

    private record SpotifyPlaylistSource(
        boolean includeLooseTrackSearch,
        SpotifyPlaylistSourceResolver resolver
    ) {
        SpotifySearchResult<SpotifyPlaylistSummary> resolve(
            SpotifyWebApiClient client,
            PlatformAccountCredential credential,
            int limit
        ) {
            return resolver.resolve(client, credential, limit);
        }
    }

    @FunctionalInterface
    private interface SpotifyPlaylistSourceResolver {
        SpotifySearchResult<SpotifyPlaylistSummary> resolve(
            SpotifyWebApiClient client,
            PlatformAccountCredential credential,
            int limit
        );
    }

    private record SearchTotals(int playlistCount, int trackCount) {}
}
