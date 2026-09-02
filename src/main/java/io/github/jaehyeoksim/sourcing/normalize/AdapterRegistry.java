package io.github.jaehyeoksim.sourcing.normalize;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 사이트 코드 → 어댑터 조회. 등록되지 않은 사이트는 범용 어댑터로 흘린다.
 */
@Component
public class AdapterRegistry {

    private final Map<String, SiteAdapter> adapters;
    private final SiteAdapter fallback;

    public AdapterRegistry(List<SiteAdapter> all, GenericAdapter fallback) {
        this.adapters = all.stream()
                .filter(a -> !(a instanceof GenericAdapter))
                .collect(Collectors.toMap(SiteAdapter::siteCode, Function.identity()));
        this.fallback = fallback;
    }

    public SiteAdapter resolve(String siteCode) {
        return adapters.getOrDefault(siteCode, fallback);
    }

    public List<String> supportedSites() {
        return adapters.keySet().stream().sorted().toList();
    }
}
