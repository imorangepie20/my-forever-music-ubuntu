package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicCurationPlaylistRepository extends JpaRepository<PublicCurationPlaylistEntity, Long> {

    Optional<PublicCurationPlaylistEntity> findBySlugAndStatus(String slug, String status);
}
