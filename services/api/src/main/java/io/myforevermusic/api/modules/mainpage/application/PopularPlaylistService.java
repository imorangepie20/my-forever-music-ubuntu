package io.myforevermusic.api.modules.mainpage.application;

import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistRepository;
import io.myforevermusic.api.modules.mainpage.presentation.PopularPlaylistResponse;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@org.springframework.context.annotation.Profile("!local")
@Service
public class PopularPlaylistService {

    private static final int MAX_LIMIT = 24;

    private final EmsCollectedPlaylistRepository repository;

    public PopularPlaylistService(EmsCollectedPlaylistRepository repository) {
        this.repository = repository;
    }

    public List<PopularPlaylistResponse> findPopular(int limit) {
        int effectiveLimit = Math.min(MAX_LIMIT, Math.max(1, limit));
        List<EmsCollectedPlaylistEntity> entities = repository
            .findPopularByFollowersThenTrackCount(PageRequest.of(0, effectiveLimit));
        return entities.stream()
            .map(PopularPlaylistService::toResponse)
            .toList();
    }

    private static PopularPlaylistResponse toResponse(EmsCollectedPlaylistEntity playlist) {
        return new PopularPlaylistResponse(
            playlist.getId(),
            playlist.getExternalPlaylistId(),
            playlist.getSourcePlatform(),
            playlist.getTitle(),
            playlist.getCurator(),
            playlist.getDescription(),
            playlist.getCoverImageUrl(),
            playlist.getPlatformExternalUrl(),
            playlist.getTrackCount()
        );
    }
}
