package io.github.jaehyeoksim.sourcing.collect.domain;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 1건에 대한 수집 작업. 큐의 원소이자 상태 기계다.
 *
 * <pre>
 * PENDING ──claim──▶ RUNNING ──succeed──▶ SUCCEEDED
 *    ▲                  │
 *    └──retry(backoff)──┴──fail(attempt&lt;max)
 *                       └──fail(attempt=max)──▶ FAILED
 * </pre>
 */
@Entity
@Table(
        name = "collect_job",
        uniqueConstraints = @UniqueConstraint(name = "uk_job_dedup", columnNames = {"site_code", "external_id"}),
        indexes = {
            @Index(name = "ix_job_poll", columnList = "status, next_run_at"),
            @Index(name = "ix_job_lease", columnList = "status, leased_until")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 수집 대상 사이트 식별자 (taobao, 1688, zozo ...) */
    @Column(name = "site_code", nullable = false, length = 32)
    private String siteCode;

    /** 사이트 내 상품 고유값. 같은 상품을 두 번 큐에 넣지 않기 위한 멱등 키 */
    @Column(name = "external_id", nullable = false, length = 128)
    private String externalId;

    @Column(name = "source_url", nullable = false, length = 1024)
    private String sourceUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JobStatus status;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    /** 이 시각 이후에 클레임 가능. 백오프 재시도에 사용 */
    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    /** 클레임한 워커 식별자 (확장의 탭/세션 id) */
    @Column(name = "worker_id", length = 64)
    private String workerId;

    /** 점유 만료 시각. 넘기면 죽은 워커로 보고 회수한다 */
    @Column(name = "leased_until")
    private Instant leasedUntil;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    private CollectJob(String siteCode, String externalId, String sourceUrl, int maxAttempts) {
        this.siteCode = siteCode;
        this.externalId = externalId;
        this.sourceUrl = sourceUrl;
        this.status = JobStatus.PENDING;
        this.attempt = 0;
        this.maxAttempts = maxAttempts;
        this.nextRunAt = Instant.now();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static CollectJob enqueue(String siteCode, String externalId, String sourceUrl, int maxAttempts) {
        return new CollectJob(siteCode, externalId, sourceUrl, maxAttempts);
    }

    /** 워커가 점유한다. lease 를 걸어 다른 워커가 같은 작업을 잡지 못하게 한다. */
    public void claim(String workerId, Duration leaseFor) {
        this.status = JobStatus.RUNNING;
        this.workerId = workerId;
        this.attempt += 1;
        this.leasedUntil = Instant.now().plus(leaseFor);
        touch();
    }

    public void succeed() {
        this.status = JobStatus.SUCCEEDED;
        this.workerId = null;
        this.leasedUntil = null;
        this.lastError = null;
        touch();
    }

    /**
     * 실패 처리. 재시도 여지가 남아 있으면 지수 백오프로 PENDING 에 되돌리고,
     * 상한에 닿았으면 FAILED 로 확정한다.
     *
     * @return 재시도로 되돌렸으면 true
     */
    public boolean fail(String reason, Duration baseDelay) {
        this.lastError = truncate(reason);
        this.workerId = null;
        this.leasedUntil = null;
        if (this.attempt >= this.maxAttempts) {
            this.status = JobStatus.FAILED;
            touch();
            return false;
        }
        this.status = JobStatus.PENDING;
        // 2^(attempt-1) 배 지수 백오프: 30s → 60s → 120s
        long multiplier = 1L << Math.max(0, this.attempt - 1);
        this.nextRunAt = Instant.now().plus(baseDelay.multipliedBy(multiplier));
        touch();
        return true;
    }

    /** 재시도해도 결과가 같은 실패(원본 구조 오류 등). 시도 횟수와 무관하게 확정한다. */
    public void failPermanently(String reason) {
        this.lastError = truncate(reason);
        this.workerId = null;
        this.leasedUntil = null;
        this.status = JobStatus.FAILED;
        touch();
    }
    /** lease 만료된 작업을 큐로 돌려놓는다. */
    public void reclaim(Duration baseDelay) {
        fail("워커 응답 없음 (lease 만료)", baseDelay);
    }

    public void cancel() {
        this.status = JobStatus.CANCELED;
        this.workerId = null;
        this.leasedUntil = null;
        touch();
    }

    public boolean isRetryable() {
        return this.attempt < this.maxAttempts;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
