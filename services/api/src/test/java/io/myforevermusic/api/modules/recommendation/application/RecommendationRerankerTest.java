package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.myforevermusic.api.modules.gms.presentation.GmsRecommendationPreviewResponse.RecommendationItem;
import io.myforevermusic.api.modules.gms.presentation.GmsRecommendationPreviewResponse.TasteModeAffinityItem;
import io.myforevermusic.api.modules.gms.presentation.GmsRecommendationPreviewResponse.TasteModeGateItem;
import io.myforevermusic.api.modules.recommendation.application.UserPersonalizationProfileStore.ArtistAffinity;
import io.myforevermusic.api.modules.recommendation.application.UserPersonalizationProfileStore.PlatformAffinity;
import io.myforevermusic.api.modules.recommendation.application.UserPersonalizationProfileStore.Profile;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RecommendationRerankerTest {

    @Test
    void shouldBoostItemsMatchingTopArtistsAndReorderByBoostedScore() {
        RecommendationReranker reranker = configuredReranker(0.3d, 0.0d);
        List<RecommendationItem> items = List.of(
            item(1, "track-a", "Random Artist", "spotify", 1.0d),
            item(2, "track-b", "Queen", "spotify", 0.8d),
            item(3, "track-c", "Some Other Band", "spotify", 0.9d)
        );
        Profile profile = profile(
            List.of(new ArtistAffinity("Queen", 10.0d, 5L)),
            List.of()
        );

        RecommendationReranker.RerankResult result = reranker.rerank(items, profile);

        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.orderChanged()).isTrue();
        // boosted "Queen" score = 0.8 * (1 + 0.3 * 1.0) = 1.04 → moves to rank 1
        assertThat(result.items()).extracting(RecommendationItem::trackId)
            .containsExactly("track-b", "track-a", "track-c");
        assertThat(result.items()).extracting(RecommendationItem::rank)
            .containsExactly(1, 2, 3);
        assertThat(result.items().get(0).score()).isCloseTo(1.04d, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void shouldNoOpWhenProfileIsEmpty() {
        RecommendationReranker reranker = configuredReranker(0.3d, 0.1d);
        List<RecommendationItem> items = List.of(
            item(1, "track-a", "Artist A", "spotify", 1.0d),
            item(2, "track-b", "Artist B", "tidal", 0.8d)
        );
        Profile profile = profile(List.of(), List.of());

        RecommendationReranker.RerankResult result = reranker.rerank(items, profile);

        assertThat(result.matchedCount()).isZero();
        assertThat(result.orderChanged()).isFalse();
        assertThat(result.items()).isSameAs(items);
    }

    @Test
    void shouldNoOpWhenProfileIsNull() {
        RecommendationReranker reranker = configuredReranker(0.3d, 0.1d);
        List<RecommendationItem> items = List.of(item(1, "track-a", "Artist A", "spotify", 1.0d));

        RecommendationReranker.RerankResult result = reranker.rerank(items, null);

        assertThat(result.matchedCount()).isZero();
        assertThat(result.orderChanged()).isFalse();
        assertThat(result.items()).isSameAs(items);
    }

    @Test
    void shouldMatchArtistCaseInsensitively() {
        RecommendationReranker reranker = configuredReranker(0.3d, 0.0d);
        List<RecommendationItem> items = List.of(
            item(1, "track-a", "QUEEN", "spotify", 0.5d)
        );
        Profile profile = profile(
            List.of(new ArtistAffinity("queen", 10.0d, 5L)),
            List.of()
        );

        RecommendationReranker.RerankResult result = reranker.rerank(items, profile);

        assertThat(result.matchedCount()).isEqualTo(1);
        // 0.5 * (1 + 0.3 * 1.0) = 0.65
        assertThat(result.items().get(0).score()).isCloseTo(0.65d, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void shouldApplyPlatformBoostInAdditionToArtistMatch() {
        RecommendationReranker reranker = configuredReranker(0.3d, 0.2d);
        List<RecommendationItem> items = List.of(
            item(1, "track-a", "Random", "tidal", 0.5d),
            item(2, "track-b", "Queen", "spotify", 0.5d)
        );
        Profile profile = profile(
            List.of(new ArtistAffinity("Queen", 8.0d, 4L)),
            List.of(new PlatformAffinity("tidal", 4.0d, 4L))
        );

        RecommendationReranker.RerankResult result = reranker.rerank(items, profile);

        assertThat(result.matchedCount()).isEqualTo(2);
        // track-a: artist no match, platform tidal full match → 0.5 * (1 + 0.2 * 1.0) = 0.6
        // track-b: artist Queen match, platform spotify no match → 0.5 * (1 + 0.3 * 1.0) = 0.65
        assertThat(result.items()).extracting(RecommendationItem::trackId)
            .containsExactly("track-b", "track-a");
    }

    @Test
    void shouldPreserveTasteModeDiagnosticsWhenRerankingItems() {
        RecommendationReranker reranker = configuredReranker(0.3d, 0.0d);
        RecommendationItem tasteModeItem = item(2, "track-b", "Queen", "spotify", 0.8d)
            .withTasteModeAffinity(new TasteModeAffinityItem(
                true,
                "mode-bright",
                "bright_danceable",
                0.93d,
                0.07d,
                List.of("mode_energy_match")
            ))
            .withTasteModeGate(new TasteModeGateItem(
                "dry_run",
                "eligible",
                List.of("eligible", "mode_energy_match"),
                0.04d,
                0.813d,
                0.013d
            ));
        List<RecommendationItem> items = List.of(
            item(1, "track-a", "Random Artist", "spotify", 1.0d),
            tasteModeItem
        );
        Profile profile = profile(
            List.of(new ArtistAffinity("Queen", 10.0d, 5L)),
            List.of()
        );

        RecommendationReranker.RerankResult result = reranker.rerank(items, profile);

        assertThat(result.items()).extracting(RecommendationItem::trackId)
            .containsExactly("track-b", "track-a");
        assertThat(result.items().getFirst().tasteModeAffinity()).isEqualTo(tasteModeItem.tasteModeAffinity());
        assertThat(result.items().getFirst().tasteModeGate()).isEqualTo(tasteModeItem.tasteModeGate());
    }

    @Test
    void shouldHandleEmptyOrNullItems() {
        RecommendationReranker reranker = configuredReranker(0.3d, 0.1d);
        Profile profile = profile(
            List.of(new ArtistAffinity("Queen", 10.0d, 5L)),
            List.of()
        );

        assertThat(reranker.rerank(List.of(), profile).items()).isEmpty();
        assertThat(reranker.rerank(null, profile).items()).isEmpty();
    }

    private RecommendationReranker configuredReranker(double artistWeight, double platformWeight) {
        RecommendationReranker reranker = new RecommendationReranker();
        ReflectionTestUtils.setField(reranker, "artistBoostWeight", artistWeight);
        ReflectionTestUtils.setField(reranker, "platformBoostWeight", platformWeight);
        return reranker;
    }

    private RecommendationItem item(int rank, String trackId, String artistName, String sourcePlatform, double score) {
        return new RecommendationItem(
            rank,
            trackId,
            "Title " + trackId,
            artistName,
            sourcePlatform,
            "playlist-1",
            "Playlist",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            score,
            "gms",
            null,
            null
        );
    }

    private Profile profile(List<ArtistAffinity> artists, List<PlatformAffinity> platforms) {
        return new Profile(
            1L,
            "user-1",
            artists,
            platforms,
            10L,
            Instant.parse("2026-05-14T00:00:00Z"),
            Instant.parse("2026-05-14T00:01:00Z")
        );
    }
}
