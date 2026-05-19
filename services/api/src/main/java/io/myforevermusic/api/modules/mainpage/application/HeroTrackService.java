package io.myforevermusic.api.modules.mainpage.application;

import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackRepository;
import io.myforevermusic.api.modules.mainpage.presentation.HeroTrackResponse;
import io.myforevermusic.api.modules.platform.infrastructure.spotify.SpotifyPublicCatalogClient;
import io.myforevermusic.api.modules.recommendation.application.RecommendationSnapshotStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Resolves the single track that should drive the main-page Visual EQ hero
 * banner. Personalised users get their top GMS recommendation when it carries
 * a Spotify preview; everyone else falls back to the latest EMS acquisition
 * pool track that has a preview.
 */
@org.springframework.context.annotation.Profile("!local")
@Service
public class HeroTrackService {

    private static final Logger log = LoggerFactory.getLogger(HeroTrackService.class);
    private static final int GMS_LOOKUP_LIMIT = 20;
    private static final int EMS_RANDOM_POOL_SIZE = 60;
    private static final int MAX_LIST_LIMIT = 12;
    private static final String EMS_ACQUISITION_POOL = "acquisition_pool";
    private static final String DEFAULT_SOURCE_LABEL = "Editorial Pick";
    private static final String GMS_SOURCE_LABEL = "Recommended for you";

    private final RecommendationSnapshotStore recommendationSnapshotStore;
    private final EmsCollectedTrackRepository emsCollectedTrackRepository;
    private final SpotifyPublicCatalogClient spotifyPublicCatalogClient;

    public HeroTrackService(
        RecommendationSnapshotStore recommendationSnapshotStore,
        EmsCollectedTrackRepository emsCollectedTrackRepository,
        SpotifyPublicCatalogClient spotifyPublicCatalogClient
    ) {
        this.recommendationSnapshotStore = recommendationSnapshotStore;
        this.emsCollectedTrackRepository = emsCollectedTrackRepository;
        this.spotifyPublicCatalogClient = spotifyPublicCatalogClient;
    }

