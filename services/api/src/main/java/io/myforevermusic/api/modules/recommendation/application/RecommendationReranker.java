package io.myforevermusic.api.modules.recommendation.application;

import io.myforevermusic.api.modules.gms.presentation.GmsRecommendationPreviewResponse.RecommendationItem;
import io.myforevermusic.api.modules.recommendation.application.UserPersonalizationProfileStore.ArtistAffinity;
import io.myforevermusic.api.modules.recommendation.application.UserPersonalizationProfileStore.PlatformAffinity;
import io.myforevermusic.api.modules.recommendation.application.UserPersonalizationProfileStore.Profile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 개인화 프로필을 사용해 GMS preview candidate 순서를 재조정한다.
 *
 * Why (Phase 5 sub-item 2): SASRec sequence encoder 재학습 없이도 사용자의 최근 행동 신호가
 * 다음 추천 순서에 반영되도록, candidate별 score에 profile-driven boost를 곱해 재정렬한다.
 * profile이 없거나 비어 있으면 no-op (cold-start fallback과 충돌하지 않는다).
 *
 * 신호 결합:
 * - artistMatch: 후보 artist가 profile.topArtists에 있으면 (그 score / topArtist 최대 score) 만큼 boost
 * - platformMatch: 후보 source_platform이 profile.topSourcePlatforms에 있으면 약한 보조 boost
 * - finalScore = baseScore * (1 + alpha * artistMatch + beta * platformMatch)
 */
@Component
public class RecommendationReranker {

    @Value("${app.recommendation.rerank.artist-boost-weight:0.3}")
    private double artistBoostWeight;

    @Value("${app.recommendation.rerank.platform-boost-weight:0.1}")
    private double platformBoostWeight;

    /**
     * profile이 비어 있으면 입력을 그대로 반환한다.
     * 그렇지 않으면 boost를 적용해 정렬 + rank를 1부터 재부여한 결과를 반환한다.
     */
    public RerankResult rerank(List<RecommendationItem> items, Profile profile) {
        if (items == null || items.isEmpty()) {
            return new RerankResult(items == null ? List.of() : items, 0, false);
        }
        if (profile == null
            || (profile.topArtists() == null || profile.topArtists().isEmpty())
            && (profile.topSourcePlatforms() == null || profile.topSourcePlatforms().isEmpty())) {
            return new RerankResult(items, 0, false);
        }

        Map<String, Double> artistWeights = normalizedArtistWeights(profile);
        Map<String, Double> platformWeights = normalizedPlatformWeights(profile);

        record Scored(RecommendationItem item, double boostedScore, boolean matched) {}
        List<Scored> scored = new ArrayList<>(items.size());
        int matchCount = 0;
        for (RecommendationItem item : items) {
            double baseScore = item.score() == null ? 0.0d : item.score();
            double artistBoost = artistWeights.getOrDefault(normalize(item.artistName()), 0.0d);
            double platformBoost = platformWeights.getOrDefault(normalize(item.sourcePlatform()), 0.0d);
            double multiplier = 1.0d + artistBoostWeight * artistBoost + platformBoostWeight * platformBoost;
            double boostedScore = baseScore * multiplier;
            boolean matched = artistBoost > 0.0d || platformBoost > 0.0d;
            if (matched) {
                matchCount++;
            }
            scored.add(new Scored(item, boostedScore, matched));
        }

        scored.sort(Comparator.comparingDouble(Scored::boostedScore).reversed());

        boolean orderChanged = false;
        List<RecommendationItem> reordered = new ArrayList<>(scored.size());
        for (int newIndex = 0; newIndex < scored.size(); newIndex++) {
            Scored s = scored.get(newIndex);
            int newRank = newIndex + 1;
            Integer originalRank = s.item().rank();
            if (originalRank == null || originalRank != newRank) {
                orderChanged = true;
            }
            reordered.add(rerankItem(s.item(), newRank, s.boostedScore()));
        }
        return new RerankResult(reordered, matchCount, orderChanged);
    }

    private RecommendationItem rerankItem(RecommendationItem item, int newRank, double boostedScore) {
        return new RecommendationItem(
            newRank,
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
            roundScore(boostedScore),
            item.sourceSpace(),
            item.energyLevel(),
            item.reason(),
            item.axisEvidence(),
            item.tasteModeAffinity(),
            item.tasteModeGate()
        );
    }

    private Map<String, Double> normalizedArtistWeights(Profile profile) {
        List<ArtistAffinity> top = profile.topArtists();
        if (top == null || top.isEmpty()) {
            return Map.of();
        }
        double maxScore = top.stream().mapToDouble(ArtistAffinity::score).max().orElse(0.0d);
        if (maxScore <= 0.0d) {
            return Map.of();
        }
        Map<String, Double> result = new HashMap<>();
        for (ArtistAffinity affinity : top) {
            if (affinity.score() <= 0.0d || affinity.artistName() == null || affinity.artistName().isBlank()) {
                continue;
            }
            result.put(normalize(affinity.artistName()), affinity.score() / maxScore);
        }
        return result;
    }

    private Map<String, Double> normalizedPlatformWeights(Profile profile) {
        List<PlatformAffinity> top = profile.topSourcePlatforms();
        if (top == null || top.isEmpty()) {
            return Map.of();
        }
        double maxScore = top.stream().mapToDouble(PlatformAffinity::score).max().orElse(0.0d);
        if (maxScore <= 0.0d) {
            return Map.of();
        }
        Map<String, Double> result = new HashMap<>();
        for (PlatformAffinity affinity : top) {
            if (affinity.score() <= 0.0d || affinity.platform() == null || affinity.platform().isBlank()) {
                continue;
            }
            result.put(normalize(affinity.platform()), affinity.score() / maxScore);
        }
        return result;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Double roundScore(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    public record RerankResult(
        List<RecommendationItem> items,
        int matchedCount,
        boolean orderChanged
    ) {}
}
