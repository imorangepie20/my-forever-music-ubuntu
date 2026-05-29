package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicCurationPlaylistRepository extends JpaRepository<PublicCurationPlaylistEntity, Long> {
}
