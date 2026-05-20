package io.myforevermusic.api.modules.recommendation.presentation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileService;
import io.myforevermusic.api.modules.recommendation.application.AudioTasteTrackFeature;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recommendations/admin/audio-taste")
public class AudioTasteAdminController {

    private final AudioTasteProfileService profileService;

    public AudioTasteAdminController(AudioTasteProfileService profileService) {
        this.profileService = profileService;
    }

    @Operation(summary = "Fetch an on-demand audio taste profile for a user")
    @GetMapping("/profile")
    public AudioTasteProfileResponse profile(
        @RequestParam("user_id") String userId,
        @RequestParam(value = "target_user_id", required = false) String targetUserId,
        @RequestParam(value = "event_limit", required = false) Integer eventLimit
    ) {
        return AudioTasteProfileResponse.from(profileService.recompute(targetUser(userId, targetUserId), eventLimit));
    }

    @Operation(summary = "Recompute an audio taste profile for a user")
    @PostMapping("/recompute")
    public AudioTasteProfileResponse recompute(
        @RequestParam("user_id") String userId,
        @RequestParam(value = "target_user_id", required = false) String targetUserId,
        @RequestParam(value = "event_limit", required = false) Integer eventLimit
    ) {
        return AudioTasteProfileResponse.from(profileService.recompute(targetUser(userId, targetUserId), eventLimit));
    }

    @Operation(summary = "Export audio taste dataset summary and rows for a user")
    @GetMapping("/dataset")
    public AudioTasteDatasetResponse dataset(
        @RequestParam("user_id") String userId,
        @RequestParam(value = "target_user_id", required = false) String targetUserId,
        @RequestParam(value = "event_limit", required = false) Integer eventLimit
    ) {
        AudioTasteProfileService.Dataset dataset = profileService.dataset(targetUser(userId, targetUserId), eventLimit);
        return AudioTasteDatasetResponse.from(dataset);
    }

    private String targetUser(String userId, String targetUserId) {
        return targetUserId == null || targetUserId.isBlank() ? userId : targetUserId;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AudioTasteProfileResponse(
        String service,
        String status,
        Instant generatedAt,
        ProfileItem profile
    ) {
        static AudioTasteProfileResponse from(AudioTasteProfileService.Profile profile) {
            return new AudioTasteProfileResponse("api", profile.status(), Instant.now(), ProfileItem.from(profile));
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AudioTasteDatasetResponse(
        String service,
        String status,
        Instant generatedAt,
        String datasetVersion,
        String userId,
        ProfileItem profile,
        List<RowItem> rows
    ) {
        static AudioTasteDatasetResponse from(AudioTasteProfileService.Dataset dataset) {
            return new AudioTasteDatasetResponse(
                "api",
                dataset.profile().status(),
                Instant.now(),
                dataset.datasetVersion(),
                dataset.userId(),
                ProfileItem.from(dataset.profile()),
                dataset.rows().stream().map(RowItem::from).toList()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProfileItem(
        String userId,
        String status,
        boolean audioTasteApplicable,
        int positiveTrackCount,
        int negativeTrackCount,
        int eventLimit,
        CentroidItem positiveCentroid,
        CentroidItem negativeCentroid,
        CoverageItem coverage,
        List<String> warnings,
        Instant recomputedAt
    ) {
        static ProfileItem from(AudioTasteProfileService.Profile profile) {
            return new ProfileItem(
                profile.userId(),
                profile.status(),
                profile.audioTasteApplicable(),
                profile.positiveTrackCount(),
                profile.negativeTrackCount(),
                profile.eventLimit(),
                CentroidItem.from(profile.positiveCentroid()),
                CentroidItem.from(profile.negativeCentroid()),
                CoverageItem.from(profile.coverage()),
                profile.warnings(),
                profile.recomputedAt()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CentroidItem(
        double acousticness,
        double danceability,
        double energy,
        double instrumentalness,
        double liveness,
        double speechiness,
        double tempo,
        double valence
    ) {
        static CentroidItem from(AudioTasteProfileService.Centroid centroid) {
            return new CentroidItem(
                centroid.acousticness(),
                centroid.danceability(),
                centroid.energy(),
                centroid.instrumentalness(),
                centroid.liveness(),
                centroid.speechiness(),
                centroid.tempo(),
                centroid.valence()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CoverageItem(
        int trackCount,
        long usableTrackCount,
        double featureReadyRatio,
        long inferredTrackCount,
        long weakTrackCount
    ) {
        static CoverageItem from(AudioTasteProfileService.Coverage coverage) {
            return new CoverageItem(
                coverage.trackCount(),
                coverage.usableTrackCount(),
                coverage.featureReadyRatio(),
                coverage.inferredTrackCount(),
                coverage.weakTrackCount()
            );
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RowItem(
        String trackScope,
        String trackId,
        String title,
        String artistName,
        String sourcePlatform,
        String audioFeatureSource,
        boolean audioFeaturesFilled,
        double featureWeight,
        String featureTier,
        Double acousticness,
        Double danceability,
        Double energy,
        Double instrumentalness,
        Double liveness,
        Double speechiness,
        Double tempo,
        Double valence
    ) {
        static RowItem from(AudioTasteTrackFeature row) {
            return new RowItem(
                row.trackScope(),
                row.trackId(),
                row.title(),
                row.artistName(),
                row.sourcePlatform(),
                row.audioFeatureSource(),
                row.audioFeaturesFilled(),
                row.featureWeight(),
                row.featureTier(),
                row.acousticness(),
                row.danceability(),
                row.energy(),
                row.instrumentalness(),
                row.liveness(),
                row.speechiness(),
                row.tempo(),
                row.valence()
            );
        }
    }
}
