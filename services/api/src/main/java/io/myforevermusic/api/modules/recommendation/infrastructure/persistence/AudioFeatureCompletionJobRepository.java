package io.myforevermusic.api.modules.recommendation.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AudioFeatureCompletionJobRepository extends JpaRepository<AudioFeatureCompletionJobEntity, Long> {

    Optional<AudioFeatureCompletionJobEntity> findByTrackScopeAndTrackIdAndRequestedReason(
        String trackScope,
        String trackId,
        String requestedReason
    );

    @Query("""
        select job
        from AudioFeatureCompletionJobEntity job
        where (:status is null or job.status = :status)
        order by job.createdAt desc, job.jobId desc
        """)
    List<AudioFeatureCompletionJobEntity> findRecent(@Param("status") String status, Pageable pageable);

    @Query("""
        select job
        from AudioFeatureCompletionJobEntity job
        where job.status = 'unresolved'
          and (:trackScope is null or job.trackScope = :trackScope)
          and (:userId is null or job.userId = :userId)
          and (:lastError is null or job.lastError = :lastError)
        order by job.updatedAt desc, job.jobId desc
        """)
    List<AudioFeatureCompletionJobEntity> findUnresolvedForRequeue(
        @Param("trackScope") String trackScope,
        @Param("userId") String userId,
        @Param("lastError") String lastError,
        Pageable pageable
    );

    @Query("""
        select job
        from AudioFeatureCompletionJobEntity job
        where job.status = 'queued'
           or (job.status = 'retry_wait' and job.nextRetryAt is not null and job.nextRetryAt <= :now)
        order by job.priority desc, job.createdAt asc, job.jobId asc
        """)
    List<AudioFeatureCompletionJobEntity> findClaimable(@Param("now") Instant now, Pageable pageable);
}
