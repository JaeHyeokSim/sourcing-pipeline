package io.github.jaehyeoksim.sourcing.listing.market;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 마켓 계정 없이 파이프라인 전체를 돌려보기 위한 대역.
 *
 * <p>실패를 난수로 만들면 재현이 안 되므로 <b>페이로드에서 결정</b>한다.
 * 상품명이 {@code [TRANSIENT]} 로 시작하면 일시 실패(재시도 대상),
 * {@code [REJECT]} 로 시작하면 확정 실패, {@code [NOID]} 로 시작하면
 * "성공했다면서 상품ID를 안 주는" 응답을 돌려준다. 세 경우 모두 큐 동작이 달라야 한다.
 *
 * <p>실제 마켓 연동은 {@link MarketClient} 구현체를 하나 더 만들어 갈아끼우면 되고,
 * 큐·재시도·상태 추적 코드는 그대로 둔다.
 */
@Component
public class SimulatedMarketClient implements MarketClient {

    private static final Logger log = LoggerFactory.getLogger(SimulatedMarketClient.class);

    @Override
    public MarketResult send(String marketCode, Map<String, Object> payload) {
        String title = title(payload);
        log.debug("[대역] {} 전송: {}", marketCode, title);

        if (title.startsWith("[TRANSIENT]")) {
            return new MarketResult.Rejected("MARKET_BUSY", "마켓 응답 지연", true);
        }
        if (title.startsWith("[REJECT]")) {
            return new MarketResult.Rejected("CATEGORY_INVALID", "카테고리를 확인할 수 없습니다", false);
        }
        if (title.startsWith("[NOID]")) {
            return new MarketResult.Accepted("   ");
        }
        return new MarketResult.Accepted(marketCode.toUpperCase() + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static String title(Map<String, Object> payload) {
        Object name = payload.getOrDefault("name", payload.get("sellerProductName"));
        return name == null ? "" : name.toString();
    }
}
