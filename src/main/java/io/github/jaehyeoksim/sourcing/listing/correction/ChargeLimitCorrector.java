package io.github.jaehyeoksim.sourcing.listing.correction;

import io.github.jaehyeoksim.sourcing.listing.market.MarketResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 반품배송비 상한 반려. 응답에 허용 상한이 들어 있으므로 그 값으로 낮춰 다시 보낸다.
 *
 * <p>상한은 카테고리마다 다르고 마켓이 문서로 공개하지 않아 전송 전에 알 수 없다.
 * 셀러가 입력한 값을 우선하되, 거절당하면 마켓이 허용하는 최대값으로 맞춘다.
 * <b>내리는 방향으로만</b> 고친다. 올려서 통과시키면 셀러가 정한 것보다 비싼 반품비가 고객에게 청구된다.
 */
@Component
public class ChargeLimitCorrector implements PayloadCorrector {

    static final String FIELD = "returnCharge";

    /** "반품배송비는 0원 ~ 3,000원 사이로 입력해 주세요" 류. 숫자 사이 콤마 유무는 마켓마다 다르다. */
    private static final Pattern LIMIT = Pattern.compile("반품\\s*배송비[^0-9]*0\\s*원?\\s*~\\s*([0-9][0-9,]*)\\s*원");

    @Override
    public String key() {
        return "charge-limit";
    }

    @Override
    public boolean supports(String marketCode) {
        return true;
    }

    @Override
    public Optional<Correction> correct(Map<String, Object> payload, MarketResult.Rejected rejection) {
        Matcher m = LIMIT.matcher(nullToEmpty(rejection.message()));
        if (!m.find() || !(payload.get(FIELD) instanceof Number current)) {
            return Optional.empty();
        }
        long limit = Long.parseLong(m.group(1).replace(",", ""));
        if (current.longValue() <= limit) {
            return Optional.empty();
        }
        Map<String, Object> fixed = new LinkedHashMap<>(payload);
        fixed.put(FIELD, limit);
        return Optional.of(new Correction(key(), fixed, FIELD + " " + current.longValue() + "→" + limit));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
