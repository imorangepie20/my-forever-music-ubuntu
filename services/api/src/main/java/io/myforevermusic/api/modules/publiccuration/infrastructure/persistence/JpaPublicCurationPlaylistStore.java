package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import io.myforevermusic.api.modules.publiccuration.application.PublicCurationPlaylistStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
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
        PublicCurationPlaylistEntity playlist = playlistRepository.save(new PublicCurationPlaylistEntity(draft));
        Long playlistId = playlist.getPlaylistId();
        List<PublicCurationPlaylistTrackEntity> trackEntities = draft.tracks().stream()
            .map(track -> new PublicCurationPlaylistTrackEntity(playlistId, track, draft.createdAt()))
            .toList();
        List<StoredTrack> tracks = trackRepository.saveAll(trackEntities).stream()
            .map(PublicCurationPlaylistTrackEntity::toState)
            .toList();
        StoredRun run = runRepository.save(new PublicCurationRunEntity(playlistId, draft.run())).toState();

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
}
