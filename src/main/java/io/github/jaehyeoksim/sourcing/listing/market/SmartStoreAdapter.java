package io.github.jaehyeoksim.sourcing.listing.market;

import io.github.jaehyeoksim.sourcing.catalog.domain.Product;
import io.github.jaehyeoksim.sourcing.catalog.domain.ProductOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 옵션을 축(이름) 단위로 묶어 보내는 마켓. 같은 축의 값들이 하나의 선택 목록이 된다.
 */
@Component
public class SmartStoreAdapter implements MarketAdapter {

    private static final MarketRules RULES =
            new MarketRules(100, 100, true, 1000L, List.of("최저가", "정품보장"));

    @Override
    public String marketCode() {
        return "smartstore";
    }

    @Override
    public MarketRules rules() {
        return RULES;
    }

    @Override
    public Map<String, Object> toPayload(Product product, long sellingPrice) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", product.getTitle());
        payload.put("salePrice", sellingPrice);
        payload.put("representativeImage", product.getMainImageUrl());
        payload.put("originAreaCode", "중국");
        payload.put("optionGroups", toOptionGroups(product.getOptions()));
        return payload;
    }

    /** [색상: 블랙, 아이보리], [사이즈: M, L] 처럼 축별로 접어 보낸다. */
    private static List<Map<String, Object>> toOptionGroups(List<ProductOption> options) {
        Map<String, List<String>> byAxis = new LinkedHashMap<>();
        for (ProductOption o : options) {
            List<String> values = byAxis.computeIfAbsent(o.getName(), k -> new ArrayList<>());
            if (!values.contains(o.getValue())) {
                values.add(o.getValue());
            }
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        byAxis.forEach((axis, values) -> {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("groupName", axis);
            group.put("values", values);
            groups.add(group);
        });
        return groups;
    }
}
