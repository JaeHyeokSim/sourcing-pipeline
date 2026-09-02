package io.github.jaehyeoksim.sourcing.normalize;

import java.math.BigDecimal;
import java.util.List;

/**
 * 어댑터가 사이트별 원본을 변환해 내놓는 공통 형태.
 */
public record NormalizedProduct(
        String siteCode,
        String externalId,
        String title,
        BigDecimal priceAmount,
        String currency,
        String mainImageUrl,
        String sourceUrl,
        List<Option> options) {

    public record Option(String name, String value, BigDecimal extraPrice, Integer stockQuantity, String imageUrl) {
    }
}
