package io.github.jaehyeoksim.sourcing.collect.repository;

import io.github.jaehyeoksim.sourcing.collect.domain.CollectJob;
import io.github.jaehyeoksim.sourcing.collect.domain.JobStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CollectJobRepository extends JpaRepository<CollectJob, Long> {

    Optional<CollectJob> findBySiteCodeAndExternalId(String siteCode, String externalId);

    long countByStatus(JobStatus status);

    List<CollectJob> findByStatusOrderByIdDesc(JobStatus status, Limit limit);

    /**
     * 클레임 후보를 행 잠금과 함께 가져온다.
     * 워커가 여러 개 붙어도 같은 작업을 두 번 집어가지 않게 하는 지점이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select j from CollectJob j
            where j.status = io.github.jaehyeoksim.sourcing.collect.domain.JobStatus.PENDING
              and j.nextRunAt <= :now
            order by j.nextRunAt asc, j.id asc
            """)
    List<CollectJob> findClaimable(@Param("now") Instant now, Limit limit);

    /** lease 가 만료된 채 RUNNING 으로 남은 작업 (워커가 죽었거나 탭이 닫힌 경우) */
    @Query("""
            select j from CollectJob j
            where j.status = io.github.jaehyeoksim.sourcing.collect.domain.JobStatus.RUNNING
              and j.leasedUntil < :now
            """)
    List<CollectJob> findExpiredLeases(@Param("now") Instant now);
}
