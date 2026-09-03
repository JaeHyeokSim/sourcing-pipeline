package io.github.jaehyeoksim.sourcing.listing.service;

import io.github.jaehyeoksim.sourcing.common.ListingProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * 원가(외화) → 판매가(원) 계산.
 *
 * <p>마켓별 어댑터마다 각자 계산하게 두면 같은 상품이 마켓마다 다른 값으로 올라간다.
 * 계산은 한 곳에 두고, 어댑터는 결과 금액만 받는다.
 */
@Component
public class PricingPolicy {

    private final ListingProperties properties;

    public PricingPolicy(ListingProperties properties) {
        this.properties = properties;
    }

    /**
     * 판매가 = 원가 × 환율 × (1 + 마진율), 설정한 단위로 올림.
     *
     * <p>내림이 아니라 올림인 이유: 절사하면 마진율이 설정값보다 항상 조금 낮게 나온다.
     * 소액 상품일수록 그 차이가 커서, 저가 상품을 대량으로 올릴 때 손해가 누적된다.
     */
    public long sellingPrice(BigDecimal cost) {
        if (cost == null) {
            return 0L;
        }
        BigDecimal krw = cost.multiply(properties.exchangeRate())
                .multiply(BigDecimal.ONE.add(properties.marginRate()));
        int unit = Math.max(1, properties.roundTo());
        return krw.divide(BigDecimal.valueOf(unit), 0, RoundingMode.CEILING).longValue() * unit;
    }
}
