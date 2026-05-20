package io.myforevermusic.api.modules.recommendation.application;

import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsTrackAudioFeatures;
import io.myforevermusic.api.modules.platform.infrastructure.reccobeats.ReccoBeatsAudioFeaturesClient;
import io.myforevermusic.api.modules.platform.infrastructure.reccobeats.ReccoBeatsAudioFeaturesClient.ReccoBeatsAudioFeaturesSnapshot;
import io.myforevermusic.api.modules.platform.infrastructure.reccobeats.ReccoBeatsAudioFeaturesClient.ReccoBeatsTrackLookupRequest;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsTrackAudioFeatures;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsUserTrackEntity;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsUserTrackRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!local")
public class AudioFeatureCompletionWorkerService {

    private static final Duration RETRY_DELAY = Duration.ofHours(1);
    private static final String MANUAL_LLM_RETRY_REASON = "manual_llm_retry";

    private final AudioFeatureCompletionJobStore jobStore;
    private final PmsUserTrackRepository pmsTrackRepository;
    private final EmsCollectedTrackRepository emsTrackRepository;
    private final ReccoBeatsAudioFeaturesClient reccoBeatsClient;
    private final Optional<LastFmAudioFeatureInferenceService> lastFmInferenceService;
    private final Optional<AudioFeatureLlmSearchInferenceService> llmSearchInferenceService;

    @Autowired
    public AudioFeatureCompletionWorkerService(
        AudioFeatureCompletionJobStore jobStore,
        PmsUserTrackRepository pmsTrackRepository,
        EmsCollectedTrackRepository emsTrackRepository,
        ReccoBeatsAudioFeaturesClient reccoBeatsClient,
        Optional<LastFmAudioFeatureInferenceService> lastFmInferenceService,
        Optional<AudioFeatureLlmSearchInferenceService> llmSearchInferenceService
    ) {
        this.jobStore = jobStore;
        this.pmsTrackRepository = pmsTrackRepository;
        this.emsTrackRepository = emsTrackRepository;
        this.reccoBeatsClient = reccoBeatsClient;
        this.lastFmInferenceService = lastFmInferenceService;
        this.llmSearchInferenceService = llmSearchInferenceService;
    }

    public AudioFeatureCompletionWorkerService(
        AudioFeatureCompletionJobStore jobStore,
        PmsUserTrackRepository pmsTrackRepository,
        EmsCollectedTrackRepository emsTrackRepository,
        ReccoBeatsAudioFeaturesClient reccoBeatsClient,
        Optional<LastFmAudioFeatureInferenceService> lastFmInferenceService
    ) {
        this(jobStore, pmsTrackRepository, emsTrackRepository, reccoBeatsClient, lastFmInferenceService, Optional.empty());
    }

    public AudioFeatureCompletionWorkerService(
        AudioFeatureCompletionJobStore jobStore,
        PmsUserTrackRepository pmsTrackRepository,
        EmsCollectedTrackRepository emsTrackRepository,
        ReccoBeatsAudioFeaturesClient reccoBeatsClient
    ) {
        this(jobStore, pmsTrackRepository, emsTrackRepository, reccoBeatsClient, Optional.empty(), Optional.empty());
    }

    @Transactional
    public ProcessCompletionResult processQueuedJobs(String workerId, int limit) {
        Instant now = Instant.now();
        List<AudioFeatureCompletionJobStore.StoredJob> jobs = jobStore.claimQueued(
            workerId == null || workerId.isBlank() ? "api-worker" : workerId.trim(),
            normalizeLimit(limit),
            now
        );
        Counter counter = new Counter();
        for (AudioFeatureCompletionJobStore.StoredJob job : jobs) {
            processJob(job, counter);
        }
        return new ProcessCompletionResult(
            jobs.size(),
            counter.completedJobCount,
            counter.retryWaitJobCount,
            counter.unresolvedJobCount,
            counter.failedJobCount
        );
    }

