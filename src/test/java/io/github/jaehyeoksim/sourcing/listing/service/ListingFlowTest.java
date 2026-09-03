package io.github.jaehyeoksim.sourcing.listing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jaehyeoksim.sourcing.catalog.domain.Product;
import io.github.jaehyeoksim.sourcing.catalog.repository.ProductRepository;
import io.github.jaehyeoksim.sourcing.listing.domain.ListingStatus;
import io.github.jaehyeoksim.sourcing.listing.domain.MarketListing;
import io.github.jaehyeoksim.sourcing.listing.repository.MarketListingRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 등록 큐를 서버 쪽만으로 끝까지 돌려보는 테스트.
 *
 * <p>{@code @Transactional} 을 붙이지 않는다. 전송은 두 트랜잭션으로 나뉘어 있어서,
 * 테스트가 전체를 하나로 감싸면 "커밋된 뒤에도 상태가 남아 있는가"를 확인할 수 없다.
 *
 * <p>{@code test} 프로파일이라 배경 스케줄러는 뜨지 않고, 디스패처를 직접 한 번씩 돌린다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:listing-test;DB_CLOSE_DELAY=-1",
    "listing.max-attempts=3",
    "listing.retry-base-delay=60s"
})
class ListingFlowTest {

    @Autowired
    private ListingService service;

    @Autowired
    private ListingDispatcher dispatcher;

    @Autowired
    private MarketListingRepository listingRepository;

    @Autowired
    private ProductRepository productRepository;

    @AfterEach
    void clean() {
        listingRepository.deleteAll();
        productRepository.deleteAll();
    }

    private Product saveProduct(String title, String imageUrl) {
        return productRepository.save(new Product(
                "taobao",
                "P" + System.nanoTime(),
                title,
                new BigDecimal("50.00"),
                "CNY",
                imageUrl,
                "https://item.taobao.com/item.htm?id=1"));
    }

