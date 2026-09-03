package io.github.jaehyeoksim.sourcing.listing.market;

import io.github.jaehyeoksim.sourcing.catalog.domain.Product;
import io.github.jaehyeoksim.sourcing.catalog.domain.ProductOption;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 옵션 조합 하나하나를 개별 아이템으로 받는 마켓.
 * 같은 상품이라도 스마트스토어와 요청 형태가 완전히 달라, 이 차이를 어댑터가 흡수한다.
 */
@Component
public class CoupangAdapter implements MarketAdapter {

    private static final MarketRules RULES =
            new MarketRules(100, 50, true, 1000L, List.of("최저가", "1위"));

    @Override
    public String marketCode() {
        return "coupang";
    }

    @Override
    public MarketRules rules() {
        return RULES;
    }

    @Override
    public Map<String, Object> toPayload(Product product, long sellingPrice) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sellerProductName", product.getTitle());
        payload.put("displayCategoryCode", "0");
        payload.put("items", toItems(product, sellingPrice));
        return payload;
    }

    private static List<Map<String, Object>> toItems(Product product, long sellingPrice) {
        if (product.getOptions().isEmpty()) {
            return List.of(item("단일상품", sellingPrice, null, product.getMainImageUrl()));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProductOption o : product.getOptions()) {
            BigDecimal extra = o.getExtraPrice() == null ? BigDecimal.ZERO : o.getExtraPrice();
            long price = sellingPrice + extra.longValue();
            String image = o.getImageUrl() == null ? product.getMainImageUrl() : o.getImageUrl();
            items.add(item(o.getName() + ": " + o.getValue(), price, o.getStockQuantity(), image));
        }
        return items;
    }

    private static Map<String, Object> item(String name, long price, Integer stock, String image) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemName", name);
        item.put("salePrice", price);
        item.put("maximumBuyCount", stock == null ? 999 : stock);
        item.put("images", image == null ? List.of() : List.of(image));
        return item;
    }
}
