package io.github.jaehyeoksim.sourcing.collect.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jaehyeoksim.sourcing.collect.domain.JobStatus;
import io.github.jaehyeoksim.sourcing.collect.repository.CollectJobRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * 워커 여러 개가 동시에 클레임할 때도 동시 실행 상한이 지켜지는지 확인한다.
 *
 * <p>상한 판단은 "RUNNING 개수를 센다 → 후보를 잠근다 → RUNNING 으로 바꾼다" 순서다.
 * 개수를 세는 시점에 아무것도 잠그지 않으면, 동시에 들어온 요청이 모두 같은 개수를 보고
 * 통과해 상한을 넘긴다(check-then-act). 후보 행 잠금은 "같은 작업 중복 점유"만 막을 뿐
 * 상한은 막지 못한다. 순차 호출 테스트로는 드러나지 않아 실제 동시 요청으로 검증한다.
 *
 * <p>잠금 동작이 엔진마다 달라 운영 대상인 PostgreSQL 위에서 돌린다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "collector.max-concurrent-jobs=3")
class ClaimConcurrencyTest {

    private static final EmbeddedPostgres POSTGRES = start();

    private static EmbeddedPostgres start() {
        try {
            return EmbeddedPostgres.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private CollectJobService service;

    @Autowired
    private CollectJobRepository jobRepository;

    @Test
    @DisplayName("동시 클레임 요청이 몰려도 RUNNING 은 상한을 넘지 않는다")
    void concurrentClaimsRespectLimit() throws Exception {
        int workers = 10;
        for (int i = 0; i < workers; i++) {
            service.enqueue("taobao", "CC-" + i, "https://item.taobao.com/item.htm?id=CC-" + i);
        }

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                String workerId = "w" + i;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return service.claimNext(workerId).isPresent();
                }));
            }
            ready.await();
            go.countDown();

            long claimed = 0;
            for (Future<Boolean> r : results) {
                if (r.get(30, TimeUnit.SECONDS)) {
                    claimed++;
                }
            }

            assertThat(claimed).isEqualTo(3);
            assertThat(jobRepository.countByStatus(JobStatus.RUNNING)).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
    }
}