    private void processJob(AudioFeatureCompletionJobStore.StoredJob job, Counter counter) {
        Instant now = Instant.now();
        try {
            ProcessStatus status = switch (job.trackScope()) {
                case "pms_user_track" -> processPmsTrack(job);
                case "ems_collected_track" -> processEmsTrack(job);
                default -> ProcessStatus.failed("unsupported_track_scope:" + job.trackScope());
            };
            mark(job.jobId(), status, now, counter);
        } catch (RuntimeException exception) {
            jobStore.markRetryWait(job.jobId(), truncate(exception.getMessage()), now.plus(RETRY_DELAY), now);
            counter.retryWaitJobCount++;
        }
    }

    private ProcessStatus processPmsTrack(AudioFeatureCompletionJobStore.StoredJob job) {
        PmsUserTrackEntity track = pmsTrackRepository.findById(job.trackId()).orElse(null);
        if (track == null) {
            return ProcessStatus.failed("pms_track_not_found");
        }
        if (MANUAL_LLM_RETRY_REASON.equals(job.requestedReason())) {
            return processPmsInferenceOnly(track, "manual_llm_retry_no_inference_result");
        }

        ReccoBeatsAudioFeaturesSnapshot snapshot = resolvePmsSnapshot(track);
        if (snapshot == null) {
            return processPmsInferenceOnly(track, "reccobeats_no_match");
        }

        PmsTrackAudioFeatures audioFeatures = toPmsAudioFeatures(track, snapshot, Instant.now());
        track.applyAudioFeatures(audioFeatures);
        pmsTrackRepository.save(track);
        return audioFeatures.isComplete()
            ? ProcessStatus.completed()
            : ProcessStatus.unresolved("reccobeats_incomplete_audio_features");
    }

    private ProcessStatus processEmsTrack(AudioFeatureCompletionJobStore.StoredJob job) {
        Long emsTrackId = parseLong(job.trackId());
        if (emsTrackId == null) {
            return ProcessStatus.failed("invalid_ems_track_id");
        }
        EmsCollectedTrackEntity track = emsTrackRepository.findById(emsTrackId).orElse(null);
        if (track == null) {
            return ProcessStatus.failed("ems_track_not_found");
        }
        if (MANUAL_LLM_RETRY_REASON.equals(job.requestedReason())) {
            return processEmsInferenceOnly(track, "manual_llm_retry_no_inference_result");
        }

        ReccoBeatsAudioFeaturesSnapshot snapshot = resolveEmsSnapshot(track);
        if (snapshot == null) {
            return processEmsInferenceOnly(track, "reccobeats_no_match");
        }

        EmsTrackAudioFeatures audioFeatures = toEmsAudioFeatures(track, snapshot, Instant.now());
        track.applyAudioFeatures(audioFeatures);
        emsTrackRepository.save(track);
        return hasCompleteAudioFeatures(snapshot, track.getDurationMs())
            ? ProcessStatus.completed()
            : ProcessStatus.unresolved("reccobeats_incomplete_audio_features");
    }

    private ProcessStatus processPmsInferenceOnly(PmsUserTrackEntity track, String noResultMessage) {
        LastFmAudioFeatureInferenceService.InferredAudioFeatureSnapshot inferredSnapshot = inferPmsSnapshot(track);
        if (inferredSnapshot != null) {
            track.applyAudioFeatures(toPmsAudioFeatures(track, inferredSnapshot));
            pmsTrackRepository.save(track);
            return ProcessStatus.unresolved("lastfm_tag_inferred_partial_audio_features");
        }
        AudioFeatureLlmSearchInferenceService.InferredAudioFeatureSnapshot llmSnapshot = inferPmsLlmSearchSnapshot(track);
        if (llmSnapshot != null) {
            PmsTrackAudioFeatures audioFeatures = toPmsAudioFeatures(track, llmSnapshot);
            track.applyAudioFeatures(audioFeatures);
            pmsTrackRepository.save(track);
            return audioFeatures.isComplete()
                ? ProcessStatus.completed()
                : ProcessStatus.unresolved("llm_search_inferred_partial_audio_features");
        }
        return ProcessStatus.unresolved(noResultMessage);
    }

