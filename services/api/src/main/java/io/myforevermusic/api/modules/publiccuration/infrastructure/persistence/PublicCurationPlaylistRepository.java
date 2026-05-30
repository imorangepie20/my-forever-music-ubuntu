package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicCurationPlaylistRepository extends JpaRepository<PublicCurationPlaylistEntity, Long> {

    Optional<PublicCurationPlaylistEntity> findBySlugAndStatus(String slug, String status);

    boolean existsBySlug(String slug);

    List<PublicCurationPlaylistEntity> findAllByOrderByCreatedAtDescPlaylistIdDesc(Pageable pageable);
}
