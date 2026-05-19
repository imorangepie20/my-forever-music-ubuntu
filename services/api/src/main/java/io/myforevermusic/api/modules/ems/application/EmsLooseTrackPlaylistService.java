package io.myforevermusic.api.modules.ems.application;

import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackRepository;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.context.annotation.Profile("!local")
@Service
public class EmsLooseTrackPlaylistService {

    public static final String COLLECTION_SOURCE = "loose_track_playlist";
    private static final int DEFAULT_TRACK_LIMIT = 500;
    private static final int MAX_TRACK_LIMIT = 5_000;
    private static final int DEFAULT_TRACKS_PER_PLAYLIST = 40;
    private static final int MAX_TRACKS_PER_PLAYLIST = 100;

    private final EmsCollectedPlaylistRepository playlistRepository;
    private final EmsCollectedTrackRepository trackRepository;
    private final JdbcTemplate jdbcTemplate;

    public EmsLooseTrackPlaylistService(
        EmsCollectedPlaylistRepository playlistRepository,
        EmsCollectedTrackRepository trackRepository,
        JdbcTemplate jdbcTemplate
    ) {
        this.playlistRepository = playlistRepository;
        this.trackRepository = trackRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public LooseTrackPlaylistMaterializationResult materializeLooseTracks(
        Integer requestedTrackLimit,
        Integer requestedTracksPerPlaylist
    ) {
        int trackLimit = clamp(requestedTrackLimit, DEFAULT_TRACK_LIMIT, 1, MAX_TRACK_LIMIT);
        int tracksPerPlaylist = clamp(
            requestedTracksPerPlaylist,
            DEFAULT_TRACKS_PER_PLAYLIST,
            5,
            MAX_TRACKS_PER_PLAYLIST
        );
        long unassignedBefore = trackRepository.countUnassignedTracks();
        List<EmsCollectedTrackEntity> tracks = trackRepository.findUnassignedTracks(PageRequest.of(0, trackLimit));
        Instant now = Instant.now();
        List<MaterializedPlaylist> materialized = new ArrayList<>();
        int linkedTrackCount = 0;

        for (List<EmsCollectedTrackEntity> batch : batchesBySource(tracks, tracksPerPlaylist)) {
            if (batch.isEmpty()) {
                continue;
            }
            EmsCollectedPlaylistEntity playlist = upsertSyntheticPlaylist(batch, now);
            linkPlaylistTracks(playlist.getId(), batch);
            linkedTrackCount += batch.size();
            materialized.add(new MaterializedPlaylist(
                playlist.getId(),
                playlist.getTitle(),
                playlist.getSourcePlatform(),
                sourceCollection(batch.get(0)),
                batch.size()
            ));
        }

        long unassignedAfter = trackRepository.countUnassignedTracks();
        return new LooseTrackPlaylistMaterializationResult(
            unassignedBefore,
            tracks.size(),
            materialized.size(),
            linkedTrackCount,
            unassignedAfter,
            now,
            List.copyOf(materialized)
        );
    }

    @Transactional(readOnly = true)
    public long countUnassignedTracks() {
        return trackRepository.countUnassignedTracks();
    }

    private EmsCollectedPlaylistEntity upsertSyntheticPlaylist(List<EmsCollectedTrackEntity> batch, Instant now) {
        EmsCollectedTrackEntity firstTrack = batch.get(0);
        String sourcePlatform = required(firstTrack.getSourcePlatform(), "unknown");
        String sourceCollection = sourceCollection(firstTrack);
        String externalPlaylistId = truncate(
            "synthetic:loose:%s:%s:%d".formatted(sourcePlatform, sourceCollection, firstTrack.getId()),
            160
        );
        String title = truncate(
            "EMS 추천 후보 · %s · %s".formatted(display(sourcePlatform), display(sourceCollection)),
            200
        );
        String description = truncate(
            "Playlist materialized from EMS tracks that were collected without a source playlist.",
            1000
        );
        String coverImageUrl = batch.stream()
            .map(EmsCollectedTrackEntity::getAlbumImageUrl)
            .filter(this::hasText)
            .findFirst()
            .orElse(null);

        return playlistRepository.findBySourcePlatformAndExternalPlaylistId(sourcePlatform, externalPlaylistId)
            .map(existing -> {
                existing.applyCollectedMetadata(
                    title,
                    "EMS Track Materializer",
                    description,
                    coverImageUrl,
                    null,
                    null,
                    batch.size(),
                    COLLECTION_SOURCE,
                    sourceCollection,
                    now
                );
                return existing;
            })
            .orElseGet(() -> playlistRepository.save(new EmsCollectedPlaylistEntity(
                externalPlaylistId,
                title,
                sourcePlatform,
                "EMS Track Materializer",
                description,
                coverImageUrl,
                null,
                null,
                batch.size(),
                COLLECTION_SOURCE,
                sourceCollection,
                now
            )));
    }

    private void linkPlaylistTracks(Long playlistId, List<EmsCollectedTrackEntity> tracks) {
        if (playlistId == null) {
            throw new IllegalStateException("Synthetic EMS playlist must be persisted before linking loose tracks.");
        }
        jdbcTemplate.batchUpdate(
            """
                insert into ems_collected_playlist_track (
                    ems_collected_playlist_id,
                    ems_collected_track_id,
                    sort_order
                ) values (?, ?, ?)
                on conflict (ems_collected_playlist_id, ems_collected_track_id) do update set
                    sort_order = excluded.sort_order
                """,
            new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int index) throws SQLException {
                    ps.setLong(1, playlistId);
                    ps.setLong(2, tracks.get(index).getId());
                    ps.setInt(3, index);
                }

                @Override
                public int getBatchSize() {
                    return tracks.size();
                }
            }
        );
    }

    private List<List<EmsCollectedTrackEntity>> batchesBySource(
        List<EmsCollectedTrackEntity> tracks,
        int tracksPerPlaylist
    ) {
        Map<String, List<EmsCollectedTrackEntity>> grouped = new LinkedHashMap<>();
        for (EmsCollectedTrackEntity track : tracks) {
            String key = "%s:%s".formatted(required(track.getSourcePlatform(), "unknown"), sourceCollection(track));
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(track);
        }

        List<List<EmsCollectedTrackEntity>> result = new ArrayList<>();
        for (List<EmsCollectedTrackEntity> group : grouped.values()) {
            for (int offset = 0; offset < group.size(); offset += tracksPerPlaylist) {
                result.add(group.subList(offset, Math.min(group.size(), offset + tracksPerPlaylist)));
            }
        }
        return result;
    }

    private String sourceCollection(EmsCollectedTrackEntity track) {
        return required(track.getCollectionSource(), "unknown");
    }

    private int clamp(Integer value, int fallback, int min, int max) {
        int resolved = value == null ? fallback : value;
        return Math.min(max, Math.max(min, resolved));
    }

    private String display(String value) {
        return value == null || value.isBlank()
            ? "Unknown"
            : value.replace('_', ' ').replace('-', ' ');
    }

    private String required(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record LooseTrackPlaylistMaterializationResult(
        long unassignedTrackCountBefore,
        int selectedTrackCount,
        int createdPlaylistCount,
        int linkedTrackCount,
        long unassignedTrackCountAfter,
        Instant materializedAt,
        List<MaterializedPlaylist> playlists
    ) {}

    public record MaterializedPlaylist(
        Long playlistId,
        String title,
        String sourcePlatform,
        String sourceCollection,
        int trackCount
    ) {}
}
