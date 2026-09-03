package io.github.jaehyeoksim.sourcing.listing.service;

import io.github.jaehyeoksim.sourcing.listing.market.MarketClient;
import io.github.jaehyeoksim.sourcing.listing.market.MarketResult;
import java.util.List;
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

    public ListingDispatcher(ListingService listingService, MarketClient marketClient) {
        this.listingService = listingService;
        this.marketClient = marketClient;
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
            MarketResult result;
            try {
                result = marketClient.send(plan.marketCode(), plan.payload());
            } catch (RuntimeException e) {
                // 마켓 호출이 예외로 끝나도 등록 건이 SENDING 에 남으면 안 된다.
                log.warn("등록 {} 전송 중 예외: {}", listingId, e.toString());
                listingService.recordTransportError(listingId, e.getMessage() == null ? e.toString() : e.getMessage());
                return;
            }
            listingService.completeSend(listingId, plan.payloadHash(), result);
        });
    }
}
