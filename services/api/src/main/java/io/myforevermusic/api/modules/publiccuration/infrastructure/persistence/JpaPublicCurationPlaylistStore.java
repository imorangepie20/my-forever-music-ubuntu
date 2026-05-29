package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import io.myforevermusic.api.modules.publiccuration.application.PublicCurationPlaylistStore;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
}
