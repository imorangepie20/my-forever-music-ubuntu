package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import io.myforevermusic.api.modules.publiccuration.application.PublicCurationPlaylistStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("!local")
public class JpaPublicCurationPlaylistStore implements PublicCurationPlaylistStore {

    private final PublicCurationPlaylistRepository playlistRepository;
    private final PublicCurationPlaylistTrackRepository trackRepository;
    private final PublicCurationRunRepository runRepository;

    public JpaPublicCurationPlaylistStore(
        PublicCurationPlaylistRepository playlistRepository,
        PublicCurationPlaylistTrackRepository trackRepository,
        PublicCurationRunRepository runRepository
    ) {
        this.playlistRepository = playlistRepository;
        this.trackRepository = trackRepository;
        this.runRepository = runRepository;
    }

    @Override
    @Transactional
    public StoredPlaylist createDraft(CreateDraft draft) {
        CreateDraft uniqueDraft = withUniqueSlug(draft);
        PublicCurationPlaylistEntity playlist = playlistRepository.save(new PublicCurationPlaylistEntity(uniqueDraft));
        Long playlistId = playlist.getPlaylistId();
        List<PublicCurationPlaylistTrackEntity> trackEntities = uniqueDraft.tracks().stream()
            .map(track -> new PublicCurationPlaylistTrackEntity(playlistId, track, uniqueDraft.createdAt()))
            .toList();
        List<StoredTrack> tracks = trackRepository.saveAll(trackEntities).stream()
            .map(PublicCurationPlaylistTrackEntity::toState)
            .toList();
        StoredRun run = runRepository.save(new PublicCurationRunEntity(playlistId, uniqueDraft.run())).toState();

        return playlist.toState(tracks, run);
    }

    @Override
    @Transactional
    public StoredPlaylist publish(Long playlistId, Instant publishedAt) {
        PublicCurationPlaylistEntity playlist = playlistRepository.findById(playlistId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Public curation playlist was not found."
            ));
        playlist.publish(publishedAt);
        return toStoredPlaylist(playlistRepository.save(playlist));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredPlaylistSummary> findRecentForAdmin(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return playlistRepository.findAllByOrderByCreatedAtDescPlaylistIdDesc(PageRequest.of(0, safeLimit)).stream()
            .map(PublicCurationPlaylistEntity::toSummary)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredPlaylist> findPublishedBySlug(String slug) {
        return playlistRepository.findBySlugAndStatus(slug, "published")
            .map(this::toStoredPlaylist);
    }

    private StoredPlaylist toStoredPlaylist(PublicCurationPlaylistEntity playlist) {
        Long playlistId = playlist.getPlaylistId();
        List<StoredTrack> tracks = trackRepository.findByPlaylistIdOrderByTrackOrderAsc(playlistId).stream()
            .map(PublicCurationPlaylistTrackEntity::toState)
            .toList();
        StoredRun run = runRepository.findFirstByPlaylistIdOrderByRunIdDesc(playlistId)
            .map(PublicCurationRunEntity::toState)
            .orElse(null);
        return playlist.toState(tracks, run);
    }

    private CreateDraft withUniqueSlug(CreateDraft draft) {
        String baseSlug = normalizeSlug(draft.slug());
        String candidateSlug = baseSlug;
        int suffix = 2;
        while (playlistRepository.existsBySlug(candidateSlug)) {
            candidateSlug = "%s-%d".formatted(baseSlug, suffix);
            suffix++;
        }

        if (candidateSlug.equals(draft.slug())) {
            return draft;
        }

        return new CreateDraft(
            candidateSlug,
            draft.title(),
            draft.subtitle(),
            draft.description(),
            draft.prompt(),
            draft.filterSnapshotJson(),
            draft.coverStyle(),
            draft.modelVersion(),
            draft.trackCount(),
            draft.durationMs(),
            draft.createdByAdminUserId(),
            draft.createdAt(),
            draft.tracks(),
            draft.run()
        );
    }

    private String normalizeSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return "public-curation";
        }
        String normalized = slug.trim()
            .toLowerCase()
            .replaceAll("[^a-z0-9가-힣]+", "-")
            .replaceAll("(^-+|-+$)", "");
        if (normalized.isBlank()) {
            return "public-curation";
        }
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }
}
