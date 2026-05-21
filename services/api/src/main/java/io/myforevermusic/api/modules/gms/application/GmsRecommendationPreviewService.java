package io.myforevermusic.api.modules.gms.application;

import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import io.myforevermusic.api.modules.auth.application.AuthRegisteredAccount;
import io.myforevermusic.api.modules.gms.infrastructure.ai.AiRecommendationPreviewClient;
import io.myforevermusic.api.modules.gms.infrastructure.ai.AiSasrecRankingClient;
import io.myforevermusic.api.modules.gms.presentation.GmsRecommendationPreviewRequest;
import io.myforevermusic.api.modules.gms.presentation.GmsRecommendationPreviewResponse;
import io.myforevermusic.api.modules.platform.application.LastFmScrobbleStore;
import io.myforevermusic.api.modules.platform.infrastructure.lastfm.LastFmWebApiClient;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsTrackAudioFeatures;
import io.myforevermusic.api.modules.recommendation.application.AxisEvidence;
import io.myforevermusic.api.modules.recommendation.application.AudioTasteFeatureQuality;
import io.myforevermusic.api.modules.recommendation.application.AudioTasteModeAffinityService;
import io.myforevermusic.api.modules.recommendation.application.AudioTasteProfileService;
import io.myforevermusic.api.modules.recommendation.application.AudioTasteScoringService;
import io.myforevermusic.api.modules.recommendation.application.AudioTasteTrackFeature;
import io.myforevermusic.api.modules.recommendation.application.ColdStartFallbackService;
import io.myforevermusic.api.modules.recommendation.application.PlaylistQualityEvaluation;
import io.myforevermusic.api.modules.recommendation.application.PlaylistQualityEvaluator;
import io.myforevermusic.api.modules.recommendation.application.RecommendationAuditLogStore;
import io.myforevermusic.api.modules.recommendation.application.RecommendationAxisEvidenceBuilder;
import io.myforevermusic.api.modules.recommendation.application.RecommendationReranker;
import io.myforevermusic.api.modules.recommendation.application.RecommendationSnapshotService;
import io.myforevermusic.api.modules.recommendation.application.UserPersonalizationProfileStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

@Service
public class GmsRecommendationPreviewService {

    private final AiRecommendationPreviewClient aiRecommendationPreviewClient;
    private final Optional<AiSasrecRankingClient> aiSasrecRankingClient;
    private final AuthAccountStore authAccountStore;
    private final LastFmScrobbleStore lastFmScrobbleStore;
    private final PmsUserLibraryStore pmsUserLibraryStore;
    private final Optional<LastFmWebApiClient> lastFmWebApiClient;
    private final RecommendationSnapshotService recommendationSnapshotService;
    private final RecommendationAuditLogStore recommendationAuditLogStore;
    private final PlaylistQualityEvaluator playlistQualityEvaluator;
    private final UserPersonalizationProfileStore userPersonalizationProfileStore;
    private final RecommendationReranker recommendationReranker;
    private final AudioTasteProfileService audioTasteProfileService;
    private final AudioTasteScoringService audioTasteScoringService;
    private final AudioTasteModeAffinityService audioTasteModeAffinityService;
    private final ColdStartFallbackService coldStartFallbackService;

    public GmsRecommendationPreviewService(
        AiRecommendationPreviewClient aiRecommendationPreviewClient,
        Optional<AiSasrecRankingClient> aiSasrecRankingClient,
        AuthAccountStore authAccountStore,
        LastFmScrobbleStore lastFmScrobbleStore,
        PmsUserLibraryStore pmsUserLibraryStore,
        Optional<LastFmWebApiClient> lastFmWebApiClient,
        RecommendationSnapshotService recommendationSnapshotService,
        RecommendationAuditLogStore recommendationAuditLogStore,
        PlaylistQualityEvaluator playlistQualityEvaluator,
        UserPersonalizationProfileStore userPersonalizationProfileStore,
        RecommendationReranker recommendationReranker,
        AudioTasteProfileService audioTasteProfileService,
        AudioTasteScoringService audioTasteScoringService,
        AudioTasteModeAffinityService audioTasteModeAffinityService,
        ColdStartFallbackService coldStartFallbackService
    ) {
        this.aiRecommendationPreviewClient = aiRecommendationPreviewClient;
        this.aiSasrecRankingClient = aiSasrecRankingClient;
        this.authAccountStore = authAccountStore;
        this.lastFmScrobbleStore = lastFmScrobbleStore;
        this.pmsUserLibraryStore = pmsUserLibraryStore;
        this.lastFmWebApiClient = lastFmWebApiClient;
        this.recommendationSnapshotService = recommendationSnapshotService;
        this.recommendationAuditLogStore = recommendationAuditLogStore;
        this.playlistQualityEvaluator = playlistQualityEvaluator;
        this.userPersonalizationProfileStore = userPersonalizationProfileStore;
        this.recommendationReranker = recommendationReranker;
        this.audioTasteProfileService = audioTasteProfileService;
        this.audioTasteScoringService = audioTasteScoringService;
        this.audioTasteModeAffinityService = audioTasteModeAffinityService;
        this.coldStartFallbackService = coldStartFallbackService;
    }

