package io.myforevermusic.api.modules.gms.application;

import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistTrackEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistTrackRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsTrackAudioFeatures;
import io.myforevermusic.api.modules.gms.presentation.GmsRecommendationPreviewResponse;
import io.myforevermusic.api.modules.pms.application.PmsPersonalPlaylistStore;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore;
import io.myforevermusic.api.modules.recommendation.application.AxisEvidence;
import io.myforevermusic.api.modules.recommendation.application.PlaylistQualityEvaluation;
import io.myforevermusic.api.modules.recommendation.application.PlaylistQualityEvaluator;
import io.myforevermusic.api.modules.recommendation.application.RecommendationAxisEvidenceBuilder;
import io.myforevermusic.api.modules.recommendation.application.UserMusicEventStore;
import io.myforevermusic.api.modules.recommendation.application.UserMusicEventService;
import io.myforevermusic.api.modules.recommendation.presentation.UserMusicEventRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * GMS playlist 후보 흐름의 1차 service.
 * 사용자 PMS 라이브러리가 준비된(baseline 이상) 사용자에 한해 EMS 본 테이블에 적재된
 * 공개 playlist 중 affinity 가 있는 후보를 골라 반환한다.
 *
 * 1차 단계의 affinity 는 단순 — 사용자 PMS user_library 의 distinct artist 집합과
 * EMS playlist 의 curator/title/source 일치로 가중치를 줌. 정교한 6축 evaluator/
 * SASRec 적용은 후속 commit.
 */
@org.springframework.context.annotation.Profile("!local")
@Service
public class GmsPlaylistPreviewService {

    private static final int MAX_LIMIT = 50;

    private final AuthAccountStore authAccountStore;
    private final PmsUserLibraryStore pmsUserLibraryStore;
    private final EmsCollectedPlaylistRepository playlistRepository;
    private final EmsCollectedPlaylistTrackRepository playlistTrackRepository;
    private final PmsPersonalPlaylistStore personalPlaylistStore;
    private final UserMusicEventService userMusicEventService;
    private final UserMusicEventStore userMusicEventStore;
    private final PlaylistQualityEvaluator playlistQualityEvaluator;

    public GmsPlaylistPreviewService(
        AuthAccountStore authAccountStore,
        PmsUserLibraryStore pmsUserLibraryStore,
        EmsCollectedPlaylistRepository playlistRepository,
        EmsCollectedPlaylistTrackRepository playlistTrackRepository,
        PmsPersonalPlaylistStore personalPlaylistStore,
        UserMusicEventService userMusicEventService,
        UserMusicEventStore userMusicEventStore,
        PlaylistQualityEvaluator playlistQualityEvaluator
    ) {
        this.authAccountStore = authAccountStore;
        this.pmsUserLibraryStore = pmsUserLibraryStore;
        this.playlistRepository = playlistRepository;
        this.playlistTrackRepository = playlistTrackRepository;
        this.personalPlaylistStore = personalPlaylistStore;
        this.userMusicEventService = userMusicEventService;
        this.userMusicEventStore = userMusicEventStore;
        this.playlistQualityEvaluator = playlistQualityEvaluator;
    }

    public GmsPlaylistPreviewResult preview(String userId, Integer limit) {
        return preview(userId, limit, null);
    }

    public GmsPlaylistPreviewResult preview(String userId, Integer limit, Long includePlaylistId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user_id is required.");
        }

