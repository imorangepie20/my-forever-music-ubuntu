package io.myforevermusic.api.modules.mainpage.presentation;

import io.myforevermusic.api.modules.mainpage.application.HeroTrackService;
import io.myforevermusic.api.modules.mainpage.application.MagazineArticleService;
import io.myforevermusic.api.modules.mainpage.application.PopularPlaylistService;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@org.springframework.context.annotation.Profile("!local")
@RestController
@RequestMapping("/api/v1/main-page")
public class HeroTrackController {

    private static final int DEFAULT_HERO_LIST_LIMIT = 5;
    private static final int DEFAULT_LATEST_LIMIT = 10;
    private static final int DEFAULT_POPULAR_PLAYLIST_LIMIT = 6;
    private static final int DEFAULT_MAGAZINE_LIMIT = 8;

    private final HeroTrackService heroTrackService;
    private final PopularPlaylistService popularPlaylistService;
    private final MagazineArticleService magazineArticleService;

    public HeroTrackController(
        HeroTrackService heroTrackService,
        PopularPlaylistService popularPlaylistService,
        MagazineArticleService magazineArticleService
    ) {
        this.heroTrackService = heroTrackService;
        this.popularPlaylistService = popularPlaylistService;
        this.magazineArticleService = magazineArticleService;
    }

    @GetMapping("/hero-track")
    public ResponseEntity<HeroTrackResponse> getHeroTrack(
        @RequestParam(value = "user_id", required = false) String userId
    ) {
        Optional<HeroTrackResponse> response = heroTrackService.resolve(userId);
        return response
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/hero-tracks")
    public ResponseEntity<HeroTrackListResponse> getHeroTracks(
        @RequestParam(value = "user_id", required = false) String userId,
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_HERO_LIST_LIMIT : limit;
        List<HeroTrackResponse> tracks = heroTrackService.resolveList(userId, effectiveLimit);
        if (tracks.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(new HeroTrackListResponse(tracks));
    }

    @GetMapping("/latest-tracks")
    public ResponseEntity<HeroTrackListResponse> getLatestTracks(
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_LATEST_LIMIT : limit;
        List<HeroTrackResponse> tracks = heroTrackService.findLatest(effectiveLimit);
        if (tracks.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(new HeroTrackListResponse(tracks));
    }

    @GetMapping("/popular-playlists")
    public ResponseEntity<PopularPlaylistResponse.ListEnvelope> getPopularPlaylists(
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_POPULAR_PLAYLIST_LIMIT : limit;
        List<PopularPlaylistResponse> playlists = popularPlaylistService.findPopular(effectiveLimit);
        if (playlists.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(new PopularPlaylistResponse.ListEnvelope(playlists));
    }

    @GetMapping("/magazine-articles")
    public ResponseEntity<MagazineArticleResponse.ListEnvelope> getMagazineArticles(
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_MAGAZINE_LIMIT : limit;
        List<MagazineArticleResponse> articles = magazineArticleService.findRecent(effectiveLimit);
        if (articles.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(new MagazineArticleResponse.ListEnvelope(articles));
    }
}
