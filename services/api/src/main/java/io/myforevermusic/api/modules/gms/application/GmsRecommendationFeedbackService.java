package io.myforevermusic.api.modules.gms.application;

import io.myforevermusic.api.modules.gms.presentation.GmsRecommendationFeedbackRequest;
import io.myforevermusic.api.modules.gms.presentation.GmsRecommendationFeedbackResponse;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore;
import io.myforevermusic.api.modules.recommendation.application.RecommendationAuditLogStore;
import io.myforevermusic.api.modules.recommendation.application.UserMusicEventService;
import io.myforevermusic.api.modules.recommendation.presentation.UserMusicEventRequest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class GmsRecommendationFeedbackService {

    private static final Set<String> SUPPORTED_FEEDBACK_TYPES = Set.of("like", "dislike", "save", "skip");

    private final GmsRecommendationFeedbackStore feedbackStore;
    private final PmsUserLibraryStore pmsUserLibraryStore;
    private final UserMusicEventService userMusicEventService;
    private final RecommendationAuditLogStore recommendationAuditLogStore;

    public GmsRecommendationFeedbackService(
        GmsRecommendationFeedbackStore feedbackStore,
        PmsUserLibraryStore pmsUserLibraryStore,
        UserMusicEventService userMusicEventService,
        RecommendationAuditLogStore recommendationAuditLogStore
    ) {
        this.feedbackStore = feedbackStore;
        this.pmsUserLibraryStore = pmsUserLibraryStore;
        this.userMusicEventService = userMusicEventService;
        this.recommendationAuditLogStore = recommendationAuditLogStore;
    }

    public GmsRecommendationFeedbackResponse recordFeedback(GmsRecommendationFeedbackRequest request) {
        String feedbackType = normalizeFeedbackType(request.feedbackType());
        PmsUserLibraryStore.LibraryTrackState libraryTrack = resolveLibraryTrack(request.userId(), request.trackId());

        GmsRecommendationFeedbackStore.StoredFeedback storedFeedback = feedbackStore.save(
            new GmsRecommendationFeedbackStore.FeedbackDraft(
                request.userId(),
                request.requestId(),
                request.playlistId(),
                request.trackId(),
                feedbackType,
                request.score(),
                request.sourceSpace(),
                request.reason()
            )
        );
        recordFeedbackLearningEvent(request, feedbackType, libraryTrack);
        recordFeedbackAudit(request, feedbackType, storedFeedback);

        return GmsRecommendationFeedbackResponse.from(storedFeedback);
    }

    private void recordFeedbackAudit(
        GmsRecommendationFeedbackRequest request,
        String feedbackType,
        GmsRecommendationFeedbackStore.StoredFeedback storedFeedback
    ) {
        recommendationAuditLogStore.save(new RecommendationAuditLogStore.AuditDraft(
            request.userId(),
            request.requestId(),
            request.requestId(),
            RecommendationAuditLogStore.EVENT_FEEDBACK_RECORDED,
            sourceSpace(request.sourceSpace()),
            null,
            null,
            null,
            null,
            null,
            null,
            feedbackType,
            request.trackId(),
            request.playlistId(),
            null,
            storedFeedback.createdAt() == null ? Instant.now() : storedFeedback.createdAt()
        ));
    }

    private String normalizeFeedbackType(String feedbackType) {
        String normalized = feedbackType == null ? "" : feedbackType.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_FEEDBACK_TYPES.contains(normalized)) {
            throw new IllegalArgumentException(
                "GMS feedback type must be one of: %s.".formatted(String.join(", ", SUPPORTED_FEEDBACK_TYPES))
            );
        }
        return normalized;
    }

    private PmsUserLibraryStore.LibraryTrackState resolveLibraryTrack(String userId, String trackId) {
        List<PmsUserLibraryStore.LibraryPlaylistState> playlists = pmsUserLibraryStore.findPlaylists(userId);
        return playlists.stream()
            .flatMap(playlist -> playlist.tracks().stream())
            .filter(track -> track.trackId().equals(trackId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "GMS feedback can only be recorded for tracks in the user's synced PMS library."
            ));
    }

    private void recordFeedbackLearningEvent(
        GmsRecommendationFeedbackRequest request,
        String feedbackType,
        PmsUserLibraryStore.LibraryTrackState libraryTrack
    ) {
        userMusicEventService.recordEvent(new UserMusicEventRequest(
            request.userId(),
            toEventType(feedbackType),
            sourceSpace(request.sourceSpace()),
            libraryTrack.sourcePlatform(),
            libraryTrack.preferredPlaybackPlatform(),
            libraryTrack.trackId(),
            "track",
            libraryTrack.trackId(),
            request.playlistId(),
            libraryTrack.externalTrackId(),
            libraryTrack.platformUri(),
            libraryTrack.title(),
            libraryTrack.artistName(),
            libraryTrack.albumTitle(),
            libraryTrack.isrc(),
            trackDurationMs(libraryTrack),
            null,
            null,
            request.requestId(),
            null,
            Instant.now()
        ));
    }

    private String toEventType(String feedbackType) {
        return switch (feedbackType) {
            case "like" -> "recommendation_liked";
            case "dislike" -> "recommendation_rejected";
            case "save" -> "track_saved";
            case "skip" -> "ignored_recommendation";
            default -> throw new IllegalArgumentException("Unsupported GMS feedback type: %s.".formatted(feedbackType));
        };
    }

    private String sourceSpace(String sourceSpace) {
        String normalized = sourceSpace == null ? "" : sourceSpace.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "gms" : normalized;
    }

    private Integer trackDurationMs(PmsUserLibraryStore.LibraryTrackState track) {
        return track.audioFeatures() == null
            ? null
            : track.audioFeatures().getDurationMs();
    }
}