    public Optional<HeroTrackResponse> resolve(String userId) {
        List<HeroTrackResponse> list = resolveList(userId, 1);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<HeroTrackResponse> findLatest(int limit) {
        int effectiveLimit = Math.min(MAX_LIST_LIMIT, Math.max(1, limit));
        List<EmsCollectedTrackEntity> tracks = emsCollectedTrackRepository
            .findRecentByCollectionSource(EMS_ACQUISITION_POOL, PageRequest.of(0, effectiveLimit));
        return tracks.stream()
            .map(track -> toResponse(track, DEFAULT_SOURCE_LABEL))
            .toList();
    }

    public List<HeroTrackResponse> resolveList(String userId, int limit) {
        int effectiveLimit = Math.min(MAX_LIST_LIMIT, Math.max(1, limit));
        List<HeroTrackResponse> picked = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        if (userId != null && !userId.isBlank()) {
            collectFromGms(userId, effectiveLimit, picked, seenKeys);
        }
        if (picked.size() < effectiveLimit) {
            collectFromEms(
                emsCollectedTrackRepository.findRecentByCollectionSourceWithPreview(
                    EMS_ACQUISITION_POOL,
                    PageRequest.of(0, EMS_RANDOM_POOL_SIZE)
                ),
                DEFAULT_SOURCE_LABEL,
                effectiveLimit,
                picked,
                seenKeys
            );
        }
        if (picked.size() < effectiveLimit) {
            collectFromEms(
                emsCollectedTrackRepository.findRecentWithPreview(PageRequest.of(0, EMS_RANDOM_POOL_SIZE)),
                DEFAULT_SOURCE_LABEL,
                effectiveLimit,
                picked,
                seenKeys
            );
        }
        return picked;
    }

    private void collectFromGms(
        String userId,
        int limit,
        List<HeroTrackResponse> picked,
        Set<String> seenKeys
    ) {
        List<RecommendationSnapshotStore.StoredSnapshot> snapshots;
        try {
            snapshots = recommendationSnapshotStore.findRecentByUserId(userId, GMS_LOOKUP_LIMIT);
        } catch (RuntimeException exception) {
            log.warn("Hero track GMS lookup failed for user {}: {}", userId, exception.getMessage());
            return;
        }
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }

        snapshots.stream()
            .filter(snapshot -> snapshot.candidateTrackId() != null && !snapshot.candidateTrackId().isBlank())
            .filter(snapshot -> snapshot.sourcePlatform() != null && !snapshot.sourcePlatform().isBlank())
            .sorted(Comparator
                .comparing((RecommendationSnapshotStore.StoredSnapshot s) ->
                    s.rank() == null ? Integer.MAX_VALUE : s.rank())
                .thenComparing(Comparator
                    .comparing(RecommendationSnapshotStore.StoredSnapshot::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))))
            .forEach(snapshot -> {
                if (picked.size() >= limit) {
                    return;
                }
                Optional<EmsCollectedTrackEntity> trackOpt = emsCollectedTrackRepository
                    .findBySourcePlatformAndExternalTrackId(snapshot.sourcePlatform(), snapshot.candidateTrackId());
                if (trackOpt.isEmpty()) {
                    return;
                }
                EmsCollectedTrackEntity track = trackOpt.get();
                String key = trackKey(track);
                if (seenKeys.contains(key)) {
                    return;
                }
                buildGmsResponse(track).ifPresent(response -> {
                    seenKeys.add(key);
                    picked.add(response);
                });
            });
    }

    private Optional<HeroTrackResponse> buildGmsResponse(EmsCollectedTrackEntity track) {
        if (hasUsablePreview(track)) {
            return Optional.of(toResponse(track, GMS_SOURCE_LABEL));
        }
        String spotifyId = extractSpotifyTrackId(track);
        if (spotifyId == null) {
            return Optional.empty();
        }
        try {
            return spotifyPublicCatalogClient.getTrack(spotifyId)
                .filter(remote -> remote.previewUrl() != null && !remote.previewUrl().isBlank())
                .map(remote -> toResponseWithPreview(track, GMS_SOURCE_LABEL, remote.previewUrl()));
        } catch (RuntimeException ex) {
            log.warn("Hero preview enrichment failed for spotify track {}: {}", spotifyId, ex.getMessage());
            return Optional.empty();
        }
    }

    private void collectFromEms(
        List<EmsCollectedTrackEntity> pool,
        String sourceLabel,
        int limit,
        List<HeroTrackResponse> picked,
        Set<String> seenKeys
    ) {
        if (pool == null || pool.isEmpty()) {
            return;
        }
        List<EmsCollectedTrackEntity> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, new Random());
        for (EmsCollectedTrackEntity track : shuffled) {
            if (picked.size() >= limit) {
                break;
            }
            if (!hasUsablePreview(track)) {
                continue;
            }
            String key = trackKey(track);
            if (seenKeys.add(key)) {
                picked.add(toResponse(track, sourceLabel));
            }
        }
    }

    private String trackKey(EmsCollectedTrackEntity track) {
        return "%s::%s".formatted(
            track.getSourcePlatform() == null ? "" : track.getSourcePlatform(),
            track.getExternalTrackId() == null ? "" : track.getExternalTrackId()
        );
    }

    private boolean hasUsablePreview(EmsCollectedTrackEntity track) {
        return track != null && track.getPreviewUrl() != null && !track.getPreviewUrl().isBlank();
    }

    private HeroTrackResponse toResponse(EmsCollectedTrackEntity track, String sourceLabel) {
        return toResponseWithPreview(track, sourceLabel, track.getPreviewUrl());
    }

    private HeroTrackResponse toResponseWithPreview(
        EmsCollectedTrackEntity track,
        String sourceLabel,
        String previewUrl
    ) {
        String spotifyTrackId = extractSpotifyTrackId(track);
        return new HeroTrackResponse(
            track.getExternalTrackId(),
            track.getSourcePlatform(),
            spotifyTrackId,
            track.getTitle(),
            track.getArtistName(),
            track.getAlbumTitle(),
            track.getAlbumImageUrl(),
            previewUrl,
            track.getPlatformExternalUrl(),
            track.getDurationMs(),
            sourceLabel
        );
    }

    private String extractSpotifyTrackId(EmsCollectedTrackEntity track) {
        if ("spotify".equalsIgnoreCase(track.getSourcePlatform())
            && track.getExternalTrackId() != null
            && !track.getExternalTrackId().isBlank()) {
            return track.getExternalTrackId();
        }
        String uri = track.getSpotifyUri();
        if (uri == null || uri.isBlank() || !uri.startsWith("spotify:track:")) {
            return null;
        }
        String id = uri.substring("spotify:track:".length()).trim();
        return id.isEmpty() ? null : id;
    }
}