    private MarketListing reload(Long id) {
        return listingRepository.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("같은 상품을 같은 마켓에 두 번 요청해도 등록 건은 하나다")
    void requestIsIdempotent() {
        Product product = saveProduct("무선 이어폰", "https://img/1.jpg");

        MarketListing first = service.request(product.getId(), "smartstore", false);
        MarketListing second = service.request(product.getId(), "smartstore", false);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(listingRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("상품 하나를 여러 마켓에 올리면 마켓별로 따로 추적된다")
    void tracksEachMarketSeparately() {
        Product product = saveProduct("무선 이어폰", "https://img/1.jpg");

        service.requestAll(product.getId(), List.of("smartstore", "coupang"), false);
        dispatcher.dispatch();

        List<MarketListing> listings = service.byProduct(product.getId());
        assertThat(listings).hasSize(2);
        assertThat(listings).allSatisfy(l -> {
            assertThat(l.getStatus()).isEqualTo(ListingStatus.LISTED);
            assertThat(l.getMarketProductId()).isNotBlank();
        });
        // 마켓마다 요청 형태가 다르므로 페이로드 해시도 다르다
        assertThat(listings.get(0).getPayloadHash()).isNotEqualTo(listings.get(1).getPayloadHash());
    }

    @Test
    @DisplayName("지원하지 않는 마켓은 큐에 넣지 않고 요청 단계에서 거절한다")
    void rejectsUnknownMarket() {
        Product product = saveProduct("무선 이어폰", "https://img/1.jpg");

        assertThatThrownBy(() -> service.request(product.getId(), "gmarket", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 마켓");
        assertThat(listingRepository.count()).isZero();
    }

    @Test
    @DisplayName("마켓 규칙을 어긴 건은 전송하지 않고 바로 확정 실패한다")
    void ruleViolationNeverReachesMarket() {
        Product product = saveProduct("무선 이어폰", null);
        MarketListing listing = service.request(product.getId(), "smartstore", false);

        dispatcher.dispatch();

        MarketListing after = reload(listing.getId());
        assertThat(after.getStatus()).isEqualTo(ListingStatus.FAILED);
        assertThat(after.getLastErrorCode()).isEqualTo("RULE_VIOLATION");
        assertThat(after.getLastError()).contains("대표 이미지");
        // 마켓을 호출하지 않았으므로 시도 횟수는 늘지 않는다
        assertThat(after.getAttempt()).isZero();
    }

    @Test
    @DisplayName("마켓이 성공을 주면서 상품ID를 비워 보내면 성공으로 기록하지 않는다")
    void emptyMarketProductIdIsNotSuccess() {
        Product product = saveProduct("[NOID] 무선 이어폰", "https://img/1.jpg");
        MarketListing listing = service.request(product.getId(), "smartstore", false);

        dispatcher.dispatch();

        MarketListing after = reload(listing.getId());
        assertThat(after.getStatus()).isEqualTo(ListingStatus.FAILED);
        assertThat(after.getLastErrorCode()).isEqualTo("NO_MARKET_ID");
        assertThat(after.getMarketProductId()).isNull();
    }

    @Test
    @DisplayName("일시 실패는 백오프 후 다시 큐로, 확정 실패는 그대로 끝난다")
    void transientRetriesAndPermanentStops() {
        Product busy = saveProduct("[TRANSIENT] 무선 이어폰", "https://img/1.jpg");
        Product rejected = saveProduct("[REJECT] 무선 이어폰", "https://img/1.jpg");
        MarketListing busyListing = service.request(busy.getId(), "smartstore", false);
        MarketListing rejectedListing = service.request(rejected.getId(), "smartstore", false);

        dispatcher.dispatch();

        MarketListing afterBusy = reload(busyListing.getId());
        assertThat(afterBusy.getStatus()).isEqualTo(ListingStatus.QUEUED);
        assertThat(afterBusy.getAttempt()).isEqualTo(1);
        assertThat(afterBusy.getNextRunAt()).isAfter(Instant.now().plusSeconds(30));

        MarketListing afterRejected = reload(rejectedListing.getId());
        assertThat(afterRejected.getStatus()).isEqualTo(ListingStatus.FAILED);
        assertThat(afterRejected.getLastErrorCode()).isEqualTo("CATEGORY_INVALID");
        assertThat(afterRejected.getAttempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("백오프 대기 중인 건은 아직 집어가지 않는다")
    void backoffDelaysNextAttempt() {
        Product busy = saveProduct("[TRANSIENT] 무선 이어폰", "https://img/1.jpg");
        service.request(busy.getId(), "smartstore", false);
        dispatcher.dispatch();

        int dispatched = dispatcher.dispatch();

        assertThat(dispatched).isZero();
    }

    @Test
    @DisplayName("내용이 그대로면 재전송 요청이 와도 마켓에 다시 보내지 않는다")
    void unchangedPayloadIsNotResent() {
        Product product = saveProduct("무선 이어폰", "https://img/1.jpg");
        MarketListing listing = service.request(product.getId(), "smartstore", false);
        dispatcher.dispatch();
        String firstMarketId = reload(listing.getId()).getMarketProductId();

        service.request(product.getId(), "smartstore", true);
        dispatcher.dispatch();

        MarketListing after = reload(listing.getId());
        assertThat(after.getStatus()).isEqualTo(ListingStatus.LISTED);
        // 다시 보냈다면 대역이 새 상품ID 를 발급했을 것이다
        assertThat(after.getMarketProductId()).isEqualTo(firstMarketId);
        assertThat(after.getAttempt()).isZero();
    }

    @Test
    @DisplayName("상품이 바뀌면 재전송 요청이 실제 전송으로 이어진다")
    void changedProductIsResent() {
        Product product = saveProduct("무선 이어폰", "https://img/1.jpg");
        MarketListing listing = service.request(product.getId(), "smartstore", false);
        dispatcher.dispatch();
        String firstMarketId = reload(listing.getId()).getMarketProductId();

        product.refresh("무선 이어폰 2세대", new BigDecimal("60.00"), "CNY", "https://img/2.jpg");
        productRepository.save(product);
        service.request(product.getId(), "smartstore", true);
        dispatcher.dispatch();

        MarketListing after = reload(listing.getId());
        assertThat(after.getStatus()).isEqualTo(ListingStatus.LISTED);
        assertThat(after.getMarketProductId()).isNotEqualTo(firstMarketId);
        assertThat(after.getAttempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("전송 중인 건은 재전송 요청이 들어와도 큐로 되돌리지 않는다")
    void sendingIsNotRequeued() {
        Product product = saveProduct("무선 이어폰", "https://img/1.jpg");
        MarketListing listing = service.request(product.getId(), "smartstore", false);
        // 마켓 호출 직전 상태(SENDING)를 만들고, 응답을 받기 전에 재전송 요청이 들어온 상황
        service.beginSend(listing.getId());

        MarketListing again = service.request(product.getId(), "smartstore", true);

        assertThat(again.getStatus()).isEqualTo(ListingStatus.SENDING);
        assertThat(again.getAttempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("등록 현황은 실패 사유별로 집계해 보여준다")
    void statsGroupFailureReasons() {
        Product noImage = saveProduct("무선 이어폰", null);
        Product rejected = saveProduct("[REJECT] 무선 이어폰", "https://img/1.jpg");
        service.request(noImage.getId(), "smartstore", false);
        service.request(rejected.getId(), "smartstore", false);

        dispatcher.dispatch();

        ListingService.ListingStats stats = service.stats();
        assertThat(stats.failed()).isEqualTo(2);
        assertThat(stats.failuresByCode()).containsKeys("RULE_VIOLATION", "CATEGORY_INVALID");
        assertThat(stats.markets()).contains("smartstore", "coupang");
    }
}
