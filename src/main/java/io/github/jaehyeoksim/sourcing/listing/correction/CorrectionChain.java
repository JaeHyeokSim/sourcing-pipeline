package io.github.jaehyeoksim.sourcing.listing.correction;

import io.github.jaehyeoksim.sourcing.listing.market.MarketResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 반려 응답에 맞는 보정 규칙을 고른다.
 *
 * <p>한 번의 전송에서 <b>규칙마다 최대 한 번</b>만 적용한다. 반품비를 낮췄더니 이번엔 브랜드로 거절되는 식의
 * 연쇄는 따라가되, 같은 규칙이 같은 실패를 계속 고치려 드는 무한 재전송은 생기지 않는다.
 * 그래서 전송 횟수의 상한은 등록된 규칙 수 + 1 이다.
 *
 * <p>보정은 전송 계층에서만 일어난다. 호출하는 곳(등록·수정·재전송)마다 같은 보정을 따로 두면
 * 한 곳에만 빠져도 그 경로에서만 실패가 계속된다.
 */
@Component
public class CorrectionChain {

    private final List<PayloadCorrector> correctors;

    public CorrectionChain(List<PayloadCorrector> correctors) {
        this.correctors = List.copyOf(correctors);
    }

    /**
     * @param used 이번 전송에서 이미 적용한 규칙 이름
     */
    public Optional<Correction> next(
            String marketCode, Map<String, Object> payload, MarketResult result, Set<String> used) {
        // 일시 실패는 같은 요청을 나중에 다시 보내면 되는 실패라 값을 건드리지 않는다.
        if (!(result instanceof MarketResult.Rejected rejected) || rejected.retryable()) {
            return Optional.empty();
        }
        for (PayloadCorrector corrector : correctors) {
            if (used.contains(corrector.key()) || !corrector.supports(marketCode)) {
                continue;
            }
            Optional<Correction> correction = corrector.correct(payload, rejected);
            if (correction.isPresent()) {
                return correction;
            }
        }
        return Optional.empty();
    }
}