    private ProcessStatus processEmsInferenceOnly(EmsCollectedTrackEntity track, String noResultMessage) {
        LastFmAudioFeatureInferenceService.InferredAudioFeatureSnapshot inferredSnapshot = inferEmsSnapshot(track);
        if (inferredSnapshot != null) {
            track.applyAudioFeatures(toEmsAudioFeatures(track, inferredSnapshot));
            emsTrackRepository.save(track);
            return ProcessStatus.unresolved("lastfm_tag_inferred_partial_audio_features");
        }
        AudioFeatureLlmSearchInferenceService.InferredAudioFeatureSnapshot llmSnapshot = inferEmsLlmSearchSnapshot(track);
        if (llmSnapshot != null) {
            EmsTrackAudioFeatures audioFeatures = toEmsAudioFeatures(track, llmSnapshot);
            track.applyAudioFeatures(audioFeatures);
            emsTrackRepository.save(track);
            return hasCompleteLlmAudioFeatures(llmSnapshot)
                ? ProcessStatus.completed()
                : ProcessStatus.unresolved("llm_search_inferred_partial_audio_features");
        }
        return ProcessStatus.unresolved(noResultMessage);
    }

    private ReccoBeatsAudioFeaturesSnapshot resolvePmsSnapshot(PmsUserTrackEntity track) {
        if ("spotify".equals(track.getSourcePlatform()) && hasText(track.getSpotifyTrackId())) {
            return reccoBeatsClient.getAudioFeaturesForSpotifyTrackIds(List.of(track.getSpotifyTrackId()))
                .get(track.getSpotifyTrackId());
        }
        if (hasText(track.getExternalTrackId()) && hasText(track.getIsrc())) {
            return reccoBeatsClient.getAudioFeaturesForExternalTracksByIsrc(List.of(new ReccoBeatsTrackLookupRequest(
                track.getExternalTrackId(),
                track.getTitle(),
                track.getArtistName(),
                currentDurationMs(track.getAudioFeatures()),
                track.getIsrc()
            ))).get(track.getExternalTrackId());
        }
        return null;
    }

    private ReccoBeatsAudioFeaturesSnapshot resolveEmsSnapshot(EmsCollectedTrackEntity track) {
        if ("spotify".equals(track.getSourcePlatform()) && hasText(track.getExternalTrackId())) {
            return reccoBeatsClient.getAudioFeaturesForSpotifyTrackIds(List.of(track.getExternalTrackId()))
                .get(track.getExternalTrackId());
        }
        if (hasText(track.getExternalTrackId()) && hasText(track.getIsrc())) {
            return reccoBeatsClient.getAudioFeaturesForExternalTracksByIsrc(List.of(new ReccoBeatsTrackLookupRequest(
                track.getExternalTrackId(),
                track.getTitle(),
                track.getArtistName(),
                track.getDurationMs(),
                track.getIsrc()
            ))).get(track.getExternalTrackId());
        }
        return null;
    }

