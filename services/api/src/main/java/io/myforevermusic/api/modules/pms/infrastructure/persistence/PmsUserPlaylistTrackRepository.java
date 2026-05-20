package io.myforevermusic.api.modules.pms.infrastructure.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PmsUserPlaylistTrackRepository extends JpaRepository<PmsUserPlaylistTrackEntity, Long> {

    List<PmsUserPlaylistTrackEntity> findByPlaylist_UserPlaylistIdOrderBySortOrderAscUserPlaylistTrackIdAsc(Long userPlaylistId);

    @Query("""
        select track
        from PmsUserTrackEntity track
        where lower(track.artistName) = lower(:artistName)
          and exists (
              select link.userPlaylistTrackId
              from PmsUserPlaylistTrackEntity link
              where link.track = track
                and link.playlist.userId = :userId
          )
        order by track.sourcePlatform asc, track.title asc, track.trackId asc
        """)
    List<PmsUserTrackEntity> findDistinctTracksByUserIdAndArtistName(
        @Param("userId") String userId,
        @Param("artistName") String artistName,
        Pageable pageable
    );

    void deleteByPlaylist_UserPlaylistId(Long userPlaylistId);
}
