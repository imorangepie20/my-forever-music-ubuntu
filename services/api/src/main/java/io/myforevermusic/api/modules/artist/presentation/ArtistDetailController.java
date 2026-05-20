package io.myforevermusic.api.modules.artist.presentation;

import io.myforevermusic.api.modules.artist.application.ArtistDetailService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/artists")
public class ArtistDetailController {

    private final ArtistDetailService artistDetailService;

    public ArtistDetailController(ArtistDetailService artistDetailService) {
        this.artistDetailService = artistDetailService;
    }

    @Operation(summary = "Get an artist detail view from PMS and EMS library signals")
    @GetMapping("/{artistSlug}")
    public ArtistDetailResponse getArtistDetail(
        @PathVariable String artistSlug,
        @RequestParam(value = "user_id", required = false) String userId,
        @RequestParam(value = "artist_name", required = false) String artistName
    ) {
        return artistDetailService.getArtistDetail(artistSlug, userId, artistName);
    }
}
