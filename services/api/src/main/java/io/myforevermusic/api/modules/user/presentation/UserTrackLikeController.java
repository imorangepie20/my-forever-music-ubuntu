package io.myforevermusic.api.modules.user.presentation;

import io.myforevermusic.api.modules.user.application.UserTrackLikeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@org.springframework.context.annotation.Profile("!local")
@RestController
@RequestMapping("/api/v1/user/likes")
public class UserTrackLikeController {

    private static final int DEFAULT_LIST_LIMIT = 50;

    private final UserTrackLikeService userTrackLikeService;

    public UserTrackLikeController(UserTrackLikeService userTrackLikeService) {
        this.userTrackLikeService = userTrackLikeService;
    }

    @PostMapping
    public UserTrackLikeResponse toggle(@Valid @RequestBody UserTrackLikeRequest request) {
        return userTrackLikeService.toggle(request);
    }

    @GetMapping("/state")
    public UserTrackLikeResponse getState(
        @RequestParam("user_id") String userId,
        @RequestParam("source_platform") String sourcePlatform,
        @RequestParam("external_track_id") String externalTrackId
    ) {
        return userTrackLikeService.getState(userId, sourcePlatform, externalTrackId);
    }

    @GetMapping
    public UserTrackLikeListResponse list(
        @RequestParam("user_id") String userId,
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_LIST_LIMIT : limit;
        return userTrackLikeService.list(userId, effectiveLimit);
    }
}
