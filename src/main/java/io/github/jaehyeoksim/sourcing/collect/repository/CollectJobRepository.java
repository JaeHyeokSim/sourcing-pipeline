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

    /**
     * 클레임 게이트 행을 잠근다. 트랜잭션이 끝날 때까지 다른 클레임은 여기서 기다린다.
     * 상한 판단(개수 세기)과 점유를 한 덩어리로 만들기 위한 것으로, 후보 행 잠금만으로는
     * 동시에 들어온 요청이 같은 RUNNING 개수를 보고 함께 통과해 상한을 넘긴다.
     */
    @Query(value = "select id from collect_claim_gate where id = 1 for update", nativeQuery = true)
    Integer lockClaimGate();

    /** lease 가 만료된 채 RUNNING 으로 남은 작업 (워커가 죽었거나 탭이 닫힌 경우) */
    @Query("""
            select j from CollectJob j
            where j.status = io.github.jaehyeoksim.sourcing.collect.domain.JobStatus.RUNNING
              and j.leasedUntil < :now
            """)
    List<CollectJob> findExpiredLeases(@Param("now") Instant now);
}