    public GmsRecommendationPreviewResponse previewRecommendations(GmsRecommendationPreviewRequest request) {
        List<String> enrichmentWarnings = new ArrayList<>();
        List<String> appliedSasrecModelVersions = new ArrayList<>();
        List<String> appliedAudioTasteModelVersions = new ArrayList<>();
        GmsRecommendationPreviewRequest enrichedRequest = enrichWithLastFmArtists(request, enrichmentWarnings);
        GmsRecommendationPreviewResponse response = aiRecommendationPreviewClient.requestPreview(enrichedRequest);
        List<GmsRecommendationPreviewResponse.RecommendationItem> playableItems = projectPlayableItems(
            enrichedRequest,
            response.items(),
            enrichmentWarnings,
            appliedSasrecModelVersions,
            appliedAudioTasteModelVersions
        );
        boolean coldStartFallbackUsed = false;
        if ((response.items() != null && !response.items().isEmpty()) && playableItems.isEmpty()) {
            if (coldStartFallbackService.isColdStart(enrichedRequest.userId())) {
                List<GmsRecommendationPreviewResponse.RecommendationItem> fallback =
                    coldStartFallbackService.fallbackItems(enrichedRequest.userId(), enrichedRequest.limit());
                if (!fallback.isEmpty()) {
                    playableItems = renumberRanks(fallback);
                    coldStartFallbackUsed = true;
                    enrichmentWarnings.add(
                        "Cold-start fallback applied: showing %d EMS pool track(s) because the user has no imported PMS library yet."
                            .formatted(playableItems.size())
                    );
                }
            }
            if (!coldStartFallbackUsed) {
                throw new IllegalArgumentException(
                    "GMS recommendations require imported PMS user library tracks. Import a real playlist before requesting recommendations."
                );
            }
        }

        if (!playableItems.isEmpty() && !coldStartFallbackUsed) {
            enrichmentWarnings.add(
                "GMS preview items were resolved against the PMS user library so they can be played inside the rebuild shell."
            );
        }

        GmsRecommendationPreviewResponse finalResponse;
        if (enrichmentWarnings.isEmpty()) {
            finalResponse = playableItems.isEmpty() ? response : new GmsRecommendationPreviewResponse(
                response.requestId(),
                response.generatedAt(),
                response.service(),
                response.status(),
                applyModelContexts(response.context(), appliedSasrecModelVersions, appliedAudioTasteModelVersions),
                response.inputSummary(),
                playableItems,
                response.warnings()
            );
            finalResponse = applyPersonalizationRerank(finalResponse, enrichedRequest);
            finalResponse = withAxisEvidence(finalResponse, enrichedRequest);
            recommendationSnapshotService.recordGmsPreview(enrichedRequest, finalResponse);
            recordPreviewAudit(enrichedRequest, finalResponse);
            return finalResponse;
        }

        List<String> mergedWarnings = new ArrayList<>(response.warnings());
        mergedWarnings.addAll(enrichmentWarnings);

        finalResponse = new GmsRecommendationPreviewResponse(
            response.requestId(),
            response.generatedAt(),
            response.service(),
            response.status(),
            applyModelContexts(response.context(), appliedSasrecModelVersions, appliedAudioTasteModelVersions),
            response.inputSummary(),
            playableItems.isEmpty() ? response.items() : playableItems,
            List.copyOf(mergedWarnings)
        );
        finalResponse = applyPersonalizationRerank(finalResponse, enrichedRequest);
        finalResponse = withAxisEvidence(finalResponse, enrichedRequest);
        recommendationSnapshotService.recordGmsPreview(enrichedRequest, finalResponse);
        recordPreviewAudit(enrichedRequest, finalResponse);
        return finalResponse;
    }

