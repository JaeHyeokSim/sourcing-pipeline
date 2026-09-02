package io.github.jaehyeoksim.sourcing.collect.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import io.github.jaehyeoksim.sourcing.catalog.domain.Product;
import io.github.jaehyeoksim.sourcing.catalog.service.ProductUpsertService;
import io.github.jaehyeoksim.sourcing.collect.domain.CollectJob;
import io.github.jaehyeoksim.sourcing.collect.domain.JobStatus;
import io.github.jaehyeoksim.sourcing.collect.domain.RawProduct;
import io.github.jaehyeoksim.sourcing.collect.repository.CollectJobRepository;
import io.github.jaehyeoksim.sourcing.collect.repository.RawProductRepository;
import io.github.jaehyeoksim.sourcing.common.CollectorProperties;
import io.github.jaehyeoksim.sourcing.normalize.AdapterRegistry;
import io.github.jaehyeoksim.sourcing.normalize.NormalizationException;
import io.github.jaehyeoksim.sourcing.normalize.NormalizedProduct;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집 큐의 중심. 작업 등록 → 워커 클레임 → 결과 수용/실패 처리까지를 한 곳에서 관리한다.
 *
 * <p>설계상 지킨 것
 * <ul>
 *   <li><b>중복 방지</b>: (siteCode, externalId) 유니크. 같은 상품을 다시 요청하면 기존 작업을 돌려준다.</li>
 *   <li><b>동시성 제한</b>: RUNNING 개수를 상한으로 막는다. 대상 사이트에 부하를 주지 않기 위함.</li>
 *   <li><b>죽은 워커 회수</b>: lease 만료분을 주기적으로 큐에 되돌린다.</li>
 *   <li><b>영구 실패 구분</b>: 정규화 실패는 재시도해도 같은 결과라 즉시 FAILED 로 확정한다.</li>
 * </ul>
 */
@Service
public class CollectJobService {

    private static final Logger log = LoggerFactory.getLogger(CollectJobService.class);

    private final CollectJobRepository jobRepository;
    private final RawProductRepository rawRepository;
    private final ProductUpsertService productUpsertService;
    private final AdapterRegistry adapterRegistry;
    private final CollectorProperties properties;
    private final JsonMapper objectMapper;

    public CollectJobService(
            CollectJobRepository jobRepository,
            RawProductRepository rawRepository,
            ProductUpsertService productUpsertService,
            AdapterRegistry adapterRegistry,
            CollectorProperties properties,
            JsonMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.rawRepository = rawRepository;
        this.productUpsertService = productUpsertService;
        this.adapterRegistry = adapterRegistry;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 수집 요청. 이미 있는 상품이면 새로 만들지 않고 기존 작업을 돌려준다. */
    @Transactional
    public CollectJob enqueue(String siteCode, String externalId, String sourceUrl) {
        return jobRepository
                .findBySiteCodeAndExternalId(siteCode, externalId)
                .orElseGet(() -> jobRepository.save(
                        CollectJob.enqueue(siteCode, externalId, sourceUrl, properties.maxAttempts())));
    }

    /**
     * 워커가 다음 작업을 점유한다. 동시 실행 상한에 걸리면 빈 값을 돌려준다.
     */
    @Transactional
    public Optional<CollectJob> claimNext(String workerId) {
        long running = jobRepository.countByStatus(JobStatus.RUNNING);
        if (running >= properties.maxConcurrentJobs()) {
            log.debug("동시 실행 상한 도달 ({}/{}), 클레임 보류", running, properties.maxConcurrentJobs());
            return Optional.empty();
        }

        List<CollectJob> candidates = jobRepository.findClaimable(Instant.now(), Limit.of(1));
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        CollectJob job = candidates.get(0);
        job.claim(workerId, properties.claimTimeout());
        log.debug("작업 {} 클레임 by {} (시도 {}/{})", job.getId(), workerId, job.getAttempt(), job.getMaxAttempts());
        return Optional.of(job);
    }

    /**
     * 워커가 수집한 원본을 받아 정규화까지 진행한다.
     *
     * @return 저장된 상품
     */
    @Transactional
    public Product submitResult(Long jobId, String workerId, JsonNode rawPayload) {
        CollectJob job = mustFind(jobId);
        if (job.getStatus() != JobStatus.RUNNING) {
            throw new IllegalStateException("RUNNING 상태가 아닌 작업입니다: " + job.getStatus());
        }
        if (workerId != null && !workerId.equals(job.getWorkerId())) {
            throw new IllegalStateException("이 작업을 점유한 워커가 아닙니다");
        }

        rawRepository.save(new RawProduct(job.getId(), job.getSiteCode(), writeJson(rawPayload)));

        NormalizedProduct normalized;
        try {
            normalized = adapterRegistry.resolve(job.getSiteCode()).normalize(rawPayload, job.getSourceUrl());
        } catch (NormalizationException e) {
            // 원본 구조 문제는 재시도해도 동일하므로 즉시 확정 실패로 처리한다.
            job.failPermanently("정규화 실패: " + e.getMessage());
            throw e;
        }

        Product product = productUpsertService.upsert(normalized);
        job.succeed();
        log.info("작업 {} 성공 → 상품 {} ({})", job.getId(), product.getId(), product.getTitle());
        return product;
    }

    /** 워커가 보고한 실패. 재시도 여지가 있으면 백오프 후 큐로 되돌린다. */
    @Transactional
    public CollectJob reportFailure(Long jobId, String reason) {
        CollectJob job = mustFind(jobId);
        boolean retrying = job.fail(reason, properties.retryBaseDelay());
        log.warn("작업 {} 실패({}) → {}", job.getId(), reason, retrying ? "재시도 예약" : "확정 실패");
        return job;
    }

    @Transactional
    public CollectJob cancel(Long jobId) {
        CollectJob job = mustFind(jobId);
        job.cancel();
        return job;
    }

    @Transactional(readOnly = true)
    public CollectJob get(Long jobId) {
        return mustFind(jobId);
    }

    @Transactional(readOnly = true)
    public QueueStats stats() {
        return new QueueStats(
                jobRepository.countByStatus(JobStatus.PENDING),
                jobRepository.countByStatus(JobStatus.RUNNING),
                jobRepository.countByStatus(JobStatus.SUCCEEDED),
                jobRepository.countByStatus(JobStatus.FAILED),
                properties.maxConcurrentJobs());
    }

    @Transactional(readOnly = true)
    public List<CollectJob> recent(JobStatus status, int limit) {
        return jobRepository.findByStatusOrderByIdDesc(status, Limit.of(limit));
    }

    /** lease 가 끊긴 작업을 큐로 되돌린다. 스케줄러가 호출한다. */
    @Transactional
    public int reclaimExpired() {
        List<CollectJob> expired = jobRepository.findExpiredLeases(Instant.now());
        for (CollectJob job : expired) {
            job.reclaim(properties.retryBaseDelay());
        }
        if (!expired.isEmpty()) {
            log.warn("lease 만료 작업 {}건 회수", expired.size());
        }
        return expired.size();
    }

    private CollectJob mustFind(Long jobId) {
        return jobRepository.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("원본 페이로드를 직렬화할 수 없습니다", e);
        }
    }

    public record QueueStats(long pending, long running, long succeeded, long failed, int maxConcurrent) {
    }
}
