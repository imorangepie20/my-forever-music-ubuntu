package io.myforevermusic.api.modules.ems.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmsCollectedPlaylistRepository extends JpaRepository<EmsCollectedPlaylistEntity, Long> {
    @Query(
        value = """
            select *
            from ems_collected_playlist
            where source_platform = :platformId
            order by collected_at desc
            limit :limit
            """,
        nativeQuery = true
    )
    List<EmsCollectedPlaylistEntity> findBySourcePlatformOrderByCollectedAtDesc(
        @Param("platformId") String platformId,
        @Param("limit") int limit
    );

    @Query(
        value = """
            select *
            from ems_collected_playlist
            where source_platform = :platformId
            order by random()
            limit :limit
            """,
        nativeQuery = true
    )
    List<EmsCollectedPlaylistEntity> findRandomBySourcePlatform(
        @Param("platformId") String platformId,
        @Param("limit") int limit
    );

    Optional<EmsCollectedPlaylistEntity> findBySourcePlatformAndExternalPlaylistId(String platformId, String externalPlaylistId);

    @Query("select distinct playlist.sourcePlatform from EmsCollectedPlaylistEntity playlist order by playlist.sourcePlatform")
    List<String> findDistinctSourcePlatforms();

    @Query("""
        select playlist
        from EmsCollectedPlaylistEntity playlist
        where exists (
            select link.id
            from EmsCollectedPlaylistTrackEntity link
            where link.playlist.id = playlist.id
        )
        order by playlist.collectedAt desc
        """)
    List<EmsCollectedPlaylistEntity> findRecentWithTracks(Pageable pageable);

    @Query("""
        select playlist
        from EmsCollectedPlaylistEntity playlist
        where playlist.trackCount > 0
        order by playlist.trackCount desc, playlist.collectedAt desc
        """)
    List<EmsCollectedPlaylistEntity> findPopularByTrackCount(Pageable pageable);

    @Query("""
        select playlist
        from EmsCollectedPlaylistEntity playlist
        where playlist.trackCount > 0
        order by
            case when playlist.followersCount is null then 1 else 0 end,
            playlist.followersCount desc,
            playlist.trackCount desc,
            playlist.collectedAt desc
        """)
    List<EmsCollectedPlaylistEntity> findPopularByFollowersThenTrackCount(Pageable pageable);

    @Query("""
        select playlist
        from EmsCollectedPlaylistEntity playlist
        where playlist.sourcePlatform = :sourcePlatform
          and (playlist.popularityRefreshedAt is null or playlist.popularityRefreshedAt < :staleBefore)
        order by playlist.popularityRefreshedAt asc nulls first, playlist.collectedAt desc
        """)
    List<EmsCollectedPlaylistEntity> findStalePopularityCandidates(
        @Param("sourcePlatform") String sourcePlatform,
        @Param("staleBefore") Instant staleBefore,
        Pageable pageable
    );

    @Query("""
        select playlist
        from EmsCollectedPlaylistEntity playlist
        where playlist.sourcePlatform in :platformIds
          and exists (
              select link.id
              from EmsCollectedPlaylistTrackEntity link
              where link.playlist.id = playlist.id
          )
        order by playlist.collectedAt desc
        """)
    List<EmsCollectedPlaylistEntity> findRecentWithTracksBySourcePlatforms(
        @Param("platformIds") List<String> platformIds,
        Pageable pageable
    );

    @Query("""
        select playlist
        from EmsCollectedPlaylistEntity playlist
        where playlist.collectionSource = :collectionSource
          and exists (
              select link.id
              from EmsCollectedPlaylistTrackEntity link
              where link.playlist.id = playlist.id
          )
        order by playlist.collectedAt desc
        """)
    List<EmsCollectedPlaylistEntity> findRecentWithTracksByCollectionSource(
        @Param("collectionSource") String collectionSource,
        Pageable pageable
    );

    long countBySourcePlatform(String platformId);

    Optional<EmsCollectedPlaylistEntity> findFirstBySourcePlatformOrderByCollectedAtDesc(String platformId);

    @Modifying
    @Query(value = """
        delete from ems_collected_playlist_source
        where source_platform = :platformId
          and collection_source = :collectionSource
          and source_id = :sourceId
        """, nativeQuery = true)
    int deleteTidalHomeSources(
        @Param("platformId") String platformId,
        @Param("collectionSource") String collectionSource,
        @Param("sourceId") String sourceId
    );

    @Modifying
    @Query(value = """
        insert into ems_collected_playlist_source (
            ems_collected_playlist_id,
            source_platform,
            collection_source,
            source_id,
            collected_at
        ) values (
            :playlistId,
            :platformId,
            :collectionSource,
            :sourceId,
            :collectedAt
        )
        on conflict (ems_collected_playlist_id, source_platform, collection_source, source_id)
        do update set collected_at = excluded.collected_at
        """, nativeQuery = true)
    void upsertPlaylistSource(
        @Param("playlistId") Long playlistId,
        @Param("platformId") String platformId,
        @Param("collectionSource") String collectionSource,
        @Param("sourceId") String sourceId,
        @Param("collectedAt") Instant collectedAt
    );

    @Query(value = """
        select distinct playlist.*
        from ems_collected_playlist playlist
        join ems_collected_playlist_source source
          on source.ems_collected_playlist_id = playlist.ems_collected_playlist_id
        where source.source_platform = 'tidal'
          and source.collection_source = 'public_pool'
          and source.source_id in (:sourceIds)
          and not exists (
              select 1
              from ems_collected_playlist_track link
              where link.ems_collected_playlist_id = playlist.ems_collected_playlist_id
          )
        order by playlist.collected_at asc, playlist.ems_collected_playlist_id asc
        """, nativeQuery = true)
    List<EmsCollectedPlaylistEntity> findPendingTidalHomeTrackBackfill(
        @Param("sourceIds") List<String> sourceIds,
        Pageable pageable
    );

    @Query(value = """
        select
            source.source_id as "sourceId",
            count(distinct playlist.ems_collected_playlist_id) as "playlistCount",
            count(distinct case
                when link.ems_collected_track_id is not null then playlist.ems_collected_playlist_id
                else null
            end) as "playlistWithTracksCount",
            count(distinct playlist.ems_collected_playlist_id)
                - count(distinct case
                    when link.ems_collected_track_id is not null then playlist.ems_collected_playlist_id
                    else null
                end) as "playlistWithoutTracksCount",
            count(link.ems_collected_track_id) as "linkedTrackCount"
        from ems_collected_playlist_source source
        join ems_collected_playlist playlist
          on playlist.ems_collected_playlist_id = source.ems_collected_playlist_id
        left join ems_collected_playlist_track link
          on link.ems_collected_playlist_id = playlist.ems_collected_playlist_id
        where source.source_platform = 'tidal'
          and source.collection_source = 'public_pool'
        group by source.source_id
        order by source.source_id
        """, nativeQuery = true)
    List<TidalHomeBackfillSourceSummaryRow> summarizeTidalHomeBackfillBySource();

    @Query(
        value = """
            select playlist.*
            from ems_collected_playlist playlist
            join ems_collected_playlist_source source
              on source.ems_collected_playlist_id = playlist.ems_collected_playlist_id
            where source.source_platform = 'tidal'
              and source.collection_source = 'public_pool'
              and source.source_id = :sourceId
            order by source.collected_at desc, playlist.ems_collected_playlist_id desc
            """,
        countQuery = """
            select count(*)
            from ems_collected_playlist_source source
            where source.source_platform = 'tidal'
              and source.collection_source = 'public_pool'
              and source.source_id = :sourceId
            """,
        nativeQuery = true
    )
    Page<EmsCollectedPlaylistEntity> findTidalHomeBySourceId(
        @Param("sourceId") String sourceId,
        Pageable pageable
    );

    @Query(
        value = """
            select p.ems_collected_playlist_id
            from ems_collected_playlist p
            where not exists (
                select 1
                from ems_collected_playlist_track pt
                where pt.ems_collected_playlist_id = p.ems_collected_playlist_id
            )
            """,
        nativeQuery = true
    )
    List<Long> findIdsWithoutTracks();

    @Modifying
    @Query(
        value = """
            delete from ems_collected_playlist p
            where not exists (
                select 1
                from ems_collected_playlist_track pt
                where pt.ems_collected_playlist_id = p.ems_collected_playlist_id
            )
            """,
        nativeQuery = true
    )
    int deletePlaylistsWithoutTracks();

    interface TidalHomeBackfillSourceSummaryRow {
        String getSourceId();
        long getPlaylistCount();
        long getPlaylistWithTracksCount();
        long getPlaylistWithoutTracksCount();
        long getLinkedTrackCount();
    }
}