        long pmsTrackCount = pmsUserLibraryStore.findPlaylists(userId).stream()
            .mapToLong(playlist -> playlist.trackCount())
            .sum();
        if (pmsTrackCount == 0L) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "GMS playlist preview requires an imported PMS user library (current stage = cold-start)."
            );
        }

        String preferredPlatform = authAccountStore.findByUserId(userId)
            .map(account -> account.preferredPlatformId())
            .filter(value -> value != null && !value.isBlank())
            .orElse(null);

        int safeLimit = Math.max(1, Math.min(MAX_LIMIT, limit == null ? 12 : limit));

        List<EmsCollectedPlaylistEntity> source;
        if (preferredPlatform != null) {
            source = playlistRepository.findRecentWithTracksBySourcePlatforms(
                List.of(preferredPlatform),
                PageRequest.of(0, safeLimit * 3)
            );
        } else {
            source = playlistRepository.findRecentWithTracks(PageRequest.of(0, safeLimit * 3));
        }

        List<EmsCollectedPlaylistEntity> candidateSource = new ArrayList<>(source);
        if (includePlaylistId != null && candidateSource.stream().noneMatch(playlist ->
            includePlaylistId.equals(playlist.getId())
        )) {
            playlistRepository.findById(includePlaylistId).ifPresent(candidateSource::add);
        }

        Set<String> userArtists = collectUserArtists(userId);
        Set<Long> dismissedPlaylistIds = dismissedPlaylistIds(userId);
        Set<Long> savedPlaylistIds = savedPlaylistIds(userId);

        List<GmsPlaylistPreviewCandidate> candidates = candidateSource.stream()
            .filter(playlist -> playlist.getId() != null
                && !dismissedPlaylistIds.contains(playlist.getId())
                && !savedPlaylistIds.contains(playlist.getId()))
            .map(playlist -> scoreCandidate(playlist, userArtists))
            .filter(candidate -> includePlaylistId != null && includePlaylistId.equals(candidate.playlistId())
                || candidate.affinityScore() > 0.0d)
            .sorted((left, right) -> {
                if (includePlaylistId != null) {
                    boolean leftExplicit = includePlaylistId.equals(left.playlistId());
                    boolean rightExplicit = includePlaylistId.equals(right.playlistId());
                    if (leftExplicit != rightExplicit) {
                        return leftExplicit ? -1 : 1;
                    }
                }
                return Double.compare(right.compositeScore(), left.compositeScore());
            })
            .limit(safeLimit)
            .toList();

        return new GmsPlaylistPreviewResult(
            userId,
            preferredPlatform,
            pmsTrackCount > 0L ? "baseline" : "cold-start",
            Instant.now(),
            candidates
        );
    }

    private Set<Long> savedPlaylistIds(String userId) {
        Set<Long> result = new HashSet<>();
        personalPlaylistStore.findPlaylists(userId).forEach(playlist -> {
            Long playlistId = parseGmsPersonalPlaylistId(playlist.playlistId());
            if (playlistId != null) {
                result.add(playlistId);
            }
        });
        return result;
    }

    private Set<Long> dismissedPlaylistIds(String userId) {
        Set<Long> result = new HashSet<>();
        for (UserMusicEventStore.StoredEvent event : userMusicEventStore.findRecentByUserId(userId, 1000)) {
            if (!"ignored_recommendation".equals(event.eventType())
                || !"gms".equals(event.sourceSpace())
                || !"playlist".equals(event.itemKind())) {
                continue;
            }
            Long playlistId = parseGmsEmsPlaylistId(event.playlistId());
            if (playlistId != null) {
                result.add(playlistId);
            }
        }
        return result;
    }

    private Set<String> collectUserArtists(String userId) {
        Set<String> result = new HashSet<>();
        pmsUserLibraryStore.findPlaylists(userId).forEach(playlist ->
            playlist.tracks().forEach(track -> {
                if (track.artistName() != null && !track.artistName().isBlank()) {
                    result.add(track.artistName().trim().toLowerCase(Locale.ROOT));
                }
            })
        );
        return result;
    }

    private GmsPlaylistPreviewCandidate scoreCandidate(
        EmsCollectedPlaylistEntity playlist,
        Set<String> userArtists
    ) {
        List<EmsCollectedPlaylistTrackEntity> trackLinks =
            playlistTrackRepository.findByPlaylistIdOrderBySortOrderAsc(playlist.getId());
        long linkedTrackCount = trackLinks.size();
        long filledFeatureCount = trackLinks.stream()
            .map(EmsCollectedPlaylistTrackEntity::getTrack)
            .filter(track -> track != null && track.getAudioFeatures() != null)
            .count();

        double baseAffinity = userArtists.contains(normalize(playlist.getCurator())) ? 0.45d : 0.15d;
        if (titleMatchesUserArtists(playlist.getTitle(), userArtists)) {
            baseAffinity += 0.15d;
        }
        double coverageBonus = linkedTrackCount == 0L
            ? 0.0d
            : Math.min(0.30d, ((double) filledFeatureCount / linkedTrackCount) * 0.30d);
        double sizeBonus = Math.min(0.10d, linkedTrackCount / 80.0d);
        double affinity = Math.min(1.0d, baseAffinity + coverageBonus + sizeBonus);

        double confidence = linkedTrackCount == 0L
            ? 0.0d
            : Math.min(1.0d, 0.5d + ((double) filledFeatureCount / Math.max(1, linkedTrackCount)) * 0.5d);

        PlaylistQualityEvaluation evaluation = trackLinks.isEmpty()
            ? null
            : playlistQualityEvaluator.evaluate(toEvaluatorItems(playlist, trackLinks));
        Double novelty = computeNoveltyAgainstUserArtists(trackLinks, userArtists);
        List<AxisEvidence> evidence = RecommendationAxisEvidenceBuilder.build(
            affinity,
            novelty,
            evaluation,
            confidence
        );
        double composite = computeCompositeScore(affinity, novelty, evaluation, confidence);

        return new GmsPlaylistPreviewCandidate(
            playlist.getId(),
            playlist.getExternalPlaylistId(),
            playlist.getSourcePlatform(),
            playlist.getTitle(),
            playlist.getCurator(),
            playlist.getDescription(),
            playlist.getCoverImageUrl(),
            playlist.getPlatformExternalUrl(),
            linkedTrackCount,
            filledFeatureCount,
            round(affinity),
            round(confidence),
            round(composite),
            playlist.getCollectedAt(),
            evidence
        );
    }

    private List<GmsRecommendationPreviewResponse.RecommendationItem> toEvaluatorItems(
        EmsCollectedPlaylistEntity playlist,
        List<EmsCollectedPlaylistTrackEntity> trackLinks
    ) {
        List<GmsRecommendationPreviewResponse.RecommendationItem> items = new ArrayList<>(trackLinks.size());
        int rank = 0;
        for (EmsCollectedPlaylistTrackEntity link : trackLinks) {
            EmsCollectedTrackEntity track = link.getTrack();
            if (track == null) {
                continue;
            }
            items.add(new GmsRecommendationPreviewResponse.RecommendationItem(
                rank++,
                "ems-%d".formatted(track.getId()),
                track.getTitle(),
                track.getArtistName(),
                track.getSourcePlatform(),
                playlist.getExternalPlaylistId(),
                playlist.getTitle(),
                track.getAlbumTitle(),
                track.getAlbumImageUrl(),
                track.getPlatformExternalUrl(),
                track.getSpotifyUri(),
                track.getPreviewUrl(),
                "spotify".equalsIgnoreCase(track.getSourcePlatform()) ? track.getExternalTrackId() : null,
                track.getDurationMs(),
                null,
                "ems",
                energyLevelFrom(track.getAudioFeatures()),
                null
            ));
        }
        return items;
    }

    private Integer energyLevelFrom(EmsTrackAudioFeatures features) {
        if (features == null || features.getEnergy() == null) {
            return null;
        }
        double clamped = Math.max(0.0d, Math.min(1.0d, features.getEnergy()));
        return Math.max(1, Math.min(5, (int) Math.round(clamped * 4.0d) + 1));
    }

    private Double computeNoveltyAgainstUserArtists(
        List<EmsCollectedPlaylistTrackEntity> trackLinks,
        Set<String> userArtists
    ) {
        if (trackLinks.isEmpty()) {
            return null;
        }
        long counted = 0L;
        long unfamiliar = 0L;
        for (EmsCollectedPlaylistTrackEntity link : trackLinks) {
            EmsCollectedTrackEntity track = link.getTrack();
            if (track == null) {
                continue;
            }
            String artist = normalize(track.getArtistName());
            if (artist.isBlank()) {
                continue;
            }
            counted++;
            if (!userArtists.contains(artist)) {
                unfamiliar++;
            }
        }
        if (counted == 0L) {
            return null;
        }
        return round((double) unfamiliar / counted);
    }

    private double computeCompositeScore(
        double affinity,
        Double novelty,
        PlaylistQualityEvaluation evaluation,
        double confidence
    ) {
        double noveltyValue = novelty == null ? 0.5d : novelty;
        double coherence = evaluation == null || evaluation.coherenceScore() == null
            ? 0.5d : evaluation.coherenceScore();
        double diversity = evaluation == null || evaluation.diversityScore() == null
            ? 0.5d : evaluation.diversityScore();
        double redundancy = evaluation == null || evaluation.redundancyPenalty() == null
            ? 0.0d : evaluation.redundancyPenalty();
        double composite = (0.45d * affinity)
            + (0.15d * noveltyValue)
            + (0.15d * coherence)
            + (0.10d * diversity)
            + (0.10d * confidence)
            - (0.05d * redundancy);
        return Math.max(0.0d, Math.min(1.0d, composite));
    }

    private boolean titleMatchesUserArtists(String title, Set<String> userArtists) {
        if (title == null || title.isBlank() || userArtists.isEmpty()) {
            return false;
        }
        String normalized = title.toLowerCase(Locale.ROOT);
        return userArtists.stream().anyMatch(normalized::contains);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Long parseGmsEmsPlaylistId(String playlistId) {
        if (playlistId == null || !playlistId.startsWith("ems-")) {
            return null;
        }
        try {
            return Long.parseLong(playlistId.substring("ems-".length()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long parseGmsPersonalPlaylistId(String playlistId) {
        if (playlistId == null || !playlistId.startsWith("gms-ems-")) {
            return null;
        }
        try {
            return Long.parseLong(playlistId.substring("gms-ems-".length()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    public record GmsPlaylistPreviewCandidate(
        Long playlistId,
        String externalPlaylistId,
        String sourcePlatform,
        String title,
        String curator,
        String description,
        String coverImageUrl,
        String platformExternalUrl,
        long trackCount,
        long audioFeatureFilledCount,
        double affinityScore,
        double confidenceScore,
        double compositeScore,
        Instant collectedAt,
        List<AxisEvidence> axisEvidence
    ) {}

    public record GmsPlaylistPreviewResult(
        String userId,
        String preferredPlatform,
        String modelStage,
        Instant generatedAt,
        List<GmsPlaylistPreviewCandidate> candidates
    ) {}

    public SaveResult saveToPms(String userId, Long emsPlaylistId, String customTitle, List<Long> excludedTrackIds) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user_id is required.");
        }
        if (emsPlaylistId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "playlist_id is required.");
        }

        long pmsTrackCount = pmsUserLibraryStore.findPlaylists(userId).stream()
            .mapToLong(playlist -> playlist.trackCount())
            .sum();
        if (pmsTrackCount == 0L) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "GMS playlist save requires an imported PMS user library (current stage = cold-start)."
            );
        }

        EmsCollectedPlaylistEntity emsPlaylist = playlistRepository.findById(emsPlaylistId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "EMS playlist not found: " + emsPlaylistId
            ));

        List<EmsCollectedPlaylistTrackEntity> trackLinks =
            playlistTrackRepository.findByPlaylistIdOrderBySortOrderAsc(emsPlaylistId);
        if (trackLinks.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "EMS playlist has no tracks to import."
            );
        }
        Set<Long> excludedIds = excludedTrackIds == null
            ? Set.of()
            : new HashSet<>(excludedTrackIds);
        List<EmsCollectedPlaylistTrackEntity> selectedTrackLinks = trackLinks.stream()
            .filter(link -> link.getTrack() != null && !excludedIds.contains(link.getTrack().getId()))
            .toList();
        if (selectedTrackLinks.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "No tracks selected for PMS import."
            );
        }

        String personalPlaylistId = "gms-ems-%d".formatted(emsPlaylistId);
        String resolvedTitle = (customTitle != null && !customTitle.isBlank())
            ? customTitle
            : "%s (GMS)".formatted(emsPlaylist.getTitle());

        PmsPersonalPlaylistStore.PersonalPlaylistState playlist = personalPlaylistStore
            .findPlaylist(userId, personalPlaylistId)
            .orElseGet(() -> personalPlaylistStore.createPlaylist(
                new PmsPersonalPlaylistStore.CreatePlaylistDraft(
                    userId,
                    personalPlaylistId,
                    resolvedTitle,
                    "Imported from EMS via GMS: %s".formatted(emsPlaylist.getTitle())
                )
            ));

        Set<String> existingTrackIds = new HashSet<>();
        if (playlist.tracks() != null) {
            playlist.tracks().forEach(track -> existingTrackIds.add(track.trackId()));
        }

        Instant now = Instant.now();
        int addedCount = 0;
        for (int i = 0; i < selectedTrackLinks.size(); i++) {
            EmsCollectedTrackEntity emsTrack = selectedTrackLinks.get(i).getTrack();
            String trackId = "ems-%d".formatted(emsTrack.getId());
            if (existingTrackIds.contains(trackId)) {
                continue;
            }
            String sourcePlatform = emsTrack.getSourcePlatform();
            String externalTrackId = firstNonBlank(emsTrack.getExternalTrackId(), trackId);
            String platformUri = platformUriFor(sourcePlatform, externalTrackId, emsTrack.getSpotifyUri());
            String spotifyTrackId = "spotify".equalsIgnoreCase(sourcePlatform) ? externalTrackId : null;
            String spotifyUri = "spotify".equalsIgnoreCase(sourcePlatform) ? platformUri : null;
            String tidalTrackId = "tidal".equalsIgnoreCase(sourcePlatform) ? externalTrackId : null;
            String tidalUri = "tidal".equalsIgnoreCase(sourcePlatform) ? platformUri : null;
            String preferredPlaybackPlatform = nativePlaybackPlatform(sourcePlatform);
            PmsPersonalPlaylistStore.PersonalTrackState trackState = new PmsPersonalPlaylistStore.PersonalTrackState(
                trackId,
                externalTrackId,
                emsTrack.getTitle(),
                emsTrack.getArtistName(),
                sourcePlatform,
                emsTrack.getAlbumTitle(),
                emsTrack.getAlbumImageUrl(),
                emsTrack.getPlatformExternalUrl(),
                platformUri,
                emsTrack.getPreviewUrl(),
                emsTrack.getIsrc(),
                spotifyTrackId,
                spotifyUri,
                tidalTrackId,
                tidalUri,
                preferredPlaybackPlatform,
                preferredPlaybackPlatform == null ? "unresolved" : "native",
                emsTrack.getDurationMs(),
                i,
                "gms-playlist-import",
                now
            );
            playlist = personalPlaylistStore.addTrack(new PmsPersonalPlaylistStore.AddTrackDraft(
                userId,
                personalPlaylistId,
                trackState,
                "gms-playlist-import"
            ));
            userMusicEventService.recordEvent(new UserMusicEventRequest(
                userId,
                "added_to_playlist",
                "gms",
                emsTrack.getSourcePlatform(),
                null,
                trackId,
                "track",
                trackId,
                personalPlaylistId,
                emsTrack.getExternalTrackId(),
                emsTrack.getSpotifyUri(),
                emsTrack.getTitle(),
                emsTrack.getArtistName(),
                emsTrack.getAlbumTitle(),
                null,
                emsTrack.getDurationMs(),
                null,
                null,
                null,
                null,
                now
            ));
            addedCount++;
        }

        return new SaveResult(
            userId,
            emsPlaylistId,
            personalPlaylistId,
            playlist.title(),
            playlist.trackCount(),
            addedCount,
            now
        );
    }

    public DismissResult dismissFromGms(String userId, Long emsPlaylistId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user_id is required.");
        }
        if (emsPlaylistId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "playlist_id is required.");
        }

        EmsCollectedPlaylistEntity emsPlaylist = playlistRepository.findById(emsPlaylistId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "EMS playlist not found: " + emsPlaylistId
            ));
        Instant now = Instant.now();
        userMusicEventService.recordEvent(new UserMusicEventRequest(
            userId,
            "ignored_recommendation",
            "gms",
            emsPlaylist.getSourcePlatform(),
            null,
            "gms-ems-%d".formatted(emsPlaylistId),
            "playlist",
            null,
            "ems-%d".formatted(emsPlaylistId),
            null,
            emsPlaylist.getSpotifyUri(),
            emsPlaylist.getTitle(),
            emsPlaylist.getCurator(),
            null,
            null,
            null,
            null,
            null,
            "gms-playlist-%d".formatted(emsPlaylistId),
            null,
            now
        ));
        return new DismissResult(userId, emsPlaylistId, now);
    }

    public record SaveResult(
        String userId,
        Long emsPlaylistId,
        String personalPlaylistId,
        String personalPlaylistTitle,
        int personalPlaylistTrackCount,
        int addedTrackCount,
        Instant savedAt
    ) {}

    public record DismissResult(
        String userId,
        Long emsPlaylistId,
        Instant dismissedAt
    ) {}

    private String nativePlaybackPlatform(String sourcePlatform) {
        if ("spotify".equalsIgnoreCase(sourcePlatform)) {
            return "spotify";
        }
        if ("tidal".equalsIgnoreCase(sourcePlatform)) {
            return "tidal";
        }
        return null;
    }

    private String platformUriFor(String sourcePlatform, String externalTrackId, String spotifyUri) {
        if ("spotify".equalsIgnoreCase(sourcePlatform)) {
            return firstNonBlank(spotifyUri, externalTrackId == null ? null : "spotify:track:%s".formatted(externalTrackId));
        }
        if ("tidal".equalsIgnoreCase(sourcePlatform) && externalTrackId != null && !externalTrackId.isBlank()) {
            return "tidal:track:%s".formatted(externalTrackId);
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
