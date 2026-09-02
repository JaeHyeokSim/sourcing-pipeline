package io.github.jaehyeoksim.sourcing.common;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 수집기 운영 파라미터. 동시성/타임아웃/재시도는 코드가 아니라 설정으로 조절한다.
 */
@ConfigurationProperties(prefix = "collector")
public record CollectorProperties(
        int maxConcurrentJobs,
        Duration claimTimeout,
        int maxAttempts,
        Duration retryBaseDelay) {
}
