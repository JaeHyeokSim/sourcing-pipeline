package io.github.jaehyeoksim.sourcing.collect.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CollectJobTest {

    private static final Duration LEASE = Duration.ofMinutes(3);
    private static final Duration BASE_DELAY = Duration.ofSeconds(30);

    private CollectJob newJob() {
        return CollectJob.enqueue("taobao", "12345", "https://item.taobao.com/item.htm?id=12345", 3);
    }

    @Test
    @DisplayName("등록 직후에는 즉시 클레임 가능한 PENDING 이다")
    void enqueued() {
        CollectJob job = newJob();

        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getAttempt()).isZero();
        assertThat(job.getNextRunAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("클레임하면 RUNNING 이 되고 시도 횟수와 lease 가 올라간다")
    void claim() {
        CollectJob job = newJob();

        job.claim("worker-1", LEASE);

        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.getWorkerId()).isEqualTo("worker-1");
        assertThat(job.getAttempt()).isEqualTo(1);
        assertThat(job.getLeasedUntil()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("재시도 여지가 있으면 지수 백오프로 큐에 되돌린다")
    void failWithBackoff() {
        CollectJob job = newJob();

        job.claim("worker-1", LEASE);
        boolean retrying = job.fail("타임아웃", BASE_DELAY);

        assertThat(retrying).isTrue();
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getWorkerId()).isNull();
        // 1회차 실패 → 30초 뒤 재시도
        assertThat(job.getNextRunAt()).isBetween(Instant.now().plusSeconds(25), Instant.now().plusSeconds(35));
    }

    @Test
    @DisplayName("백오프 간격은 시도할수록 2배로 늘어난다")
    void backoffGrows() {
        CollectJob job = newJob();

        job.claim("w", LEASE);
        job.fail("1차", BASE_DELAY);
        Instant afterFirst = job.getNextRunAt();

        job.claim("w", LEASE);
        job.fail("2차", BASE_DELAY);
        Instant afterSecond = job.getNextRunAt();

        // 30초 → 60초
        assertThat(Duration.between(afterFirst, afterSecond)).isGreaterThan(Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("시도 상한에 닿으면 FAILED 로 확정되고 더는 재시도하지 않는다")
    void failFinally() {
        CollectJob job = newJob();

        for (int i = 0; i < 3; i++) {
            job.claim("w", LEASE);
            job.fail("실패 " + i, BASE_DELAY);
        }

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.isRetryable()).isFalse();
        assertThat(job.getLastError()).isEqualTo("실패 2");
    }

    @Test
    @DisplayName("정규화 실패 같은 영구 실패는 시도 횟수와 무관하게 즉시 확정한다")
    void failPermanently() {
        CollectJob job = newJob();

        job.claim("w", LEASE);
        job.failPermanently("정규화 실패: title 이 비어 있습니다");

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getAttempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("lease 가 만료된 작업은 회수되어 다시 큐로 돌아간다")
    void reclaim() {
        CollectJob job = newJob();

        job.claim("dead-worker", LEASE);
        job.reclaim(BASE_DELAY);

        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getLeasedUntil()).isNull();
        assertThat(job.getLastError()).contains("lease 만료");
    }
}
