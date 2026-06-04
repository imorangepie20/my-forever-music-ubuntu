package io.myforevermusic.api.modules.publiccuration.application;

import io.myforevermusic.api.common.error.ApiResourceNotFoundException;
import io.myforevermusic.api.modules.platform.application.PlatformReconnectRequiredException;
import io.myforevermusic.api.modules.platform.application.TidalPlaybackTargetResolverService;
import io.myforevermusic.api.modules.platform.application.TidalPlaybackTargetResolverService.TidalPlaybackTarget;
import io.myforevermusic.api.modules.platform.application.TidalPlaybackTargetResolverService.TrackQuery;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PublicCurationPlayableCandidateService {

    private static final int RAW_LIMIT_MULTIPLIER = 3;

    private final PublicCurationCandidatePoolStore candidatePoolStore;
    private final TidalPlaybackTargetResolverService tidalResolver;

    public PublicCurationPlayableCandidateService(
        PublicCurationCandidatePoolStore candidatePoolStore,
        TidalPlaybackTargetResolverService tidalResolver
    ) {
        this.candidatePoolStore = candidatePoolStore;
        this.tidalResolver = tidalResolver;
    }

    public PreparedCandidateBatch prepare(PrepareCommand command) {
        int playableLimit = Math.max(1, command.playableLimit());
        int rawLimit = Math.max(playableLimit, playableLimit * RAW_LIMIT_MULTIPLIER);
        List<PublicCurationCandidatePoolStore.CandidateTrack> rawCandidates = candidatePoolStore.findCandidates(
            new PublicCurationCandidatePoolStore.CandidateQuery(rawLimit, false)
        );

        ArrayList<PublicCurationCandidatePoolStore.CandidateTrack> playableCandidates = new ArrayList<>();
        int nativeTidalCount = 0;
        int resolvedCount = 0;
        int resolveAttemptCount = 0;
        int resolveFailedCount = 0;
        int skippedMetadataCount = 0;
        int inspectedCandidateCount = rawCandidates.size();
        Set<String> selectedIdentityKeys = new HashSet<>();

        for (PublicCurationCandidatePoolStore.CandidateTrack candidate : rawCandidates) {
            if (playableCandidates.size() >= playableLimit) {
                break;
            }
            if (candidate.hasTidalPlaybackTarget()) {
                if (addPlayableCandidate(playableCandidates, selectedIdentityKeys, candidate)) {
                    nativeTidalCount += 1;
                }
                continue;
            }
            if (!canResolve(candidate)) {
                skippedMetadataCount += 1;
                continue;
            }

            resolveAttemptCount += 1;
            try {
                TidalPlaybackTarget target = tidalResolver.resolve(command.adminUserId(), toTrackQuery(candidate));
                PublicCurationCandidatePoolStore.CandidateTrack resolvedCandidate = candidate.withResolvedTidalTarget(
                    target.tidalTrackId(),
                    target.tidalUri(),
                    target.platformExternalUrl(),
                    target.albumImageUrl(),
                    target.durationMs()
                );
                if (addPlayableCandidate(playableCandidates, selectedIdentityKeys, resolvedCandidate)) {
                    resolvedCount += 1;
                }
            } catch (ApiResourceNotFoundException | PlatformReconnectRequiredException | IllegalArgumentException exception) {
                resolveFailedCount += 1;
            }
        }

        if (playableCandidates.size() < playableLimit) {
            List<PublicCurationCandidatePoolStore.CandidateTrack> nativeFallbackCandidates = candidatePoolStore.findCandidates(
                new PublicCurationCandidatePoolStore.CandidateQuery(playableLimit, true)
            );
            inspectedCandidateCount += nativeFallbackCandidates.size();
            for (PublicCurationCandidatePoolStore.CandidateTrack candidate : nativeFallbackCandidates) {
                if (playableCandidates.size() >= playableLimit) {
                    break;
                }
                if (candidate.hasTidalPlaybackTarget()
                    && addPlayableCandidate(playableCandidates, selectedIdentityKeys, candidate)) {
                    nativeTidalCount += 1;
                }
            }
        }

        CandidatePreparationSummary summary = new CandidatePreparationSummary(
            inspectedCandidateCount,
            nativeTidalCount,
            resolveAttemptCount,
            resolvedCount,
            resolveFailedCount,
            skippedMetadataCount,
            playableCandidates.size(),
            Math.max(0, inspectedCandidateCount - playableCandidates.size()),
            resolveAttemptCount == 0 ? 0.0d : round4((double) resolvedCount / resolveAttemptCount)
        );

        if (playableCandidates.size() < command.targetTrackCount()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Not enough playable public curation candidates. raw=%d, playable=%d, target=%d"
                    .formatted(rawCandidates.size(), playableCandidates.size(), command.targetTrackCount())
            );
        }

        return new PreparedCandidateBatch(List.copyOf(playableCandidates), summary);
    }

    private boolean addPlayableCandidate(
        List<PublicCurationCandidatePoolStore.CandidateTrack> playableCandidates,
        Set<String> selectedIdentityKeys,
        PublicCurationCandidatePoolStore.CandidateTrack candidate
    ) {
        String identityKey = identityKey(candidate);
        if (!selectedIdentityKeys.add(identityKey)) {
            return false;
        }
        playableCandidates.add(candidate);
        return true;
    }

    private String identityKey(PublicCurationCandidatePoolStore.CandidateTrack candidate) {
        if (hasText(candidate.tidalTrackId())) {
            return "tidal:" + candidate.tidalTrackId().trim().toLowerCase(java.util.Locale.ROOT);
        }
        return "source:" + candidate.sourceScope() + ":" + candidate.sourceId();
    }

    private boolean canResolve(PublicCurationCandidatePoolStore.CandidateTrack candidate) {
        return hasText(candidate.title()) && hasText(candidate.artistName());
    }

    private TrackQuery toTrackQuery(PublicCurationCandidatePoolStore.CandidateTrack candidate) {
        return new TrackQuery(
            candidate.title(),
            candidate.artistName(),
            candidate.sourcePlatform(),
            candidate.sourceId(),
            null,
            null,
            candidate.isrc(),
            candidate.durationMs()
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private double round4(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    public record PrepareCommand(
        String adminUserId,
        int playableLimit,
        int targetTrackCount
    ) {
    }

    public record PreparedCandidateBatch(
        List<PublicCurationCandidatePoolStore.CandidateTrack> candidates,
        CandidatePreparationSummary summary
    ) {
    }

    public record CandidatePreparationSummary(
        int rawCount,
        int nativeTidalCount,
        int resolveAttemptCount,
        int resolvedCount,
        int resolveFailedCount,
        int skippedMetadataCount,
        int playableCount,
        int excludedCount,
        double resolveSuccessRatio
    ) {
        public Map<String, Object> toJsonMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("raw_count", rawCount);
            values.put("native_tidal_count", nativeTidalCount);
            values.put("resolve_attempt_count", resolveAttemptCount);
            values.put("resolved_count", resolvedCount);
            values.put("resolve_failed_count", resolveFailedCount);
            values.put("skipped_metadata_count", skippedMetadataCount);
            values.put("playable_count", playableCount);
            values.put("excluded_count", excludedCount);
            values.put("resolve_success_ratio", resolveSuccessRatio);
            return values;
        }
    }
}
