package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicPlaybackSessionRepository extends JpaRepository<PublicPlaybackSessionEntity, String> {
}
