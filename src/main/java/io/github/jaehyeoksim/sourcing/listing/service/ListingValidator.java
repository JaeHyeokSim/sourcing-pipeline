package io.github.jaehyeoksim.sourcing.listing.service;

import io.github.jaehyeoksim.sourcing.catalog.domain.Product;
import io.github.jaehyeoksim.sourcing.listing.market.MarketRules;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 마켓에 보내기 전에 등록 조건을 확인한다.
 *
 * <p>보내고 나서 거절당해도 알 수는 있다. 다만 그때는 (1) 마켓 API 를 한 번 쓴 뒤고,
 * (2) 거절 사유가 마켓 문구 그대로라 사용자에게 무슨 말인지 전달되지 않으며,
 * (3) 재시도 큐에 남아 같은 실패를 상한까지 반복한다.
 * 그래서 <b>우리가 아는 규칙은 우리가 먼저 본다.</b>
 */
@Component
public class ListingValidator {

    public List<String> validate(MarketRules rules, Product product, long sellingPrice) {
        List<String> violations = new ArrayList<>();

        String title = product.getTitle() == null ? "" : product.getTitle().trim();
        if (title.isEmpty()) {
            violations.add("상품명이 비어 있습니다");
        } else if (title.length() > rules.maxTitleLength()) {
            violations.add("상품명이 %d자를 넘습니다 (현재 %d자)".formatted(rules.maxTitleLength(), title.length()));
        }

        for (String banned : rules.bannedWords()) {
            if (title.contains(banned)) {
                violations.add("상품명에 사용할 수 없는 표현이 있습니다: %s".formatted(banned));
            }
        }

        if (rules.requireMainImage() && isBlank(product.getMainImageUrl())) {
            violations.add("대표 이미지가 없습니다");
        }

        int optionCount = product.getOptions().size();
        if (optionCount > rules.maxOptionCount()) {
            violations.add("옵션이 %d개를 넘습니다 (현재 %d개)".formatted(rules.maxOptionCount(), optionCount));
        }

        if (sellingPrice < rules.minSellingPrice()) {
            violations.add("판매가가 최소 등록가(%d원)보다 낮습니다 (현재 %d원)"
                    .formatted(rules.minSellingPrice(), sellingPrice));
        }

        return violations;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