    private PmsTrackAudioFeatures toPmsAudioFeatures(
        PmsUserTrackEntity track,
        ReccoBeatsAudioFeaturesSnapshot snapshot,
        Instant resolvedAt
    ) {
        Integer durationMs = currentDurationMs(track.getAudioFeatures());
        String spotifyTrackId = firstNonBlank(snapshot.spotifyTrackId(), track.getSpotifyTrackId());
        return new PmsTrackAudioFeatures(
            spotifyTrackId,
            audioFeatureSource(track.getSourcePlatform()),
            hasCompleteAudioFeatures(snapshot, durationMs),
            null,
            firstNonBlank(snapshot.spotifyTrackHref(), track.getPlatformExternalUrl()),
            firstNonBlank(buildSpotifyUri(spotifyTrackId), track.getSpotifyUri()),
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

    private EmsTrackAudioFeatures toEmsAudioFeatures(
        EmsCollectedTrackEntity track,
        ReccoBeatsAudioFeaturesSnapshot snapshot,
        Instant resolvedAt
    ) {
        Integer durationMs = track.getDurationMs();
        String spotifyTrackId = firstNonBlank(snapshot.spotifyTrackId(), "spotify".equals(track.getSourcePlatform())
            ? track.getExternalTrackId()
            : null);
        return new EmsTrackAudioFeatures(
            spotifyTrackId,
            audioFeatureSource(track.getSourcePlatform()),
            hasCompleteAudioFeatures(snapshot, durationMs),
            null,
            firstNonBlank(snapshot.spotifyTrackHref(), track.getPlatformExternalUrl()),
            firstNonBlank(buildSpotifyUri(spotifyTrackId), track.getSpotifyUri()),
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

    private LastFmAudioFeatureInferenceService.InferredAudioFeatureSnapshot inferPmsSnapshot(PmsUserTrackEntity track) {
        if (lastFmInferenceService.isEmpty()) {
            return null;
        }
        try {
            return lastFmInferenceService.get().infer(new LastFmAudioFeatureInferenceService.AudioFeatureInferenceTarget(
                "pms_user_track",
                track.getTrackId(),
                track.getTitle(),
                track.getArtistName(),
                currentDurationMs(track.getAudioFeatures()),
                track.getPlatformExternalUrl()
            )).orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private LastFmAudioFeatureInferenceService.InferredAudioFeatureSnapshot inferEmsSnapshot(EmsCollectedTrackEntity track) {
        if (lastFmInferenceService.isEmpty()) {
            return null;
        }
        try {
            return lastFmInferenceService.get().infer(new LastFmAudioFeatureInferenceService.AudioFeatureInferenceTarget(
                "ems_collected_track",
                String.valueOf(track.getId()),
                track.getTitle(),
                track.getArtistName(),
                track.getDurationMs(),
                track.getPlatformExternalUrl()
            )).orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private AudioFeatureLlmSearchInferenceService.InferredAudioFeatureSnapshot inferPmsLlmSearchSnapshot(PmsUserTrackEntity track) {
        if (llmSearchInferenceService.isEmpty()) {
            return null;
        }
        try {
            return llmSearchInferenceService.get().infer(new AudioFeatureLlmSearchInferenceService.AudioFeatureInferenceTarget(
                "pms_user_track",
                track.getTrackId(),
                track.getTitle(),
                track.getArtistName(),
                currentDurationMs(track.getAudioFeatures()),
                track.getPlatformExternalUrl()
            )).orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private AudioFeatureLlmSearchInferenceService.InferredAudioFeatureSnapshot inferEmsLlmSearchSnapshot(EmsCollectedTrackEntity track) {
        if (llmSearchInferenceService.isEmpty()) {
            return null;
        }
        try {
            return llmSearchInferenceService.get().infer(new AudioFeatureLlmSearchInferenceService.AudioFeatureInferenceTarget(
                "ems_collected_track",
                String.valueOf(track.getId()),
                track.getTitle(),
                track.getArtistName(),
                track.getDurationMs(),
                track.getPlatformExternalUrl()
            )).orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private PmsTrackAudioFeatures toPmsAudioFeatures(
        PmsUserTrackEntity track,
        LastFmAudioFeatureInferenceService.InferredAudioFeatureSnapshot snapshot
    ) {
        Integer durationMs = snapshot.durationMs() == null ? currentDurationMs(track.getAudioFeatures()) : snapshot.durationMs();
        return new PmsTrackAudioFeatures(
            null,
            snapshot.source(),
            snapshot.audioFeaturesFilled(),
            null,
            track.getPlatformExternalUrl(),
            track.getPlatformUri(),
            "audio_features",
            durationMs,
            null,
            null,
            null,
            snapshot.acousticness(),
            snapshot.danceability(),
            snapshot.energy(),
            snapshot.instrumentalness(),
            snapshot.liveness(),
            null,
            snapshot.speechiness(),
            snapshot.tempo(),
            snapshot.valence(),
            snapshot.resolvedAt()
        );
    }

    private EmsTrackAudioFeatures toEmsAudioFeatures(
        EmsCollectedTrackEntity track,
        LastFmAudioFeatureInferenceService.InferredAudioFeatureSnapshot snapshot
    ) {
        Integer durationMs = snapshot.durationMs() == null ? track.getDurationMs() : snapshot.durationMs();
        return new EmsTrackAudioFeatures(
            null,
            snapshot.source(),
            snapshot.audioFeaturesFilled(),
            null,
            track.getPlatformExternalUrl(),
            track.getSpotifyUri(),
            "audio_features",
            durationMs,
            null,
            null,
            null,
            snapshot.acousticness(),
            snapshot.danceability(),
            snapshot.energy(),
            snapshot.instrumentalness(),
            snapshot.liveness(),
            null,
            snapshot.speechiness(),
            snapshot.tempo(),
            snapshot.valence(),
            snapshot.resolvedAt()
        );
    }

    private PmsTrackAudioFeatures toPmsAudioFeatures(
        PmsUserTrackEntity track,
        AudioFeatureLlmSearchInferenceService.InferredAudioFeatureSnapshot snapshot
    ) {
        Integer durationMs = snapshot.durationMs() == null ? currentDurationMs(track.getAudioFeatures()) : snapshot.durationMs();
        return new PmsTrackAudioFeatures(
            null,
            snapshot.source(),
            snapshot.audioFeaturesFilled(),
            null,
            track.getPlatformExternalUrl(),
            track.getPlatformUri(),
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
            snapshot.resolvedAt()
        );
    }

    private EmsTrackAudioFeatures toEmsAudioFeatures(
        EmsCollectedTrackEntity track,
        AudioFeatureLlmSearchInferenceService.InferredAudioFeatureSnapshot snapshot
    ) {
        Integer durationMs = snapshot.durationMs() == null ? track.getDurationMs() : snapshot.durationMs();
        return new EmsTrackAudioFeatures(
            null,
            snapshot.source(),
            snapshot.audioFeaturesFilled(),
            null,
            track.getPlatformExternalUrl(),
            track.getSpotifyUri(),
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
            snapshot.resolvedAt()
        );
    }

    private void mark(Long jobId, ProcessStatus status, Instant now, Counter counter) {
        switch (status.status()) {
            case "completed" -> {
                jobStore.markCompleted(jobId, now);
                counter.completedJobCount++;
            }
            case "unresolved" -> {
                jobStore.markUnresolved(jobId, status.message(), now);
                counter.unresolvedJobCount++;
            }
            default -> {
                jobStore.markFailed(jobId, status.message(), now);
                counter.failedJobCount++;
            }
        }
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 20;
        }
        return Math.min(limit, 200);
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

    private boolean hasCompleteLlmAudioFeatures(AudioFeatureLlmSearchInferenceService.InferredAudioFeatureSnapshot snapshot) {
        return snapshot != null
            && snapshot.audioFeaturesFilled()
            && snapshot.durationMs() != null
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
            && snapshot.valence() != null
            && snapshot.resolvedAt() != null;
    }

    private String audioFeatureSource(String sourcePlatform) {
        return "tidal".equals(sourcePlatform) ? "reccobeats_isrc_match" : "reccobeats_lookup";
    }

    private Integer currentDurationMs(PmsTrackAudioFeatures audioFeatures) {
        return audioFeatures == null ? null : audioFeatures.getDurationMs();
    }

    private Long parseLong(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String buildSpotifyUri(String spotifyTrackId) {
        return hasText(spotifyTrackId) ? "spotify:track:%s".formatted(spotifyTrackId) : null;
    }

    private String firstNonBlank(String first, String second) {
        return hasText(first) ? first : second;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }

    private static class Counter {
        private int completedJobCount;
        private int retryWaitJobCount;
        private int unresolvedJobCount;
        private int failedJobCount;
    }

    public record ProcessCompletionResult(
        int claimedJobCount,
        int completedJobCount,
        int retryWaitJobCount,
        int unresolvedJobCount,
        int failedJobCount
    ) {}

    private record ProcessStatus(String status, String message) {
        static ProcessStatus completed() {
            return new ProcessStatus("completed", null);
        }

        static ProcessStatus unresolved(String message) {
            return new ProcessStatus("unresolved", message);
        }

        static ProcessStatus failed(String message) {
            return new ProcessStatus("failed", message);
        }
    }
}
