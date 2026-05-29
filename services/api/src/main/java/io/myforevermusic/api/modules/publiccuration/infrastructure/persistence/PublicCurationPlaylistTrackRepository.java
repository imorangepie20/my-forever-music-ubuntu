package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicCurationPlaylistTrackRepository
    extends JpaRepository<PublicCurationPlaylistTrackEntity, Long> {

    List<PublicCurationPlaylistTrackEntity> findByPlaylistIdOrderByTrackOrderAsc(Long playlistId);
}
