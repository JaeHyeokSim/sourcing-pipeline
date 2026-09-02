package io.github.jaehyeoksim.sourcing.normalize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TaobaoAdapterTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final TaobaoAdapter adapter = new TaobaoAdapter();

    private JsonNode json(String raw) {
        return mapper.readTree(raw);
    }

    @Test
    @DisplayName("대표가는 SKU 최저가로 잡는다")
    void lowestSkuPriceBecomesRepresentative() {
        JsonNode raw = json("""
                {
                  "goods": { "itemId": "770123", "title": "여성 니트 가디건", "mainImage": "https://img/1.jpg" },
                  "skus": [
                    { "price": "89.00", "stock": 12, "props": [ { "name": "색상", "value": "블랙" } ] },
                    { "price": "75.50", "stock": 3,  "props": [ { "name": "색상", "value": "아이보리" } ] }
                  ]
                }
                """);

        NormalizedProduct result = adapter.normalize(raw, "https://item.taobao.com/item.htm?id=770123");

        assertThat(result.externalId()).isEqualTo("770123");
        assertThat(result.priceAmount()).isEqualByComparingTo(new BigDecimal("75.50"));
        assertThat(result.currency()).isEqualTo("CNY");
        assertThat(result.options()).hasSize(2);
    }

    @Test
    @DisplayName("여러 SKU 에 중복 등장하는 같은 옵션은 한 번만 남긴다")
    void duplicatedOptionAxisIsFolded() {
        JsonNode raw = json("""
                {
                  "goods": { "itemId": "770124", "title": "반팔 티셔츠" },
                  "skus": [
                    { "price": "20", "props": [ { "name": "색상", "value": "블랙" }, { "name": "사이즈", "value": "M" } ] },
                    { "price": "20", "props": [ { "name": "색상", "value": "블랙" }, { "name": "사이즈", "value": "L" } ] }
                  ]
                }
                """);

        NormalizedProduct result = adapter.normalize(raw, "https://item.taobao.com/item.htm?id=770124");

        assertThat(result.options())
                .extracting(NormalizedProduct.Option::name, NormalizedProduct.Option::value)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("색상", "블랙"),
                        org.assertj.core.groups.Tuple.tuple("사이즈", "M"),
                        org.assertj.core.groups.Tuple.tuple("사이즈", "L"));
    }

    @Test
    @DisplayName("itemId 가 없으면 재시도해도 소용없는 정규화 실패로 끝낸다")
    void missingItemId() {
        JsonNode raw = json("""
                { "goods": { "title": "제목만 있는 상품" }, "skus": [] }
                """);

        assertThatThrownBy(() -> adapter.normalize(raw, "https://item.taobao.com/item.htm"))
                .isInstanceOf(NormalizationException.class)
                .hasMessageContaining("itemId");
    }

    @Test
    @DisplayName("SKU 가격이 없으면 상품 레벨 가격으로 대체한다")
    void fallsBackToGoodsPrice() {
        JsonNode raw = json("""
                {
                  "goods": { "itemId": "770125", "title": "가방", "price": "150" },
                  "skus": []
                }
                """);

        NormalizedProduct result = adapter.normalize(raw, "https://item.taobao.com/item.htm?id=770125");

        assertThat(result.priceAmount()).isEqualByComparingTo(new BigDecimal("150"));
        assertThat(result.options()).isEmpty();
    }
}
