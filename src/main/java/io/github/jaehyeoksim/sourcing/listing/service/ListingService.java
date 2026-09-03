package io.github.jaehyeoksim.sourcing.listing.service;

import io.github.jaehyeoksim.sourcing.catalog.domain.Product;
import io.github.jaehyeoksim.sourcing.catalog.repository.ProductRepository;
import io.github.jaehyeoksim.sourcing.common.ListingProperties;
import io.github.jaehyeoksim.sourcing.listing.domain.ListingStatus;
import io.github.jaehyeoksim.sourcing.listing.domain.MarketListing;
import io.github.jaehyeoksim.sourcing.listing.market.MarketAdapter;
import io.github.jaehyeoksim.sourcing.listing.market.MarketRegistry;
import io.github.jaehyeoksim.sourcing.listing.market.MarketResult;
import io.github.jaehyeoksim.sourcing.listing.repository.MarketListingRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 오픈마켓 등록 큐. 상품 하나를 마켓 한 곳에 올리는 과정을 상태로 관리한다.
 *
 * <p>전송은 두 트랜잭션으로 나뉜다.
 * <ol>
 *   <li>{@link #beginSend} — 규칙 검사·페이로드 생성·SENDING 기록까지 하고 <b>커밋</b></li>
 *   <li>(트랜잭션 밖) 마켓 호출</li>
 *   <li>{@link #completeSend} — 응답을 상태에 반영</li>
 * </ol>
 * 한 트랜잭션으로 묶으면 마켓이 느려질 때 DB 커넥션이 같이 묶이고, 프로세스가 죽으면
 * "마켓에는 올라갔는데 우리 쪽엔 흔적이 없는" 상태가 된다. 나눠 두면 최소한
 * SENDING 이 남아 다시 확인할 수 있다.
 */
@Service
public class ListingService {

    private static final Logger log = LoggerFactory.getLogger(ListingService.class);

    private final MarketListingRepository listingRepository;
    private final ProductRepository productRepository;
    private final MarketRegistry marketRegistry;
    private final ListingValidator validator;
    private final PricingPolicy pricingPolicy;
    private final ListingProperties properties;
    private final JsonMapper objectMapper;

    public ListingService(
            MarketListingRepository listingRepository,
            ProductRepository productRepository,
            MarketRegistry marketRegistry,
            ListingValidator validator,
            PricingPolicy pricingPolicy,
            ListingProperties properties,
            JsonMapper objectMapper) {
        this.listingRepository = listingRepository;
        this.productRepository = productRepository;
        this.marketRegistry = marketRegistry;
        this.validator = validator;
        this.pricingPolicy = pricingPolicy;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 등록 요청. 같은 (상품, 마켓) 조합이면 새로 만들지 않는다.
     *
     * @param force 이미 올라간 건이라도 다시 큐에 넣을지 (상품 정보가 바뀌었을 때)
     */
    @Transactional
    public MarketListing request(Long productId, String marketCode, boolean force) {
        if (!productRepository.existsById(productId)) {
            throw new IllegalArgumentException("상품을 찾을 수 없습니다: " + productId);
        }
        marketRegistry
                .find(marketCode)
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 마켓입니다: " + marketCode));

        Optional<MarketListing> existing = listingRepository.findByProductIdAndMarketCode(productId, marketCode);
        if (existing.isEmpty()) {
            return listingRepository.save(MarketListing.queue(productId, marketCode, properties.maxAttempts()));
        }

        MarketListing listing = existing.get();
        // 전송 중인 건을 다시 큐에 넣으면 같은 상품이 마켓에 두 번 올라갈 수 있다.
        if (listing.getStatus() == ListingStatus.SENDING) {
            return listing;
        }
        if (force || listing.getStatus() == ListingStatus.FAILED) {
            listing.requeue();
        }
        return listing;
    }

    /** 상품 하나를 여러 마켓에 한 번에 올린다. */
    @Transactional
    public List<MarketListing> requestAll(Long productId, List<String> marketCodes, boolean force) {
        List<MarketListing> result = new ArrayList<>();
        for (String code : marketCodes) {
            result.add(request(productId, code, force));
        }
        return result;
    }

    /** 전송 차례가 된 건들의 id. 잠금이 걸린 트랜잭션은 짧게 끝낸다. */
    @Transactional
    public List<Long> claimDispatchable() {
        return listingRepository.findDispatchable(Instant.now(), Limit.of(properties.batchSize())).stream()
                .map(MarketListing::getId)
                .toList();
    }

    /**
     * 전송 준비. 규칙 위반이면 여기서 확정 실패로 끝내고 빈 값을 돌려준다(마켓 호출 없음).
     * 보낼 내용이 직전과 같으면 역시 보내지 않는다.
     */
    @Transactional
    public Optional<SendPlan> beginSend(Long listingId) {
        MarketListing listing = mustFind(listingId);
        if (listing.getStatus() != ListingStatus.QUEUED) {
            return Optional.empty();
        }

        Optional<Product> product = productRepository.findById(listing.getProductId());
        if (product.isEmpty()) {
            // 수집 상품이 지워졌는데 등록 건만 남은 경우. 다시 시도해도 결과가 같다.
            listing.failPermanently("PRODUCT_MISSING", "상품이 존재하지 않습니다: " + listing.getProductId());
            return Optional.empty();
        }

        Optional<MarketAdapter> adapter = marketRegistry.find(listing.getMarketCode());
        if (adapter.isEmpty()) {
            listing.failPermanently("MARKET_UNSUPPORTED", "지원하지 않는 마켓입니다: " + listing.getMarketCode());
            return Optional.empty();
        }

        long sellingPrice = pricingPolicy.sellingPrice(product.get().getPriceAmount());
        List<String> violations = validator.validate(adapter.get().rules(), product.get(), sellingPrice);
        if (!violations.isEmpty()) {
            listing.failPermanently("RULE_VIOLATION", String.join(" / ", violations));
            log.info("등록 {} 규칙 위반으로 전송하지 않음: {}", listingId, violations);
            return Optional.empty();
        }

        Map<String, Object> payload = new LinkedHashMap<>(adapter.get().toPayload(product.get(), sellingPrice));
        String hash = hash(payload);
        if (listing.isUnchanged(hash)) {
            listing.keepListed();
            log.debug("등록 {} 내용 변경 없음, 전송 생략", listingId);
            return Optional.empty();
        }

        listing.markSending();
        return Optional.of(new SendPlan(listingId, listing.getMarketCode(), payload, hash));
    }

    /** 마켓 응답을 상태에 반영한다. */
    @Transactional
    public MarketListing completeSend(Long listingId, String payloadHash, MarketResult result) {
        MarketListing listing = mustFind(listingId);
        switch (result) {
            case MarketResult.Accepted(String marketProductId) -> {
                if (marketProductId == null || marketProductId.isBlank()) {
                    // 마켓이 200 을 주면서 상품ID 를 비워 보내는 경우가 있다.
                    // 이걸 성공으로 적으면 "등록됐다는데 마켓에는 없는" 건이 조용히 쌓인다.
                    listing.failPermanently("NO_MARKET_ID", "마켓이 성공을 반환했지만 상품ID가 없습니다");
                    log.warn("등록 {} 마켓 상품ID 없음 → 성공으로 보지 않음", listingId);
                } else {
                    listing.succeed(marketProductId, payloadHash);
                    log.info("등록 {} 완료 → {} {}", listingId, listing.getMarketCode(), marketProductId);
                }
            }
            case MarketResult.Rejected(String code, String message, boolean retryable) -> {
                if (retryable) {
                    boolean retrying = listing.fail(code, message, properties.retryBaseDelay());
                    log.warn("등록 {} 실패({}) → {}", listingId, code, retrying ? "재시도 예약" : "확정 실패");
                } else {
                    listing.failPermanently(code, message);
                    log.warn("등록 {} 확정 실패({}): {}", listingId, code, message);
                }
            }
        }
        return listing;
    }

    /** 마켓 호출 자체가 터진 경우(네트워크 등). 일시 실패로 본다. */
    @Transactional
    public MarketListing recordTransportError(Long listingId, String message) {
        MarketListing listing = mustFind(listingId);
        listing.fail("TRANSPORT_ERROR", message, properties.retryBaseDelay());
        return listing;
    }

    /** 응답 없이 SENDING 에 머문 건을 큐로 되돌린다. */
    @Transactional
    public int reclaimStuck() {
        Instant threshold = Instant.now().minus(properties.sendTimeout());
        List<MarketListing> stuck = listingRepository.findStuckSending(threshold);
        for (MarketListing listing : stuck) {
            listing.reclaim(properties.retryBaseDelay());
        }
        if (!stuck.isEmpty()) {
            log.warn("응답 없는 전송 {}건 회수", stuck.size());
        }
        return stuck.size();
    }

    @Transactional(readOnly = true)
    public MarketListing get(Long listingId) {
        return mustFind(listingId);
    }

    @Transactional(readOnly = true)
    public List<MarketListing> byProduct(Long productId) {
        return listingRepository.findByProductIdOrderByMarketCodeAsc(productId);
    }

    @Transactional(readOnly = true)
    public List<MarketListing> recent(ListingStatus status, int limit) {
        return listingRepository.findByStatusOrderByIdDesc(status, Limit.of(limit));
    }

    @Transactional(readOnly = true)
    public ListingStats stats() {
        Map<String, Long> failures = new LinkedHashMap<>();
        for (Object[] row : listingRepository.countFailuresByCode()) {
            failures.put(row[0] == null ? "UNKNOWN" : row[0].toString(), ((Number) row[1]).longValue());
        }
        return new ListingStats(
                listingRepository.countByStatus(ListingStatus.QUEUED),
                listingRepository.countByStatus(ListingStatus.SENDING),
                listingRepository.countByStatus(ListingStatus.LISTED),
                listingRepository.countByStatus(ListingStatus.FAILED),
                marketRegistry.supportedMarkets(),
                failures);
    }

    private MarketListing mustFind(Long id) {
        return listingRepository.findById(id).orElseThrow(() -> new ListingNotFoundException(id));
    }

    /** 페이로드 내용이 바뀌었는지 판단하는 기준값. 필드 순서를 유지해 같은 내용이면 같은 해시가 나온다. */
    private String hash(Map<String, Object> payload) {
        try {
            byte[] json = objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (Exception e) {
            throw new IllegalStateException("페이로드 해시를 만들 수 없습니다", e);
        }
    }

    public record ListingStats(
            long queued,
            long sending,
            long listed,
            long failed,
            List<String> markets,
            Map<String, Long> failuresByCode) {
    }
}
