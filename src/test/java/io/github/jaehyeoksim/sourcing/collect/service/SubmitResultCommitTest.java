package io.github.jaehyeoksim.sourcing.collect.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jaehyeoksim.sourcing.catalog.repository.ProductRepository;
import io.github.jaehyeoksim.sourcing.collect.domain.CollectJob;
import io.github.jaehyeoksim.sourcing.collect.domain.JobStatus;
import io.github.jaehyeoksim.sourcing.collect.repository.CollectJobRepository;
import io.github.jaehyeoksim.sourcing.collect.repository.RawProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 일부러 {@code @Transactional} 을 붙이지 않은 테스트.
 *
 * <p>테스트가 트랜잭션을 감싸면 서비스의 커밋/롤백 경계가 사라져,
 * "실패를 기록했지만 롤백되어 사라진다" 같은 결함을 잡지 못한다.
 * 실제로 이 프로젝트에서 정규화 실패 시 작업이 RUNNING 으로 남는 문제를 놓쳤던 지점이라
 * 커밋이 실제로 일어나는 조건에서 다시 검증한다.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:commit-test;DB_CLOSE_DELAY=-1",
    "collector.max-concurrent-jobs=3"
})
class SubmitResultCommitTest {

    @Autowired
    private CollectJobService service;

    @Autowired
    private CollectJobRepository jobRepository;

    @Autowired
    private RawProductRepository rawProductRepository;

    @Autowired
    private ProductRepository productRepository;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @AfterEach
    void clean() {
        rawProductRepository.deleteAll();
        productRepository.deleteAll();
        jobRepository.deleteAll();
    }

    @Test
    @DisplayName("정규화 실패가 커밋되어 작업이 RUNNING 으로 남지 않는다")
    void normalizationFailureIsCommitted() {
        CollectJob job = service.enqueue("taobao", "990001", "https://item.taobao.com/item.htm?id=990001");
        service.claimNext("worker-1");
        JsonNode broken = mapper.readTree("{\"goods\": {\"title\": \"itemId 가 없는 원본\"}, \"skus\": []}");

        SubmitOutcome outcome = service.submitResult(job.getId(), "worker-1", broken);

        assertThat(outcome).isInstanceOf(SubmitOutcome.Rejected.class);
        // 트랜잭션이 끝난 뒤 DB 를 다시 읽는다
        CollectJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(reloaded.getWorkerId()).isNull();
        assertThat(reloaded.getLastError()).contains("정규화 실패");
    }

    @Test
    @DisplayName("정규화에 실패해도 원본은 남아 나중에 재처리할 수 있다")
    void rawPayloadSurvivesFailure() {
        CollectJob job = service.enqueue("taobao", "990002", "https://item.taobao.com/item.htm?id=990002");
        service.claimNext("worker-1");
        JsonNode broken = mapper.readTree("{\"goods\": {\"title\": \"itemId 없음\"}, \"skus\": []}");

        service.submitResult(job.getId(), "worker-1", broken);

        assertThat(rawProductRepository.findByJobId(job.getId())).hasSize(1);
    }

    @Test
    @DisplayName("실패한 작업은 동시 실행 슬롯을 놓아준다")
    void failedJobReleasesConcurrencySlot() {
        CollectJob first = service.enqueue("taobao", "990003", "https://item.taobao.com/item.htm?id=990003");
        service.claimNext("worker-1");
        JsonNode broken = mapper.readTree("{\"goods\": {\"title\": \"itemId 없음\"}, \"skus\": []}");
        service.submitResult(first.getId(), "worker-1", broken);

        assertThat(service.stats().running()).isZero();
        assertThat(service.stats().failed()).isEqualTo(1);
    }
}