    /**
     * Cold-start fallback 으로 주어진 EMS-backed item 들에 1..N rank 를 재부여한다.
     * (원본은 rank=null 인 placeholder 상태이고, 후속 axis evidence/snapshot 이 rank 를 기대함.)
     */
    private List<GmsRecommendationPreviewResponse.RecommendationItem> renumberRanks(
        List<GmsRecommendationPreviewResponse.RecommendationItem> items
    ) {
        List<GmsRecommendationPreviewResponse.RecommendationItem> result = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            GmsRecommendationPreviewResponse.RecommendationItem item = items.get(index);
            result.add(new GmsRecommendationPreviewResponse.RecommendationItem(
                index + 1,
                item.trackId(),
                item.title(),
                item.artistName(),
                item.sourcePlatform(),
                item.sourcePlaylistId(),
                item.sourcePlaylistTitle(),
                item.albumTitle(),
                item.albumImageUrl(),
                item.platformExternalUrl(),
                item.platformUri(),
                item.previewUrl(),
                item.spotifyTrackId(),
                item.audioFeatureTrackId(),
                item.durationMs(),
                item.score(),
                item.sourceSpace(),
                item.energyLevel(),
                item.reason(),
                item.axisEvidence(),
                item.tasteModeAffinity(),
                item.tasteModeGate()
            ));
        }
        return result;
    }

    /**
     * Phase 5 sub-item 2: 사용자 personalization 프로필이 있으면 그 신호로 후보 순서를 재정렬한다.
     * 프로필이 없거나 비어 있으면 입력을 그대로 돌려준다.
     */
    private GmsRecommendationPreviewResponse applyPersonalizationRerank(
        GmsRecommendationPreviewResponse response,
        GmsRecommendationPreviewRequest request
    ) {
        if (response.items() == null || response.items().isEmpty()) {
            return response;
        }
        String userId = request.userId();
        if (userId == null || userId.isBlank()) {
            return response;
        }
        return userPersonalizationProfileStore.findByUserId(userId)
            .map(profile -> {
                RecommendationReranker.RerankResult result =
                    recommendationReranker.rerank(response.items(), profile);
                if (!result.orderChanged() && result.matchedCount() == 0) {
                    return response;
                }
                List<String> mergedWarnings = new ArrayList<>(response.warnings());
                mergedWarnings.add(
                    "Session reranked %d candidate(s) via personalization profile (order_changed=%s).".formatted(
                        result.matchedCount(),
                        result.orderChanged()
                    )
                );
                return new GmsRecommendationPreviewResponse(
                    response.requestId(),
                    response.generatedAt(),
                    response.service(),
                    response.status(),
                    response.context(),
                    response.inputSummary(),
                    result.items(),
                    List.copyOf(mergedWarnings)
                );
            })
            .orElse(response);
    }

    private void recordPreviewAudit(
        GmsRecommendationPreviewRequest request,
        GmsRecommendationPreviewResponse response
    ) {
        if (request.userId() == null || request.userId().isBlank()) {
            return;
        }
        int itemCount = response.items() == null ? 0 : response.items().size();
        boolean sasrecApplied = hasSasrecModel(response);
        String fallbackReason = resolveFallbackReason(response, sasrecApplied);
        recommendationAuditLogStore.save(new RecommendationAuditLogStore.AuditDraft(
            request.userId(),
            response.requestId(),
            request.requestId(),
            RecommendationAuditLogStore.EVENT_PREVIEW_GENERATED,
            "gms",
            resolveAuditModelVersion(response),
            null,
            null,
            itemCount,
            sasrecApplied,
            fallbackReason,
            null,
            null,
            request.playlistId(),
            null,
            response.generatedAt() == null ? Instant.now() : response.generatedAt()
        ));
    }

    private String resolveFallbackReason(GmsRecommendationPreviewResponse response, boolean sasrecApplied) {
        if (response.warnings() != null) {
            for (String warning : response.warnings()) {
                if (warning != null && warning.startsWith("Cold-start fallback applied:")) {
                    return "cold_start_pms_empty";
                }
            }
        }
        return sasrecApplied ? null : "sasrec_not_applied";
    }

    private String resolveAuditModelVersion(GmsRecommendationPreviewResponse response) {
        if (hasSasrecModel(response)) {
            return response.context().engine();
        }
        if (hasAudioTasteModel(response)) {
            return response.context().engine();
        }
        return "gms-baseline-v1";
    }

    private boolean hasSasrecModel(GmsRecommendationPreviewResponse response) {
        return response.context() != null
            && response.context().engine() != null
            && response.context().engine().contains("sasrec:");
    }

    private boolean hasAudioTasteModel(GmsRecommendationPreviewResponse response) {
        return response.context() != null
            && response.context().engine() != null
            && response.context().engine().contains("audio-taste:");
    }

    private GmsRecommendationPreviewResponse withAxisEvidence(
        GmsRecommendationPreviewResponse response,
        GmsRecommendationPreviewRequest request
    ) {
        if (response.items() == null || response.items().isEmpty()) {
            return response;
        }
        PlaylistQualityEvaluation playlistEvaluation = playlistQualityEvaluator.evaluate(response.items());
        Double novelty = computeNoveltyScore(request.familiarityBias());
        List<GmsRecommendationPreviewResponse.RecommendationItem> enriched = response.items().stream()
            .map(item -> {
                List<AxisEvidence> evidence = RecommendationAxisEvidenceBuilder.build(
                    item.score(),
                    novelty,
                    playlistEvaluation,
                    computeConfidenceScore(item)
                );
                return item.withAxisEvidence(evidence);
            })
            .toList();
        return new GmsRecommendationPreviewResponse(
            response.requestId(),
            response.generatedAt(),
            response.service(),
            response.status(),
            response.context(),
            response.inputSummary(),
            enriched,
            response.warnings()
        );
    }

    private Double computeNoveltyScore(Integer familiarityBias) {
        if (familiarityBias == null) {
            return null;
        }
        int clamped = Math.min(5, Math.max(1, familiarityBias));
        return Math.min(1.0d, Math.max(0.0d, 1.0d - ((clamped - 1.0d) / 4.0d)));
    }

    private Double computeConfidenceScore(GmsRecommendationPreviewResponse.RecommendationItem item) {
        double score = 0.55d;
        if (item.trackId() != null && !item.trackId().isBlank()) {
            score += 0.15d;
        }
        if (item.audioFeatureTrackId() != null && !item.audioFeatureTrackId().isBlank()) {
            score += 0.15d;
        }
        if (item.sourcePlaylistId() != null && !item.sourcePlaylistId().isBlank()) {
            score += 0.10d;
        }
        return Math.min(1.0d, Math.max(0.0d, score));
    }

    private List<GmsRecommendationPreviewResponse.RecommendationItem> projectPlayableItems(
        GmsRecommendationPreviewRequest request,
        List<GmsRecommendationPreviewResponse.RecommendationItem> aiItems,
        List<String> enrichmentWarnings,
        List<String> appliedSasrecModelVersions,
        List<String> appliedAudioTasteModelVersions
    ) {
        if (aiItems == null || aiItems.isEmpty() || request.userId() == null || request.userId().isBlank()) {
            return List.of();
        }

        List<LibraryCandidateTrack> candidates = resolveLibraryCandidates(request.userId(), request.playlistId());
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<String> seedTrackIds = normalizeValues(request.seedTrackIds());
        List<String> seedArtists = normalizeValues(request.seedArtistNames());
        List<String> seedGenres = normalizeValues(request.seedGenres());
        Set<String> seenTrackIds = new HashSet<>();

        List<RankedLibraryCandidate> rankedCandidates = candidates.stream()
            .map(candidate -> new RankedLibraryCandidate(
                candidate,
                roundScore(computeAffinity(candidate, request, seedTrackIds, seedArtists, seedGenres))
            ))
            .sorted(Comparator.comparingDouble(RankedLibraryCandidate::affinityScore).reversed()
                .thenComparing((RankedLibraryCandidate ranked) -> ranked.candidate().seed()).reversed()
                .thenComparing((RankedLibraryCandidate ranked) -> requestedPlaylistMatch(request.playlistId(), ranked.candidate())).reversed()
                .thenComparing(ranked -> ranked.candidate().sortOrder())
                .thenComparing(ranked -> ranked.candidate().trackId()))
            .filter(ranked -> seenTrackIds.add(ranked.candidate().trackId()))
            .toList();

        if (rankedCandidates.isEmpty()) {
            return List.of();
        }

        rankedCandidates = applySasrecRanking(
            request,
            rankedCandidates,
            aiItems.size(),
            enrichmentWarnings,
            appliedSasrecModelVersions
        );
        AudioTasteProfileService.Profile audioTasteProfile = resolveAudioTasteProfile(request, rankedCandidates);
        rankedCandidates = applyAudioTasteRanking(
            request,
            rankedCandidates,
            audioTasteProfile,
            enrichmentWarnings,
            appliedAudioTasteModelVersions
        );
        rankedCandidates = rankedCandidates.stream()
            .limit(aiItems.size())
            .toList();
        rankedCandidates = applyTasteModeAffinity(request, rankedCandidates, audioTasteProfile);

        List<RankedLibraryCandidate> finalRankedCandidates = rankedCandidates;
        return IntStream.range(0, Math.min(aiItems.size(), finalRankedCandidates.size()))
            .mapToObj(index -> toRecommendationItem(aiItems.get(index), finalRankedCandidates.get(index)))
            .toList();
    }

    private List<LibraryCandidateTrack> resolveLibraryCandidates(String userId, String requestedPlaylistId) {
        List<PmsUserLibraryStore.LibraryPlaylistState> playlists = pmsUserLibraryStore.findPlaylists(userId);
        if (playlists.isEmpty()) {
            return List.of();
        }

        return playlists.stream()
            .flatMap(playlist -> playlist.tracks().stream()
                .map(track -> new LibraryCandidateTrack(
                    playlist.playlistId(),
                    playlist.title(),
                    playlist.sourcePlatform(),
                    playlist.coverImageUrl(),
                    playlist.platformExternalUrl(),
                    playlist.platformUri(),
                    track.trackId(),
                    track.title(),
                    track.artistName(),
                    track.primaryGenre(),
                    track.albumTitle(),
                    track.albumImageUrl(),
                    track.platformExternalUrl(),
                    track.platformUri(),
                    track.previewUrl(),
                    track.seed(),
                    track.sortOrder(),
                    track.audioFeatures()
                )))
            .sorted(Comparator
                .comparing((LibraryCandidateTrack candidate) -> requestedPlaylistMatch(requestedPlaylistId, candidate))
                .reversed()
                .thenComparing(LibraryCandidateTrack::seed)
                .reversed()
                .thenComparing(LibraryCandidateTrack::sortOrder))
            .toList();
    }

    private GmsRecommendationPreviewResponse.RecommendationItem toRecommendationItem(
        GmsRecommendationPreviewResponse.RecommendationItem aiItem,
        RankedLibraryCandidate rankedCandidate
    ) {
        LibraryCandidateTrack candidate = rankedCandidate.candidate();
        String mergedReason = aiItem.reason();
        if (mergedReason == null || mergedReason.isBlank()) {
            mergedReason = buildLibraryReason(candidate);
        } else {
            mergedReason = "%s %s".formatted(aiItem.reason(), buildLibraryReason(candidate));
        }
        if (rankedCandidate.sasrecRanked()) {
            mergedReason = "%s SASRec personalized ranking adjusted this candidate using recent user sequence context.".formatted(
                mergedReason
            );
        }
        if (rankedCandidate.audioTasteRanked()) {
            String tokenSummary = rankedCandidate.audioTasteTokens().isEmpty()
                ? "audio_profile_distance"
                : String.join(", ", rankedCandidate.audioTasteTokens());
            mergedReason = "%s Audio taste matched this candidate via %s.".formatted(mergedReason, tokenSummary);
        }

        return new GmsRecommendationPreviewResponse.RecommendationItem(
            aiItem.rank(),
            candidate.trackId(),
            candidate.title(),
            candidate.artistName(),
            candidate.sourcePlatform(),
            candidate.playlistId(),
            candidate.playlistTitle(),
            candidate.albumTitle(),
            candidate.albumImageUrl(),
            firstNonBlank(candidate.platformExternalUrl(), candidate.playlistExternalUrl()),
            firstNonBlank(candidate.platformUri(), candidate.audioFeatures() == null ? null : candidate.audioFeatures().getSpotifyUri()),
            candidate.previewUrl(),
            candidate.audioFeatures() == null ? null : candidate.audioFeatures().getSpotifyTrackId(),
            candidate.audioFeatures() == null ? null : candidate.audioFeatures().getAudioFeatureTrackId(),
            candidate.audioFeatures() == null ? null : candidate.audioFeatures().getDurationMs(),
            rankedCandidate.affinityScore(),
            aiItem.sourceSpace(),
            aiItem.energyLevel(),
            mergedReason,
            List.of(),
            GmsRecommendationPreviewResponse.TasteModeAffinityItem.from(rankedCandidate.tasteModeAffinity()),
            null
        );
    }

    private double computeAffinity(
        LibraryCandidateTrack candidate,
        GmsRecommendationPreviewRequest request,
        List<String> seedTrackIds,
        List<String> seedArtists,
        List<String> seedGenres
    ) {
        double score = 0.42d;
        if (requestedPlaylistMatch(request.playlistId(), candidate)) {
            score += 0.09d;
        }
        if (candidate.seed()) {
            score += 0.07d;
        }
        if (seedTrackIds.contains(normalizeValue(candidate.trackId()))) {
            score += 0.15d;
        }
        if (seedArtists.contains(normalizeValue(candidate.artistName()))) {
            score += 0.16d;
        }
        if (seedGenres.contains(normalizeValue(candidate.primaryGenre()))) {
            score += 0.12d;
        }

        score += 0.14d * energyAlignment(candidate.audioFeatures(), request.energyLevel());
        score += 0.09d * moodAlignment(candidate, request.mood());

        return Math.min(0.99d, score);
    }

    private List<RankedLibraryCandidate> applySasrecRanking(
        GmsRecommendationPreviewRequest request,
        List<RankedLibraryCandidate> rankedCandidates,
        int limit,
        List<String> enrichmentWarnings,
        List<String> appliedSasrecModelVersions
    ) {
        if (aiSasrecRankingClient.isEmpty() || rankedCandidates.size() < 2 || limit < 1) {
            return rankedCandidates;
        }

        List<String> contextTrackIds = resolveSasrecContextTrackIds(request, rankedCandidates);
        if (contextTrackIds.isEmpty()) {
            return rankedCandidates;
        }

        List<String> candidateTrackIds = rankedCandidates.stream()
            .map(ranked -> ranked.candidate().trackId())
            .filter(Objects::nonNull)
            .filter(trackId -> !trackId.isBlank())
            .distinct()
            .toList();
        if (candidateTrackIds.isEmpty()) {
            return rankedCandidates;
        }

        Optional<AiSasrecRankingClient.SasrecRankingResponse> rankingResponse = aiSasrecRankingClient.get()
            .rankCandidates(
                request.userId(),
                contextTrackIds,
                candidateTrackIds,
                Math.min(100, Math.max(limit, candidateTrackIds.size()))
            );
        if (rankingResponse.isEmpty()) {
            return rankedCandidates;
        }

        AiSasrecRankingClient.SasrecRankingResponse response = rankingResponse.get();
        if (response.warnings() != null && !response.warnings().isEmpty()) {
            response.warnings().forEach(warning -> enrichmentWarnings.add("SASRec ranking note: " + warning));
        }
        if (!"ok".equals(response.status()) || response.rankedCandidates() == null || response.rankedCandidates().isEmpty()) {
            enrichmentWarnings.add(
                "SASRec ranking was skipped because model '%s' returned status '%s'.".formatted(
                    response.modelVersion(),
                    response.status()
                )
            );
            return rankedCandidates;
        }

        Map<String, Integer> rankByTrackId = response.rankedCandidates().stream()
            .collect(java.util.stream.Collectors.toMap(
                AiSasrecRankingClient.SasrecRankedCandidate::trackId,
                AiSasrecRankingClient.SasrecRankedCandidate::rank,
                Math::min
            ));
        Map<String, Double> scoreByTrackId = response.rankedCandidates().stream()
            .collect(java.util.stream.Collectors.toMap(
                AiSasrecRankingClient.SasrecRankedCandidate::trackId,
                AiSasrecRankingClient.SasrecRankedCandidate::score,
                Math::max
            ));
        double minScore = scoreByTrackId.values().stream().mapToDouble(Double::doubleValue).min().orElse(0.0d);
        double maxScore = scoreByTrackId.values().stream().mapToDouble(Double::doubleValue).max().orElse(minScore);

        List<RankedLibraryCandidate> reranked = rankedCandidates.stream()
            .map(ranked -> blendSasrecScore(ranked, scoreByTrackId.get(ranked.candidate().trackId()), minScore, maxScore))
            .sorted((left, right) -> {
                int leftRank = rankByTrackId.getOrDefault(left.candidate().trackId(), Integer.MAX_VALUE);
                int rightRank = rankByTrackId.getOrDefault(right.candidate().trackId(), Integer.MAX_VALUE);
                if (leftRank != rightRank) {
                    return Integer.compare(leftRank, rightRank);
                }
                int scoreOrder = Double.compare(right.affinityScore(), left.affinityScore());
                if (scoreOrder != 0) {
                    return scoreOrder;
                }
                return left.candidate().trackId().compareTo(right.candidate().trackId());
            })
            .toList();

        enrichmentWarnings.add(
            "SASRec model '%s' reranked playable GMS candidates from the PMS library.".formatted(response.modelVersion())
        );
        appliedSasrecModelVersions.add(response.modelVersion());
        return reranked;
    }

    private List<RankedLibraryCandidate> applyAudioTasteRanking(
        GmsRecommendationPreviewRequest request,
        List<RankedLibraryCandidate> rankedCandidates,
        AudioTasteProfileService.Profile profile,
        List<String> enrichmentWarnings,
        List<String> appliedAudioTasteModelVersions
    ) {
        if (rankedCandidates.size() < 2 || request.userId() == null || request.userId().isBlank()) {
            return rankedCandidates;
        }

        if (profile == null || !profile.audioTasteApplicable()) {
            return rankedCandidates;
        }

        List<RankedLibraryCandidate> boosted = rankedCandidates.stream()
            .map(ranked -> {
                AudioTasteTrackFeature feature = toAudioTasteTrackFeature(ranked.candidate());
                AudioTasteScoringService.Score score = audioTasteScoringService.score(profile, feature);
                if (!score.applied()) {
                    return ranked;
                }
                double centered = score.score() - 0.5d;
                double boostWeight = resolveAudioBoostWeight(score);
                double boostedScore = ranked.affinityScore() * (1.0d + (boostWeight * centered));
                return ranked.withAudioTasteScore(clampScore(boostedScore), score.explanationTokens());
            })
            .sorted(Comparator.comparingDouble(RankedLibraryCandidate::affinityScore).reversed()
                .thenComparing((RankedLibraryCandidate ranked) -> ranked.candidate().seed()).reversed()
                .thenComparing((RankedLibraryCandidate ranked) -> requestedPlaylistMatch(request.playlistId(), ranked.candidate())).reversed()
                .thenComparing(ranked -> ranked.candidate().sortOrder())
                .thenComparing(ranked -> ranked.candidate().trackId()))
            .toList();

        long appliedCount = boosted.stream().filter(RankedLibraryCandidate::audioTasteRanked).count();
        if (appliedCount == 0L) {
            return rankedCandidates;
        }

        enrichmentWarnings.add(
            "Audio taste ranking adjusted %d playable GMS candidate(s) via audio-taste:v1 (profile_type=%s, confidence=%.2f, focus=%s)."
                .formatted(appliedCount, profile.profileType(), profile.profileConfidence(), profile.profileFocus())
        );
        appliedAudioTasteModelVersions.add("v1");
        return boosted;
    }

    private AudioTasteProfileService.Profile resolveAudioTasteProfile(
        GmsRecommendationPreviewRequest request,
        List<RankedLibraryCandidate> rankedCandidates
    ) {
        if (rankedCandidates.isEmpty() || request.userId() == null || request.userId().isBlank()) {
            return null;
        }
        if (rankedCandidates.size() < 2 && !request.includeExplanations()) {
            return null;
        }
        return audioTasteProfileService.recompute(request.userId(), null);
    }

    private List<RankedLibraryCandidate> applyTasteModeAffinity(
        GmsRecommendationPreviewRequest request,
        List<RankedLibraryCandidate> rankedCandidates,
        AudioTasteProfileService.Profile profile
    ) {
        if (!request.includeExplanations() || profile == null || profile.tasteModes().isEmpty()) {
            return rankedCandidates;
        }
        return rankedCandidates.stream()
            .map(ranked -> audioTasteModeAffinityService
                .findNearestMode(profile, toAudioTasteTrackFeature(ranked.candidate()))
                .map(ranked::withTasteModeAffinity)
                .orElse(ranked))
            .toList();
    }

    private double resolveAudioBoostWeight(AudioTasteScoringService.Score score) {
        if (!score.applied()) {
            return 0.0d;
        }
        return Math.min(score.maxBoostWeight(), Math.max(0.0d, score.coverageWeight() * 0.12d));
    }

    private AudioTasteTrackFeature toAudioTasteTrackFeature(LibraryCandidateTrack candidate) {
        PmsTrackAudioFeatures audio = candidate.audioFeatures();
        if (audio == null) {
            return null;
        }
        AudioTasteFeatureQuality.Quality quality = AudioTasteFeatureQuality.resolve(
            audio.getAudioFeatureSource(),
            audio.isAudioFeaturesFilled(),
            0.0d,
            0L
        );
        return new AudioTasteTrackFeature(
            "pms_user_track",
            candidate.trackId(),
            candidate.title(),
            candidate.artistName(),
            candidate.sourcePlatform(),
            audio.getAudioFeatureSource(),
            audio.isAudioFeaturesFilled(),
            quality.weight(),
            quality.tier(),
            audio.getAcousticness(),
            audio.getDanceability(),
            audio.getEnergy(),
            audio.getInstrumentalness(),
            audio.getLiveness(),
            audio.getSpeechiness(),
            audio.getTempo(),
            audio.getValence()
        );
    }

    private double clampScore(double value) {
        return roundScore(Math.max(0.01d, Math.min(0.99d, value)));
    }

    private GmsRecommendationPreviewResponse.RecommendationContext applyModelContexts(
        GmsRecommendationPreviewResponse.RecommendationContext context,
        List<String> appliedSasrecModelVersions,
        List<String> appliedAudioTasteModelVersions
    ) {
        return applyAudioTasteContext(
            applySasrecContext(context, appliedSasrecModelVersions),
            appliedAudioTasteModelVersions
        );
    }

    private GmsRecommendationPreviewResponse.RecommendationContext applySasrecContext(
        GmsRecommendationPreviewResponse.RecommendationContext context,
        List<String> appliedSasrecModelVersions
    ) {
        if (context == null || appliedSasrecModelVersions.isEmpty()) {
            return context;
        }

        String modelVersion = appliedSasrecModelVersions.getLast();
        String engine = context.engine();
        String nextEngine = engine == null || engine.isBlank()
            ? "gms-baseline-v1+sasrec:%s".formatted(modelVersion)
            : "%s+sasrec:%s".formatted(engine, modelVersion);
        return new GmsRecommendationPreviewResponse.RecommendationContext(
            context.strategy(),
            nextEngine,
            context.mode(),
            context.mood(),
            context.energyLevel(),
            context.seedBasis()
        );
    }

    private GmsRecommendationPreviewResponse.RecommendationContext applyAudioTasteContext(
        GmsRecommendationPreviewResponse.RecommendationContext context,
        List<String> appliedAudioTasteModelVersions
    ) {
        if (appliedAudioTasteModelVersions.isEmpty()) {
            return context;
        }

        String modelVersion = appliedAudioTasteModelVersions.getLast();
        if (context == null) {
            return new GmsRecommendationPreviewResponse.RecommendationContext(
                "gms-hybrid-blend",
                "gms-baseline-v1+audio-taste:%s".formatted(modelVersion),
                "gms",
                null,
                null,
                List.of()
            );
        }
        String engine = context.engine();
        if (engine != null && engine.contains("audio-taste:%s".formatted(modelVersion))) {
            return context;
        }
        String nextEngine = engine == null || engine.isBlank()
            ? "gms-baseline-v1+audio-taste:%s".formatted(modelVersion)
            : "%s+audio-taste:%s".formatted(engine, modelVersion);
        return new GmsRecommendationPreviewResponse.RecommendationContext(
            context.strategy(),
            nextEngine,
            context.mode(),
            context.mood(),
            context.energyLevel(),
            context.seedBasis()
        );
    }

    private RankedLibraryCandidate blendSasrecScore(
        RankedLibraryCandidate ranked,
        Double sasrecRawScore,
        double minScore,
        double maxScore
    ) {
        if (sasrecRawScore == null) {
            return ranked;
        }
        double normalizedSasrecScore = maxScore == minScore
            ? 1.0d
            : (sasrecRawScore - minScore) / (maxScore - minScore);
        double blendedScore = (ranked.affinityScore() * 0.70d) + (normalizedSasrecScore * 0.30d);
        return new RankedLibraryCandidate(
            ranked.candidate(),
            roundScore(blendedScore),
            true,
            ranked.audioTasteRanked(),
            ranked.audioTasteTokens(),
            ranked.tasteModeAffinity()
        );
    }

    private List<String> resolveSasrecContextTrackIds(
        GmsRecommendationPreviewRequest request,
        List<RankedLibraryCandidate> rankedCandidates
    ) {
        List<String> seedTrackIds = rawDistinctValues(request.seedTrackIds());
        if (!seedTrackIds.isEmpty()) {
            return seedTrackIds;
        }

        List<String> seededCandidateIds = rankedCandidates.stream()
            .filter(ranked -> ranked.candidate().seed())
            .map(ranked -> ranked.candidate().trackId())
            .filter(Objects::nonNull)
            .filter(trackId -> !trackId.isBlank())
            .distinct()
            .toList();
        if (!seededCandidateIds.isEmpty()) {
            return seededCandidateIds;
        }

        return rankedCandidates.stream()
            .filter(ranked -> requestedPlaylistMatch(request.playlistId(), ranked.candidate()))
            .map(ranked -> ranked.candidate().trackId())
            .filter(Objects::nonNull)
            .filter(trackId -> !trackId.isBlank())
            .limit(20)
            .distinct()
            .toList();
    }

    private double energyAlignment(PmsTrackAudioFeatures features, Integer requestedEnergyLevel) {
        if (requestedEnergyLevel == null || features == null || features.getEnergy() == null) {
            return 0.5d;
        }

        double trackEnergyLevel = 1.0d + (features.getEnergy() * 4.0d);
        double delta = Math.abs(trackEnergyLevel - requestedEnergyLevel);
        return Math.max(0.0d, 1.0d - (delta / 4.0d));
    }

    private double moodAlignment(LibraryCandidateTrack candidate, String mood) {
        if (mood == null || mood.isBlank()) {
            return 0.5d;
        }

        String genre = normalizeValue(candidate.primaryGenre());
        PmsTrackAudioFeatures features = candidate.audioFeatures();
        double energy = features == null || features.getEnergy() == null ? 0.5d : features.getEnergy();
        double valence = features == null || features.getValence() == null ? 0.5d : features.getValence();

        return switch (mood) {
            case "focus" -> genre.contains("ambient") || genre.contains("lo-fi") || genre.contains("downtempo")
                ? 0.95d
                : energy <= 0.65d ? 0.72d : 0.45d;
            case "calm" -> valence <= 0.6d && energy <= 0.55d ? 0.9d : 0.5d;
            case "upbeat" -> energy >= 0.65d || valence >= 0.6d ? 0.92d : 0.46d;
            case "melancholy" -> valence <= 0.45d ? 0.88d : 0.44d;
            case "discovery" -> 0.68d;
            default -> 0.5d;
        };
    }

    private String buildLibraryReason(LibraryCandidateTrack candidate) {
        List<String> details = new ArrayList<>();
        if (candidate.seed()) {
            details.add("It already behaves like a strong PMS anchor track.");
        }
        if (candidate.primaryGenre() != null && !candidate.primaryGenre().isBlank()) {
            details.add("It preserves the %s direction.".formatted(candidate.primaryGenre()));
        }
        if (candidate.playlistTitle() != null && !candidate.playlistTitle().isBlank()) {
            details.add("It was pulled from '%s' so the preview stays playable.".formatted(candidate.playlistTitle()));
        }
        if (details.isEmpty()) {
            details.add("It was resolved from the synced PMS user library so it stays playable.");
        }
        return String.join(" ", details);
    }

    private boolean requestedPlaylistMatch(String requestedPlaylistId, LibraryCandidateTrack candidate) {
        return requestedPlaylistId != null
            && !requestedPlaylistId.isBlank()
            && requestedPlaylistId.equals(candidate.playlistId());
    }

    private List<String> normalizeValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        return values.stream()
            .map(this::normalizeValue)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    }

    private List<String> rawDistinctValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        return values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    }

    private String normalizeValue(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private double roundScore(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback == null || fallback.isBlank() ? null : fallback;
    }

    private GmsRecommendationPreviewRequest enrichWithLastFmArtists(
        GmsRecommendationPreviewRequest request,
        List<String> enrichmentWarnings
    ) {
        if (request.userId() == null || request.userId().isBlank()) {
            return request;
        }

        Optional<AuthRegisteredAccount> account = authAccountStore.findByUserId(request.userId());
        if (account.isEmpty() || account.get().lastFmUsername() == null || account.get().lastFmUsername().isBlank()) {
            return request;
        }

        String username = account.get().lastFmUsername();
        List<String> storedArtists = resolveStoredLastFmArtists(request.userId(), 3);
        if (!storedArtists.isEmpty()) {
            return mergeArtistSeeds(
                request,
                storedArtists,
                "Stored Last.fm scrobble snapshot '%s' contributed recent artist recurrence to this GMS preview.".formatted(
                    username
                ),
                enrichmentWarnings
            );
        }

        if (lastFmWebApiClient.isEmpty()) {
            return request;
        }

        try {
            List<String> lastFmArtists = lastFmWebApiClient.get()
                .getTopArtists(username, "1month", 3)
                .stream()
                .map(LastFmWebApiClient.LastFmTopArtist::artistName)
                .filter(Objects::nonNull)
                .filter(name -> !name.isBlank())
                .toList();

            if (lastFmArtists.isEmpty()) {
                return request;
            }

            return mergeArtistSeeds(
                request,
                lastFmArtists,
                "Saved Last.fm profile '%s' contributed top artist affinity to this GMS preview.".formatted(username),
                enrichmentWarnings
            );
        } catch (IllegalArgumentException exception) {
            enrichmentWarnings.add(
                "Last.fm artist affinity could not be blended into this GMS preview: %s".formatted(exception.getMessage())
            );
            return request;
        }
    }

    private GmsRecommendationPreviewRequest mergeArtistSeeds(
        GmsRecommendationPreviewRequest request,
        List<String> additionalArtists,
        String note,
        List<String> enrichmentWarnings
    ) {
        LinkedHashSet<String> mergedArtistSeeds = new LinkedHashSet<>(request.seedArtistNames());
        mergedArtistSeeds.addAll(additionalArtists);

        if (mergedArtistSeeds.size() == request.seedArtistNames().size()) {
            return request;
        }

        enrichmentWarnings.add(note);

        return new GmsRecommendationPreviewRequest(
            request.requestId(),
            request.userId(),
            request.playlistId(),
            request.mode(),
            request.mood(),
            request.energyLevel(),
            request.familiarityBias(),
            request.limit(),
            request.seedTrackIds(),
            List.copyOf(mergedArtistSeeds),
            request.seedGenres(),
            request.includeExplanations()
        );
    }

    private List<String> resolveStoredLastFmArtists(String userId, int limit) {
        java.util.LinkedHashMap<String, Integer> countsByArtist = new java.util.LinkedHashMap<>();

        lastFmScrobbleStore.getSnapshot(userId, 20).recentScrobbles().stream()
            .map(LastFmScrobbleStore.StoredScrobble::artistName)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(name -> !name.isBlank())
            .forEach(name -> countsByArtist.merge(name, 1, Integer::sum));

        return countsByArtist.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(limit)
            .map(Map.Entry::getKey)
            .toList();
    }

    private record LibraryCandidateTrack(
        String playlistId,
        String playlistTitle,
        String sourcePlatform,
        String playlistCoverImageUrl,
        String playlistExternalUrl,
        String playlistUri,
        String trackId,
        String title,
        String artistName,
        String primaryGenre,
        String albumTitle,
        String albumImageUrl,
        String platformExternalUrl,
        String platformUri,
        String previewUrl,
        boolean seed,
        int sortOrder,
        PmsTrackAudioFeatures audioFeatures
    ) {
    }

    private record RankedLibraryCandidate(
        LibraryCandidateTrack candidate,
        double affinityScore,
        boolean sasrecRanked,
        boolean audioTasteRanked,
        List<String> audioTasteTokens,
        AudioTasteModeAffinityService.TasteModeAffinity tasteModeAffinity
    ) {
        private RankedLibraryCandidate {
            audioTasteTokens = audioTasteTokens == null ? List.of() : List.copyOf(audioTasteTokens);
        }

        private RankedLibraryCandidate(
            LibraryCandidateTrack candidate,
            double affinityScore
        ) {
            this(candidate, affinityScore, false, false, List.of(), null);
        }

        private RankedLibraryCandidate(
            LibraryCandidateTrack candidate,
            double affinityScore,
            boolean sasrecRanked
        ) {
            this(candidate, affinityScore, sasrecRanked, false, List.of(), null);
        }

        private RankedLibraryCandidate withAudioTasteScore(
            double nextAffinityScore,
            List<String> nextAudioTasteTokens
        ) {
            return new RankedLibraryCandidate(
                candidate,
                nextAffinityScore,
                sasrecRanked,
                true,
                nextAudioTasteTokens,
                tasteModeAffinity
            );
        }

        private RankedLibraryCandidate withTasteModeAffinity(
            AudioTasteModeAffinityService.TasteModeAffinity nextTasteModeAffinity
        ) {
            return new RankedLibraryCandidate(
                candidate,
                affinityScore,
                sasrecRanked,
                audioTasteRanked,
                audioTasteTokens,
                nextTasteModeAffinity
            );
        }
    }
}
