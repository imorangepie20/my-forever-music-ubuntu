package io.myforevermusic.api.modules.artist.application;

import io.myforevermusic.api.modules.artist.presentation.ArtistDetailResponse;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackRepository;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsUserPlaylistTrackRepository;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsUserTrackEntity;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class ArtistDetailService {

    private static final int TRACK_LIMIT = 50;
    private static final Pattern NON_SLUG_CHARS = Pattern.compile("[^a-z0-9]+");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    private final Optional<PmsUserPlaylistTrackRepository> pmsPlaylistTrackRepository;
    private final Optional<EmsCollectedTrackRepository> emsTrackRepository;

    public ArtistDetailService(
        Optional<PmsUserPlaylistTrackRepository> pmsPlaylistTrackRepository,
        Optional<EmsCollectedTrackRepository> emsTrackRepository
    ) {
        this.pmsPlaylistTrackRepository = pmsPlaylistTrackRepository;
        this.emsTrackRepository = emsTrackRepository;
    }

    public ArtistDetailResponse getArtistDetail(String artistSlug, String userId, String artistName) {
        String displayName = firstNonBlank(artistName, readableNameFromSlug(artistSlug), "Unknown Artist");
        String canonicalSlug = slugify(displayName);
        Pageable limit = PageRequest.of(0, TRACK_LIMIT);

        List<PmsUserTrackEntity> pmsTracks = hasText(userId)
            ? pmsPlaylistTrackRepository
                .map(repository -> repository.findDistinctTracksByUserIdAndArtistName(userId.trim(), displayName, limit))
                .orElse(List.of())
            : List.of();
        List<EmsCollectedTrackEntity> emsTracks = emsTrackRepository
            .map(repository -> repository.findByArtistNameIgnoreCaseOrderByCollectedAtDescIdAsc(displayName, limit))
            .orElse(List.of());

        List<ArtistDetailResponse.ArtistTrack> pmsTrackItems = pmsTracks.stream()
            .map(this::toPmsTrack)
            .toList();
        List<ArtistDetailResponse.ArtistTrack> emsTrackItems = emsTracks.stream()
            .map(this::toEmsTrack)
            .toList();
        String imageUrl = firstImageUrl(pmsTrackItems, emsTrackItems);
        List<ArtistDetailResponse.PlatformCandidate> platformCandidates =
            inferPlatformCandidates(displayName, imageUrl, pmsTrackItems, emsTrackItems);

        return new ArtistDetailResponse(
            "api",
            "ok",
            Instant.now(),
            new ArtistDetailResponse.ArtistProfile(
                canonicalSlug,
                displayName,
                imageUrl,
                "Matched by artist_name across PMS and EMS tracks."
            ),
            new ArtistDetailResponse.ArtistSummary(
                pmsTrackItems.size(),
                emsTrackItems.size(),
                pmsTrackItems.size() + emsTrackItems.size(),
                platformCandidates.size()
            ),
            platformCandidates,
            pmsTrackItems,
            emsTrackItems
        );
    }

    private ArtistDetailResponse.ArtistTrack toPmsTrack(PmsUserTrackEntity track) {
        return new ArtistDetailResponse.ArtistTrack(
            track.getTrackId(),
            track.getTitle(),
            track.getArtistName(),
            track.getSourcePlatform(),
            track.getAlbumTitle(),
            track.getAlbumImageUrl(),
            track.getPlatformExternalUrl(),
            firstNonBlank(track.getPlatformUri(), track.getSpotifyUri(), track.getTidalUri()),
            track.getPreviewUrl(),
            track.getIsrc(),
            track.getAudioFeatures() == null ? null : track.getAudioFeatures().getDurationMs(),
            "pms_user_library"
        );
    }

    private ArtistDetailResponse.ArtistTrack toEmsTrack(EmsCollectedTrackEntity track) {
        return new ArtistDetailResponse.ArtistTrack(
            track.getId() == null ? track.getExternalTrackId() : String.valueOf(track.getId()),
            track.getTitle(),
            track.getArtistName(),
            track.getSourcePlatform(),
            track.getAlbumTitle(),
            track.getAlbumImageUrl(),
            track.getPlatformExternalUrl(),
            track.getSpotifyUri(),
            track.getPreviewUrl(),
            track.getIsrc(),
            track.getDurationMs(),
            track.getCollectionSource()
        );
    }

    private List<ArtistDetailResponse.PlatformCandidate> inferPlatformCandidates(
        String displayName,
        String imageUrl,
        List<ArtistDetailResponse.ArtistTrack> pmsTracks,
        List<ArtistDetailResponse.ArtistTrack> emsTracks
    ) {
        Map<String, ArtistDetailResponse.ArtistTrack> firstTrackByPlatform = new LinkedHashMap<>();
        List<ArtistDetailResponse.ArtistTrack> allTracks = new ArrayList<>();
        allTracks.addAll(pmsTracks);
        allTracks.addAll(emsTracks);
        for (ArtistDetailResponse.ArtistTrack track : allTracks) {
            if (!hasText(track.sourcePlatform())) {
                continue;
            }
            firstTrackByPlatform.putIfAbsent(track.sourcePlatform(), track);
        }
        return firstTrackByPlatform.entrySet().stream()
            .map(entry -> {
                ArtistDetailResponse.ArtistTrack track = entry.getValue();
                return new ArtistDetailResponse.PlatformCandidate(
                    entry.getKey(),
                    null,
                    displayName,
                    firstNonBlank(imageUrl, track.albumImageUrl()),
                    null,
                    null,
                    null,
                    null,
                    "inferred_from_tracks"
                );
            })
            .toList();
    }

    private String firstImageUrl(
        List<ArtistDetailResponse.ArtistTrack> pmsTracks,
        List<ArtistDetailResponse.ArtistTrack> emsTracks
    ) {
        return firstNonBlank(
            pmsTracks.stream().map(ArtistDetailResponse.ArtistTrack::albumImageUrl).filter(this::hasText).findFirst().orElse(null),
            emsTracks.stream().map(ArtistDetailResponse.ArtistTrack::albumImageUrl).filter(this::hasText).findFirst().orElse(null)
        );
    }

    private String readableNameFromSlug(String artistSlug) {
        if (!hasText(artistSlug)) {
            return null;
        }
        String normalized = artistSlug.trim().replace('-', ' ');
        return normalized.isBlank() ? null : normalized;
    }

    private String slugify(String value) {
        if (!hasText(value)) {
            return "unknown-artist";
        }
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD);
        normalized = DIACRITICS.matcher(normalized).replaceAll("");
        normalized = NON_SLUG_CHARS.matcher(normalized.toLowerCase(Locale.ROOT)).replaceAll("-");
        normalized = normalized.replaceAll("(^-+|-+$)", "");
        return normalized.isBlank() ? "unknown-artist" : normalized;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
