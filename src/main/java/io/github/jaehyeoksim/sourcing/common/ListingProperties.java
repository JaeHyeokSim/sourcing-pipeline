package io.github.jaehyeoksim.sourcing.common;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 오픈마켓 연동 운영 파라미터.
 *
 * <p>환율·마진처럼 자주 바뀌는 값을 코드에 박아두면 배포 없이는 못 고친다.
 * 판매가 계산 근거를 설정 한 곳에 모아 "그때 얼마로 올렸는가"를 나중에 설명할 수 있게 한다.
 */
@ConfigurationProperties(prefix = "listing")
public record ListingProperties(
        /** 전송 재시도 상한 */
        int maxAttempts,
        /** 지수 백오프 기준 간격 */
        Duration retryBaseDelay,
        /** 디스패처가 한 번에 집어가는 건수 */
        int batchSize,
        /** 이 시간 안에 마켓 응답이 없으면 전송이 끊긴 것으로 보고 회수한다 */
        Duration sendTimeout,
        /** 원화 환산 기준 환율 (원/외화 1단위) */
        BigDecimal exchangeRate,
        /** 판매가 마진율 (0.30 = 30%) */
        BigDecimal marginRate,
        /** 판매가 절상 단위 (100 = 백원 단위) */
        int roundTo) {
}
