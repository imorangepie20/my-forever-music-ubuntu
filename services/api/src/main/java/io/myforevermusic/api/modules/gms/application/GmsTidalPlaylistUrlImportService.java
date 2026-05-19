package io.myforevermusic.api.modules.gms.application;

import io.myforevermusic.api.modules.ems.application.EmsCollectionService;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@org.springframework.context.annotation.Profile("!local")
@Service
public class GmsTidalPlaylistUrlImportService {

    private static final Pattern TIDAL_PLAYLIST_URL = Pattern.compile(
        "^https?://(?:www\\.)?tidal\\.com/(?:browse/)?playlist/([A-Za-z0-9][A-Za-z0-9_-]{2,159})(?:[/?#].*)?$"
    );

    private final EmsCollectionService emsCollectionService;

    public GmsTidalPlaylistUrlImportService(EmsCollectionService emsCollectionService) {
        this.emsCollectionService = emsCollectionService;
    }

    public ImportResult importUrl(String userId, String playlistUrl) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id is required.");
        }
        String playlistId = parsePlaylistId(playlistUrl);
        EmsCollectionService.EmsTidalPlaylistUrlImportCollection collected =
            emsCollectionService.collectTidalPlaylistFromUrlImport(userId, playlistId);
        return new ImportResult(
            userId,
            collected.emsPlaylistId(),
            collected.externalPlaylistId(),
            collected.sourcePlatform(),
            collected.title(),
            collected.trackCount(),
            collected.collectionSource(),
            collected.collectedAt()
        );
    }

    private String parsePlaylistId(String playlistUrl) {
        if (playlistUrl == null || playlistUrl.isBlank()) {
            throw new IllegalArgumentException("TIDAL playlist URL is required.");
        }
        Matcher matcher = TIDAL_PLAYLIST_URL.matcher(playlistUrl.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Enter a valid TIDAL playlist URL.");
        }
        return matcher.group(1);
    }

    public record ImportResult(
        String userId,
        Long emsPlaylistId,
        String externalPlaylistId,
        String sourcePlatform,
        String title,
        int trackCount,
        String collectionSource,
        Instant collectedAt
    ) {}
}
