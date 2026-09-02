package io.github.jaehyeoksim.sourcing.collect.web;

import io.github.jaehyeoksim.sourcing.catalog.domain.Product;
import io.github.jaehyeoksim.sourcing.collect.domain.CollectJob;
import io.github.jaehyeoksim.sourcing.collect.domain.JobStatus;
import io.github.jaehyeoksim.sourcing.collect.service.CollectJobService;
import io.github.jaehyeoksim.sourcing.collect.web.JobDtos.EnqueueRequest;
import io.github.jaehyeoksim.sourcing.collect.web.JobDtos.FailureRequest;
import io.github.jaehyeoksim.sourcing.collect.web.JobDtos.JobResponse;
import io.github.jaehyeoksim.sourcing.collect.web.JobDtos.ProductResponse;
import io.github.jaehyeoksim.sourcing.collect.web.JobDtos.ResultRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 크롬 확장(워커)과 대시보드가 사용하는 수집 API.
 *
 * <pre>
 * POST /api/v1/jobs               수집 요청 등록
 * POST /api/v1/jobs/claim         워커가 다음 작업 점유
 * POST /api/v1/jobs/{id}/result   수집 결과 제출
 * POST /api/v1/jobs/{id}/failure  실패 보고
 * GET  /api/v1/jobs/stats         큐 현황
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/jobs")
public class CollectJobController {

    private final CollectJobService service;

    public CollectJobController(CollectJobService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobResponse enqueue(@Valid @RequestBody EnqueueRequest request) {
        CollectJob job = service.enqueue(request.siteCode(), request.externalId(), request.sourceUrl());
        return JobResponse.from(job);
    }

    /** 점유할 작업이 없거나 동시 실행 상한이면 204 를 돌려준다. */
    @PostMapping("/claim")
    public ResponseEntity<JobResponse> claim(@RequestParam String workerId) {
        return service.claimNext(workerId)
                .map(job -> ResponseEntity.ok(JobResponse.from(job)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/result")
    public ProductResponse submitResult(@PathVariable Long id, @Valid @RequestBody ResultRequest request) {
        Product product = service.submitResult(id, request.workerId(), request.payload());
        return new ProductResponse(
                product.getId(),
                product.getSiteCode(),
                product.getExternalId(),
                product.getTitle(),
                product.getOptions().size());
    }

    @PostMapping("/{id}/failure")
    public JobResponse reportFailure(@PathVariable Long id, @Valid @RequestBody FailureRequest request) {
        return JobResponse.from(service.reportFailure(id, request.reason()));
    }

    @PostMapping("/{id}/cancel")
    public JobResponse cancel(@PathVariable Long id) {
        return JobResponse.from(service.cancel(id));
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable Long id) {
        return JobResponse.from(service.get(id));
    }

    @GetMapping("/stats")
    public CollectJobService.QueueStats stats() {
        return service.stats();
    }

    @GetMapping
    public List<JobResponse> recent(
            @RequestParam(defaultValue = "PENDING") JobStatus status,
            @RequestParam(defaultValue = "20") int limit) {
        return service.recent(status, limit).stream().map(JobResponse::from).toList();
    }
}
