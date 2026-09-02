package io.github.jaehyeoksim.sourcing.collect.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 브라우저 탭이 닫히거나 확장이 죽으면 작업이 RUNNING 인 채로 남는다.
 * 그대로 두면 동시 실행 슬롯을 영원히 잡아먹으므로 주기적으로 회수한다.
 */
@Component
public class LeaseReclaimScheduler {

    private final CollectJobService collectJobService;

    public LeaseReclaimScheduler(CollectJobService collectJobService) {
        this.collectJobService = collectJobService;
    }

    @Scheduled(fixedDelayString = "PT30S")
    public void reclaim() {
        collectJobService.reclaimExpired();
    }
}
