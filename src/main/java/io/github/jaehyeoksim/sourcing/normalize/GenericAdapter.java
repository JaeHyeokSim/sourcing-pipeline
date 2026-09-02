package io.github.jaehyeoksim.sourcing.normalize;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 사이트 전용 어댑터가 없을 때 쓰는 기본 규칙.
 * 확장이 표준 스키마(title, price, currency, images, options)로 보내주면 그대로 통과시킨다.
 */
@Component
public class GenericAdapter implements SiteAdapter {

    private static final String NON_NUMERIC = "[^0-9.-]";

    @Override
    public String siteCode() {
        return "generic";
    }

    @Override
    public NormalizedProduct normalize(JsonNode raw, String sourceUrl) {
        String site = text(raw, "siteCode", "generic");
        String externalId = text(raw, "externalId", null);
        if (externalId == null || externalId.isBlank()) {
            throw new NormalizationException("externalId 가 비어 있습니다");
        }
        String title = text(raw, "title", null);
        if (title == null || title.isBlank()) {
            throw new NormalizationException("title 이 비어 있습니다");
        }

        List<NormalizedProduct.Option> options = new ArrayList<>();
        JsonNode opts = raw.path("options");
        if (opts.isArray()) {
            for (JsonNode o : opts) {
                options.add(new NormalizedProduct.Option(
                        text(o, "name", "옵션"),
                        text(o, "value", ""),
                        decimal(o, "extraPrice"),
                        o.hasNonNull("stock") ? o.get("stock").asInt() : null,
                        text(o, "imageUrl", null)));
            }
        }

        return new NormalizedProduct(
                site,
                externalId,
                trim(title, 500),
                nvl(decimal(raw, "price")),
                text(raw, "currency", "CNY"),
                firstImage(raw),
                sourceUrl,
                options);
    }

    private static String firstImage(JsonNode raw) {
        JsonNode images = raw.path("images");
        if (images.isArray() && !images.isEmpty()) {
            return images.get(0).asString(null);
        }
        return text(raw, "mainImageUrl", null);
    }

    static String text(JsonNode node, String field, String defaultValue) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? defaultValue : v.asString();
    }

    static BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(v.asString().replaceAll(NON_NUMERIC, ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
