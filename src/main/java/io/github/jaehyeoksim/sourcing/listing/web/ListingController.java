package io.github.jaehyeoksim.sourcing.listing.web;

import io.github.jaehyeoksim.sourcing.listing.domain.ListingStatus;
import io.github.jaehyeoksim.sourcing.listing.service.ListingService;
import io.github.jaehyeoksim.sourcing.listing.web.ListingDtos.ListingRequest;
import io.github.jaehyeoksim.sourcing.listing.web.ListingDtos.ListingResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 오픈마켓 등록 API.
 *
 * <pre>
 * POST /api/v1/listings              상품을 여러 마켓에 등록 요청
 * POST /api/v1/listings/{id}/retry   실패 건 재전송
 * GET  /api/v1/listings/stats        등록 현황 + 실패 사유별 집계
 * GET  /api/v1/listings?status=      상태별 목록
 * GET  /api/v1/listings/product/{id} 상품 1건의 마켓별 등록 상태
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/listings")
public class ListingController {

    private final ListingService service;

    public ListingController(ListingService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public List<ListingResponse> request(@Valid @RequestBody ListingRequest request) {
        return service.requestAll(request.productId(), request.markets(), request.force()).stream()
                .map(ListingResponse::from)
                .toList();
    }

    /** 실패 건 재전송. 시도 횟수를 초기화하고 큐에 되돌린다. */
    @PostMapping("/{id}/retry")
    public ListingResponse retry(@PathVariable Long id) {
        var listing = service.get(id);
        return ListingResponse.from(service.request(listing.getProductId(), listing.getMarketCode(), true));
    }

    @GetMapping("/{id}")
    public ListingResponse get(@PathVariable Long id) {
        return ListingResponse.from(service.get(id));
    }

    @GetMapping("/product/{productId}")
    public List<ListingResponse> byProduct(@PathVariable Long productId) {
        return service.byProduct(productId).stream().map(ListingResponse::from).toList();
    }

    @GetMapping("/stats")
    public ListingService.ListingStats stats() {
        return service.stats();
    }

    @GetMapping
    public List<ListingResponse> recent(
            @RequestParam(defaultValue = "QUEUED") ListingStatus status,
            @RequestParam(defaultValue = "20") int limit) {
        return service.recent(status, limit).stream().map(ListingResponse::from).toList();
    }
}
