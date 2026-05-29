package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicCurationRunRepository extends JpaRepository<PublicCurationRunEntity, Long> {

    Optional<PublicCurationRunEntity> findFirstByPlaylistIdOrderByRunIdDesc(Long playlistId);
}
