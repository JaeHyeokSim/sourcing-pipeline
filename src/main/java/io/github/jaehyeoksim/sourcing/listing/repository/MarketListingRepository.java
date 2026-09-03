package io.github.jaehyeoksim.sourcing.listing.repository;

import io.github.jaehyeoksim.sourcing.listing.domain.ListingStatus;
import io.github.jaehyeoksim.sourcing.listing.domain.MarketListing;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketListingRepository extends JpaRepository<MarketListing, Long> {

    Optional<MarketListing> findByProductIdAndMarketCode(Long productId, String marketCode);

    List<MarketListing> findByProductIdOrderByMarketCodeAsc(Long productId);

    List<MarketListing> findByStatusOrderByIdDesc(ListingStatus status, Limit limit);

    long countByStatus(ListingStatus status);

    long countByMarketCodeAndStatus(String marketCode, ListingStatus status);

    /** 전송 차례가 된 건을 행 잠금과 함께 가져온다. 디스패처가 여럿이어도 같은 건을 두 번 보내지 않는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select l from MarketListing l
            where l.status = io.github.jaehyeoksim.sourcing.listing.domain.ListingStatus.QUEUED
              and l.nextRunAt <= :now
            order by l.nextRunAt asc, l.id asc
            """)
    List<MarketListing> findDispatchable(@Param("now") Instant now, Limit limit);

    /** 보냈는데 응답이 오지 않은 채 남은 건 (프로세스가 죽었거나 마켓 응답이 끊긴 경우) */
    @Query("""
            select l from MarketListing l
            where l.status = io.github.jaehyeoksim.sourcing.listing.domain.ListingStatus.SENDING
              and l.sentAt < :threshold
            """)
    List<MarketListing> findStuckSending(@Param("threshold") Instant threshold);

    /** 실패 사유 코드별 건수. "무엇이 왜 안 올라갔는가"를 한 화면에 보여주기 위한 집계 */
    @Query("""
            select l.lastErrorCode, count(l)
            from MarketListing l
            where l.status = io.github.jaehyeoksim.sourcing.listing.domain.ListingStatus.FAILED
            group by l.lastErrorCode
            order by count(l) desc
            """)
    List<Object[]> countFailuresByCode();
}
