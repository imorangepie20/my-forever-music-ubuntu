package io.myforevermusic.api.modules.platform.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TidalWebTokenRepository extends JpaRepository<TidalWebTokenEntity, Short> {
}
