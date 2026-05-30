package io.myforevermusic.api.modules.platform.infrastructure.tidal;

import io.myforevermusic.api.modules.platform.infrastructure.persistence.TidalWebTokenEntity;
import io.myforevermusic.api.modules.platform.infrastructure.persistence.TidalWebTokenRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator-managed TIDAL public web token (x-tidal-token). The token lives behind TIDAL's bot
 * protection (DataDome) so it cannot be fetched server-side; an admin pastes it from a real
 * browser session and it is stored here for EMS discovery to fetch playlist tracks.
 */
@Component
public class TidalWebTokenStore {

    private static final short SINGLETON_ID = 1;

    private final TidalWebTokenRepository repository;

    public TidalWebTokenStore(TidalWebTokenRepository repository) {
        this.repository = repository;
    }

    public Optional<String> getToken() {
        return repository.findById(SINGLETON_ID)
            .map(TidalWebTokenEntity::getToken)
            .filter(token -> token != null && !token.isBlank());
    }

    @Transactional
    public TidalWebTokenStatus save(String token, String updatedBy) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("TIDAL web token must not be blank.");
        }
        TidalWebTokenEntity entity = repository.findById(SINGLETON_ID).orElseGet(TidalWebTokenEntity::new);
        entity.setId(SINGLETON_ID);
        entity.setToken(token.trim());
        entity.setUpdatedBy(updatedBy);
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        return status();
    }

    public TidalWebTokenStatus status() {
        return repository.findById(SINGLETON_ID)
            .map(entity -> new TidalWebTokenStatus(
                entity.getToken() != null && !entity.getToken().isBlank(),
                mask(entity.getToken()),
                entity.getUpdatedBy(),
                entity.getUpdatedAt()
            ))
            .orElse(new TidalWebTokenStatus(false, null, null, null));
    }

    private static String mask(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String trimmed = token.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        return "****" + trimmed.substring(trimmed.length() - 4);
    }

    public record TidalWebTokenStatus(
        boolean configured,
        String maskedToken,
        String updatedBy,
        Instant updatedAt
    ) {}
}
