package io.github.jaehyeoksim.sourcing.listing.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jaehyeoksim.sourcing.catalog.domain.Product;
import io.github.jaehyeoksim.sourcing.catalog.repository.ProductRepository;
import io.github.jaehyeoksim.sourcing.listing.domain.ListingStatus;
import io.github.jaehyeoksim.sourcing.listing.domain.MarketListing;
import io.github.jaehyeoksim.sourcing.listing.repository.MarketListingRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 마켓에 보내놓고 응답을 못 받은 채 프로세스가 끊긴 상황.
 *
 * <p>전송 타임아웃을 0 으로 두어 "보낸 직후가 곧 방치된 상태"인 조건을 만든다.
 * 이 회수가 없으면 등록 건이 SENDING 에 영원히 남아, 재전송도 안 되고 실패로도 안 잡힌다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:listing-reclaim-test;DB_CLOSE_DELAY=-1",
    "listing.send-timeout=0s"
})
class ListingReclaimTest {

    @Autowired
    private ListingService service;

    @Autowired
    private MarketListingRepository listingRepository;

    @Autowired
    private ProductRepository productRepository;

    @AfterEach
    void clean() {
        listingRepository.deleteAll();
        productRepository.deleteAll();
    }

    @Test
    @DisplayName("응답 없이 남은 전송은 회수되어 다시 전송 대상이 된다")
    void stuckSendingIsReclaimed() {
        Product product = productRepository.save(new Product(
                "taobao", "R1", "무선 이어폰", new BigDecimal("50.00"),
                "CNY", "https://img/1.jpg", "https://item.taobao.com/item.htm?id=1"));
        MarketListing listing = service.request(product.getId(), "smartstore", false);
        service.beginSend(listing.getId()); // 보낸 뒤 응답을 받지 못한 상태

        assertThat(service.reclaimStuck()).isEqualTo(1);

        MarketListing after = listingRepository.findById(listing.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ListingStatus.QUEUED);
        assertThat(after.getLastErrorCode()).isEqualTo("SEND_TIMEOUT");
        assertThat(after.getSentAt()).isNull();
    }
}
