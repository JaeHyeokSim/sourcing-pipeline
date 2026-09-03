package io.github.jaehyeoksim.sourcing.listing.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jaehyeoksim.sourcing.catalog.domain.Product;
import io.github.jaehyeoksim.sourcing.catalog.domain.ProductOption;
import io.github.jaehyeoksim.sourcing.common.ListingProperties;
import io.github.jaehyeoksim.sourcing.listing.market.MarketRules;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 마켓에 보내기 전에 걸러야 할 것들과, 얼마에 올릴지에 대한 규칙. */
class ListingRulesTest {

    private final ListingValidator validator = new ListingValidator();

    private static final MarketRules RULES = new MarketRules(20, 2, true, 1000L, List.of("최저가"));

    private static Product product(String title, String image, BigDecimal price) {
        return new Product("taobao", "1001", title, price, "CNY", image, "https://item.taobao.com/1001");
    }

    @Test
    @DisplayName("규칙을 만족하면 위반 사항이 없다")
    void passes() {
        Product p = product("무선 이어폰", "https://img/1.jpg", new BigDecimal("50.00"));

        assertThat(validator.validate(RULES, p, 12000)).isEmpty();
    }

    @Test
    @DisplayName("상품명이 길거나 금지 표현이 있으면 사유를 각각 알려준다")
    void reportsEveryTitleProblem() {
        Product p = product("최저가 무선 이어폰 노이즈캔슬링 블루투스 5.3 정품", "https://img/1.jpg", new BigDecimal("50.00"));

        List<String> violations = validator.validate(RULES, p, 12000);

        // 하나만 알려주면 고쳐서 다시 보냈을 때 또 다른 사유로 막힌다. 한 번에 다 준다.
        assertThat(violations).hasSize(2);
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("20자"));
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("최저가"));
    }

    @Test
    @DisplayName("대표 이미지가 없으면 등록하지 않는다")
    void requiresMainImage() {
        Product p = product("무선 이어폰", "   ", new BigDecimal("50.00"));

        assertThat(validator.validate(RULES, p, 12000))
                .anySatisfy(v -> assertThat(v).contains("대표 이미지"));
    }

    @Test
    @DisplayName("옵션 수가 마켓 상한을 넘으면 등록하지 않는다")
    void limitsOptionCount() {
        Product p = product("무선 이어폰", "https://img/1.jpg", new BigDecimal("50.00"));
        List<ProductOption> options = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            options.add(new ProductOption("색상", "색" + i, BigDecimal.ZERO, 10, null));
        }
        p.replaceOptions(options);

        assertThat(validator.validate(RULES, p, 12000))
                .anySatisfy(v -> assertThat(v).contains("옵션이 2개를 넘습니다"));
    }

    @Test
    @DisplayName("판매가가 마켓 최소 등록가보다 낮으면 등록하지 않는다")
    void enforcesMinimumPrice() {
        Product p = product("무선 이어폰", "https://img/1.jpg", new BigDecimal("1.00"));

        assertThat(validator.validate(RULES, p, 900))
                .anySatisfy(v -> assertThat(v).contains("최소 등록가"));
    }

    @Test
    @DisplayName("판매가는 환율·마진을 적용한 뒤 설정 단위로 올린다")
    void sellingPriceRoundsUp() {
        PricingPolicy policy = new PricingPolicy(new ListingProperties(
                3,
                Duration.ofSeconds(60),
                5,
                Duration.ofMinutes(2),
                new BigDecimal("195.0"),
                new BigDecimal("0.30"),
                100));

        // 75.50 * 195 * 1.3 = 19,139.25 → 100원 단위 올림
        assertThat(policy.sellingPrice(new BigDecimal("75.50"))).isEqualTo(19200L);
    }

    @Test
    @DisplayName("절상 단위가 1이면 원 단위로 올린다")
    void roundsToWonWhenUnitIsOne() {
        PricingPolicy policy = new PricingPolicy(new ListingProperties(
                3,
                Duration.ofSeconds(60),
                5,
                Duration.ofMinutes(2),
                new BigDecimal("195.0"),
                new BigDecimal("0.30"),
                1));

        assertThat(policy.sellingPrice(new BigDecimal("75.50"))).isEqualTo(19140L);
    }
}
