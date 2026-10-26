package io.github.jaehyeoksim.sourcing.listing.correction;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jaehyeoksim.sourcing.listing.market.MarketResult;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 반려 문구 → 보정 규칙 매칭. 마켓 없이 규칙만 검증한다. */
class CorrectionChainTest {

    private final CorrectionChain chain =
            new CorrectionChain(List.of(new ChargeLimitCorrector(), new GenericBrandCorrector()));

    private static Map<String, Object> coupangPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sellerProductName", "무선 이어폰");
        payload.put("brand", "");
        payload.put("returnCharge", 5000L);
        return payload;
    }

    private static MarketResult.Rejected rejected(String message) {
        return new MarketResult.Rejected("X", message, false);
    }

    @Test
    @DisplayName("반품비 상한 반려는 응답의 상한값으로 낮춘다 (콤마 유무 무관)")
    void lowersReturnChargeToLimit() {
        for (String message : List.of(
                "반품배송비는 0원 ~ 3,000원 사이로 입력해 주세요.",
                "초도반품배송비/반품배송비는 0원~3000원 입니다")) {
            Optional<Correction> c = chain.next("coupang", coupangPayload(), rejected(message), Set.of());

            assertThat(c).isPresent();
            assertThat(c.get().payload().get("returnCharge")).isEqualTo(3000L);
            assertThat(c.get().note()).isEqualTo("returnCharge 5000→3000");
        }
    }

    @Test
    @DisplayName("이미 상한 이하면 고치지 않는다 — 반품비를 올려서 통과시키지 않는다")
    void neverRaisesReturnCharge() {
        Map<String, Object> payload = coupangPayload();
        payload.put("returnCharge", 2000L);

        Optional<Correction> c =
                chain.next("coupang", payload, rejected("반품배송비는 0원 ~ 3,000원 사이로 입력해 주세요."), Set.of());

        assertThat(c).isEmpty();
    }

    @Test
    @DisplayName("브랜드 요구 반려는 문구가 바뀌어도 GENERIC 으로 고친다")
    void brandRequiredInAnyWording() {
        for (String message : List.of(
                "브랜드 ID가 필요합니다.",
                "brandId 입력이 필요합니다. brand 필드에 GENERIC을 입력해 주세요.",
                "브랜드 정보가 필요합니다. 올바른 Brand ID와 Brand Name을 입력해 주세요.")) {
            Optional<Correction> c = chain.next("coupang", coupangPayload(), rejected(message), Set.of());

            assertThat(c).as(message).isPresent();
            assertThat(c.get().payload().get("brand")).isEqualTo("GENERIC");
        }
    }

    @Test
    @DisplayName("브랜드 보정은 그 반려를 쓰는 마켓에만 적용한다")
    void brandCorrectionIsMarketScoped() {
        Optional<Correction> c =
                chain.next("smartstore", coupangPayload(), rejected("브랜드 정보가 필요합니다."), Set.of());

        assertThat(c).isEmpty();
    }

    @Test
    @DisplayName("이미 GENERIC 이면 같은 반려를 다시 고치려 들지 않는다")
    void genericAlreadyAppliedStops() {
        Map<String, Object> payload = coupangPayload();
        payload.put("brand", "GENERIC");

        assertThat(chain.next("coupang", payload, rejected("브랜드 정보가 필요합니다."), Set.of())).isEmpty();
    }

    @Test
    @DisplayName("이번 전송에서 이미 쓴 규칙은 다시 쓰지 않는다")
    void usedRuleIsSkipped() {
        Optional<Correction> c = chain.next(
                "coupang",
                coupangPayload(),
                rejected("반품배송비는 0원 ~ 3,000원 사이로 입력해 주세요."),
                Set.of("charge-limit"));

        assertThat(c).isEmpty();
    }

    @Test
    @DisplayName("일시 실패·성공·관계없는 반려에는 손대지 않는다")
    void ignoresUnrelatedResults() {
        Map<String, Object> payload = coupangPayload();

        assertThat(chain.next("coupang", payload,
                new MarketResult.Rejected("BUSY", "반품배송비는 0원 ~ 3,000원", true), Set.of())).isEmpty();
        assertThat(chain.next("coupang", payload, new MarketResult.Accepted("C-1"), Set.of())).isEmpty();
        assertThat(chain.next("coupang", payload, rejected("카테고리를 확인할 수 없습니다"), Set.of())).isEmpty();
    }

    @Test
    @DisplayName("보정은 원본 페이로드를 바꾸지 않고 사본을 돌려준다")
    void doesNotMutateInput() {
        Map<String, Object> payload = new HashMap<>(coupangPayload());

        chain.next("coupang", payload, rejected("브랜드 정보가 필요합니다."), Set.of());

        assertThat(payload.get("brand")).isEqualTo("");
    }
}
