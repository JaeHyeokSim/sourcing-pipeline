package io.github.jaehyeoksim.sourcing.listing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.jaehyeoksim.sourcing.catalog.domain.Product;
import io.github.jaehyeoksim.sourcing.catalog.repository.ProductRepository;
import io.github.jaehyeoksim.sourcing.listing.domain.ListingStatus;
import io.github.jaehyeoksim.sourcing.listing.domain.MarketListing;
import io.github.jaehyeoksim.sourcing.listing.market.SimulatedMarketClient;
import io.github.jaehyeoksim.sourcing.listing.repository.MarketListingRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 반려 보정 재전송을 등록 큐 전체 흐름 위에서 확인한다.
 *
 * <p>{@link ListingFlowTest} 와 같은 이유로 {@code @Transactional} 을 붙이지 않는다.
 * 마켓 호출 횟수는 대역을 감시(spy)해서 센다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:listing-correction-test;DB_CLOSE_DELAY=-1",
    "listing.max-attempts=3",
    "listing.retry-base-delay=60s"
})
class ListingCorrectionFlowTest {

    @Autowired
    private ListingService service;

    @Autowired
    private ListingDispatcher dispatcher;

    @Autowired
    private MarketListingRepository listingRepository;

    @Autowired
    private ProductRepository productRepository;

    @MockitoSpyBean
    private SimulatedMarketClient marketClient;

    @AfterEach
    void clean() {
        listingRepository.deleteAll();
        productRepository.deleteAll();
    }

    private MarketListing requestAndDispatch(String title) {
        Product product = productRepository.save(new Product(
                "taobao",
                "P" + System.nanoTime(),
                title,
                new BigDecimal("50.00"),
                "CNY",
                "https://img/1.jpg",
                "https://item.taobao.com/item.htm?id=1"));
        MarketListing listing = service.request(product.getId(), "coupang", false);
        dispatcher.dispatch();
        return listingRepository.findById(listing.getId()).orElseThrow();
    }

    @Test
    @DisplayName("반품비 상한 반려는 같은 전송 안에서 고쳐 보내 등록까지 끝낸다")
    void chargeLimitIsCorrectedAndListed() {
        MarketListing after = requestAndDispatch("[CHARGE_CAP] 무선 이어폰");

        assertThat(after.getStatus()).isEqualTo(ListingStatus.LISTED);
        assertThat(after.getCorrectionNote()).isEqualTo("charge-limit: returnCharge 5000→3000");
        // 재시도 큐를 거치지 않았으므로 시도 횟수는 1이다
        assertThat(after.getAttempt()).isEqualTo(1);
        verify(marketClient, times(2)).send(anyString(), any());
    }

    @Test
    @DisplayName("반려가 연달아 오면 규칙을 차례로 적용한다")
    void chainedRejectionsAreCorrectedInTurn() {
        MarketListing after = requestAndDispatch("[CHARGE_CAP][BRAND] 무선 이어폰");

        assertThat(after.getStatus()).isEqualTo(ListingStatus.LISTED);
        assertThat(after.getCorrectionNote())
                .contains("returnCharge 5000→3000")
                .contains("brand (없음)→GENERIC");
        verify(marketClient, times(3)).send(anyString(), any());
    }

    @Test
    @DisplayName("보정해도 안 되는 반려는 한 번만 고쳐 보고 확정 실패, 무엇을 시도했는지 남긴다")
    void uncorrectableRejectionStopsAfterOneTry() {
        MarketListing after = requestAndDispatch("[BRAND_IN_TITLE] 무선 이어폰");

        assertThat(after.getStatus()).isEqualTo(ListingStatus.FAILED);
        assertThat(after.getLastErrorCode()).isEqualTo("BRAND_REQUIRED");
        assertThat(after.getCorrectionNote()).isEqualTo("generic-brand: brand (없음)→GENERIC");
        verify(marketClient, times(2)).send(anyString(), any());
    }

    @Test
    @DisplayName("보정된 건도 원본이 그대로면 재전송 요청에 마켓을 다시 부르지 않는다")
    void correctedListingStaysIdempotent() {
        MarketListing first = requestAndDispatch("[CHARGE_CAP] 무선 이어폰");
        clearInvocations(marketClient);

        service.request(first.getProductId(), "coupang", true);
        dispatcher.dispatch();

        MarketListing after = listingRepository.findById(first.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ListingStatus.LISTED);
        assertThat(after.getMarketProductId()).isEqualTo(first.getMarketProductId());
        verify(marketClient, times(0)).send(anyString(), any());
    }
}
