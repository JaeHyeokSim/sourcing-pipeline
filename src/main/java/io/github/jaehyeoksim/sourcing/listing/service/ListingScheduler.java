package io.github.jaehyeoksim.sourcing.listing.service;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 등록 큐를 주기적으로 돌린다.
 *
 * <p>주기 실행만 담당하고 판단은 하지 않는다. 이렇게 떼어두면 테스트에서
 * 스케줄러를 끄고 {@link ListingDispatcher} 를 직접 한 번씩 돌려 결과를 확인할 수 있다.
 * 배경 스케줄러가 함께 돌면 같은 건을 누가 처리했는지에 따라 테스트가 흔들린다.
 */
@Component
@Profile("!test")
public class ListingScheduler {

    private final ListingDispatcher dispatcher;
    private final ListingService listingService;

    public ListingScheduler(ListingDispatcher dispatcher, ListingService listingService) {
        this.dispatcher = dispatcher;
        this.listingService = listingService;
    }

    @Scheduled(fixedDelayString = "PT5S")
    public void dispatch() {
        dispatcher.dispatch();
    }

    /** 응답 없이 남은 전송을 회수한다. 수집 쪽 lease 회수와 같은 역할이다. */
    @Scheduled(fixedDelayString = "PT30S")
    public void reclaim() {
        listingService.reclaimStuck();
    }
}
