package io.github.jaehyeoksim.sourcing.listing.service;

import io.github.jaehyeoksim.sourcing.listing.correction.Correction;
import io.github.jaehyeoksim.sourcing.listing.correction.CorrectionChain;
import io.github.jaehyeoksim.sourcing.listing.market.MarketClient;
import io.github.jaehyeoksim.sourcing.listing.market.MarketResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 등록 큐를 소비한다. 마켓 호출은 트랜잭션 밖에서 일어난다.
 *
 * <p>디스패처가 하는 일은 순서를 잡는 것뿐이고, 판단은 모두 {@link ListingService} 안에 있다.
 * 스케줄러가 도는 방식(주기·배치 크기)이 바뀌어도 상태 규칙은 그대로 두기 위해서다.
 */
@Component
public class ListingDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ListingDispatcher.class);

    private final ListingService listingService;
    private final MarketClient marketClient;
    private final CorrectionChain correctionChain;

    public ListingDispatcher(ListingService listingService, MarketClient marketClient, CorrectionChain correctionChain) {
        this.listingService = listingService;
        this.marketClient = marketClient;
        this.correctionChain = correctionChain;
    }

    /** 전송 차례가 된 건들을 한 배치 처리한다. */
    public int dispatch() {
        List<Long> ids = listingService.claimDispatchable();
        for (Long id : ids) {
            send(id);
        }
        return ids.size();
    }

    void send(Long listingId) {
        listingService.beginSend(listingId).ifPresent(plan -> {
            Map<String, Object> payload = plan.payload();
            Set<String> used = new HashSet<>();
            List<String> notes = new ArrayList<>();
            MarketResult result;
            try {
                result = marketClient.send(plan.marketCode(), payload);
                // 응답에 고칠 값이 들어 있는 반려는 사람을 거치지 않고 바로 고쳐 보낸다.
                Optional<Correction> correction;
                while ((correction = correctionChain.next(plan.marketCode(), payload, result, used)).isPresent()) {
                    Correction c = correction.get();
                    used.add(c.key());
                    notes.add(c.describe());
                    log.info("등록 {} 반려 보정 후 재전송 ({})", listingId, c.describe());
                    payload = c.payload();
                    result = marketClient.send(plan.marketCode(), payload);
                }
            } catch (RuntimeException e) {
                // 마켓 호출이 예외로 끝나도 등록 건이 SENDING 에 남으면 안 된다.
                log.warn("등록 {} 전송 중 예외: {}", listingId, e.toString());
                listingService.recordTransportError(listingId, e.getMessage() == null ? e.toString() : e.getMessage());
                return;
            }
            // 해시는 보정 전 원본 기준이다. 보정은 매 전송마다 같은 결과로 다시 일어나므로,
            // 원본이 그대로면 "변경 없음"으로 보는 게 맞다.
            listingService.completeSend(listingId, plan.payloadHash(), result, notes);
        });
    }
}
