package io.github.jaehyeoksim.sourcing.listing.correction;

import io.github.jaehyeoksim.sourcing.listing.market.MarketResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 브랜드 정보 요구 반려. 해외 소싱 상품은 대부분 브랜드가 없거나 마켓에 등록되지 않은 이름이라
 * 마켓이 정한 "브랜드 없음" 값({@code GENERIC})으로 다시 보낸다.
 *
 * <p>문구를 고정 문자열로 비교하지 않는다. 같은 반려가
 * "브랜드 ID가 필요합니다" → "brandId 입력이 필요합니다" → "브랜드 정보가 필요합니다" 식으로
 * 바뀌어 왔고, 문자열 비교는 바뀔 때마다 조용히 빗나간다.
 * 그래서 "브랜드를 가리키는 말 + 요구하는 말"의 조합으로 판정한다.
 *
 * <p>GENERIC 으로도 거절되면 더 고치지 않는다. 그 경우는 대개 상품명에 등록 브랜드명이 들어 있는 것인데,
 * 상품명은 자동으로 바꾸지 않는다({@link PayloadCorrector} 참고).
 */
@Component
public class GenericBrandCorrector implements PayloadCorrector {

    static final String FIELD = "brand";
    static final String GENERIC = "GENERIC";

    private static final Pattern BRAND = Pattern.compile("브랜드|brand", Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUIRED = Pattern.compile("필요|입력해|올바른|required", Pattern.CASE_INSENSITIVE);

    @Override
    public String key() {
        return "generic-brand";
    }

    @Override
    public boolean supports(String marketCode) {
        return "coupang".equals(marketCode);
    }

    @Override
    public Optional<Correction> correct(Map<String, Object> payload, MarketResult.Rejected rejection) {
        String message = rejection.message() == null ? "" : rejection.message();
        if (!BRAND.matcher(message).find() || !REQUIRED.matcher(message).find()) {
            return Optional.empty();
        }
        Object before = payload.get(FIELD);
        if (GENERIC.equals(before)) {
            return Optional.empty();
        }
        Map<String, Object> fixed = new LinkedHashMap<>(payload);
        fixed.put(FIELD, GENERIC);
        String shown = before == null || before.toString().isBlank() ? "(없음)" : before.toString();
        return Optional.of(new Correction(key(), fixed, FIELD + " " + shown + "→" + GENERIC));
    }
}
