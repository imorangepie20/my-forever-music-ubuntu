package io.myforevermusic.api.modules.user.application;

import io.myforevermusic.api.modules.user.infrastructure.persistence.UserTrackLikeEntity;
import io.myforevermusic.api.modules.user.infrastructure.persistence.UserTrackLikeRepository;
import io.myforevermusic.api.modules.user.presentation.UserTrackLikeListResponse;
import io.myforevermusic.api.modules.user.presentation.UserTrackLikeRequest;
import io.myforevermusic.api.modules.user.presentation.UserTrackLikeResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.context.annotation.Profile("!local")
@Service
public class UserTrackLikeService {

    private static final int MAX_LIST_LIMIT = 200;

    private final UserTrackLikeRepository repository;
    private final Clock clock;

    @Autowired
    public UserTrackLikeService(UserTrackLikeRepository repository) {
        this(repository, Clock.systemUTC());
    }

    UserTrackLikeService(UserTrackLikeRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public UserTrackLikeResponse toggle(UserTrackLikeRequest request) {
        validate(request);
        Optional<UserTrackLikeEntity> existing = repository
            .findByUserIdAndSourcePlatformAndExternalTrackId(
                request.userId(),
                request.sourcePlatform(),
                request.externalTrackId()
            );

        if (existing.isPresent()) {
            repository.delete(existing.get());
            return new UserTrackLikeResponse(false, request.sourcePlatform(), request.externalTrackId(), null);
        }

        Instant now = clock.instant();
        UserTrackLikeEntity saved = repository.save(new UserTrackLikeEntity(
            request.userId(),
            request.sourcePlatform(),
            request.externalTrackId(),
            request.title(),
            request.artistName(),
            request.albumTitle(),
            request.imageUrl(),
            request.spotifyTrackId(),
            request.platformExternalUrl(),
            now
        ));
        return new UserTrackLikeResponse(true, saved.getSourcePlatform(), saved.getExternalTrackId(), saved.getLikedAt());
    }

    @Transactional(readOnly = true)
    public UserTrackLikeResponse getState(String userId, String sourcePlatform, String externalTrackId) {
        if (isBlank(userId) || isBlank(sourcePlatform) || isBlank(externalTrackId)) {
            throw new IllegalArgumentException("user_id, source_platform, external_track_id are required.");
        }
        return repository
            .findByUserIdAndSourcePlatformAndExternalTrackId(userId, sourcePlatform, externalTrackId)
            .map(entity -> new UserTrackLikeResponse(true, sourcePlatform, externalTrackId, entity.getLikedAt()))
            .orElse(new UserTrackLikeResponse(false, sourcePlatform, externalTrackId, null));
    }

    @Transactional(readOnly = true)
    public UserTrackLikeListResponse list(String userId, int limit) {
        if (isBlank(userId)) {
            throw new IllegalArgumentException("user_id is required.");
        }
        int effectiveLimit = Math.min(MAX_LIST_LIMIT, Math.max(1, limit));
        List<UserTrackLikeEntity> entities = repository.findByUserIdOrderByLikedAtDesc(
            userId,
            PageRequest.of(0, effectiveLimit)
        );
        long total = repository.countByUserId(userId);
        List<UserTrackLikeListResponse.Entry> items = entities.stream()
            .map(entity -> new UserTrackLikeListResponse.Entry(
                entity.getSourcePlatform(),
                entity.getExternalTrackId(),
                entity.getSpotifyTrackId(),
                entity.getTitle(),
                entity.getArtistName(),
                entity.getAlbumTitle(),
                entity.getImageUrl(),
                entity.getPlatformExternalUrl(),
                entity.getLikedAt()
            ))
            .toList();
        return new UserTrackLikeListResponse(total, items);
    }

    private void validate(UserTrackLikeRequest request) {
        if (isBlank(request.userId())) {
            throw new IllegalArgumentException("user_id is required.");
        }
        if (isBlank(request.sourcePlatform())) {
            throw new IllegalArgumentException("source_platform is required.");
        }
        if (isBlank(request.externalTrackId())) {
            throw new IllegalArgumentException("external_track_id is required.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
