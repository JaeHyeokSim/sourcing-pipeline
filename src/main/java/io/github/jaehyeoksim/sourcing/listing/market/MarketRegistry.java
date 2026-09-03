package io.github.jaehyeoksim.sourcing.listing.market;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 마켓 코드 → 어댑터 조회.
 *
 * <p>수집 쪽 {@code AdapterRegistry} 와 달리 폴백을 두지 않는다.
 * 모르는 사이트는 범용 규칙으로 읽어볼 수 있지만, 모르는 마켓에 상품을 올려볼 수는 없다.
 */
@Component
public class MarketRegistry {

    private final Map<String, MarketAdapter> adapters;

    public MarketRegistry(List<MarketAdapter> all) {
        this.adapters = all.stream().collect(Collectors.toMap(MarketAdapter::marketCode, Function.identity()));
    }

    public Optional<MarketAdapter> find(String marketCode) {
        return Optional.ofNullable(adapters.get(marketCode));
    }

    public List<String> supportedMarkets() {
        return adapters.keySet().stream().sorted().toList();
    }
}
