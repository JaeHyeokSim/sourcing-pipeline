package io.github.jaehyeoksim.sourcing.collect.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jaehyeoksim.sourcing.catalog.domain.Product;
import io.github.jaehyeoksim.sourcing.catalog.repository.ProductRepository;
import io.github.jaehyeoksim.sourcing.collect.domain.CollectJob;
import io.github.jaehyeoksim.sourcing.collect.domain.JobStatus;
import io.github.jaehyeoksim.sourcing.collect.repository.CollectJobRepository;
import io.github.jaehyeoksim.sourcing.normalize.NormalizationException;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:collect-test;DB_CLOSE_DELAY=-1",
    "collector.max-concurrent-jobs=2"
})
class CollectJobServiceTest {

    @Autowired
    private CollectJobService service;

    @Autowired
    private CollectJobRepository jobRepository;

    @Autowired
    private ProductRepository productRepository;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void clean() {
        jobRepository.deleteAll();
        productRepository.deleteAll();
    }

    private CollectJob enqueue(String externalId) {
        return service.enqueue("taobao", externalId, "https://item.taobao.com/item.htm?id=" + externalId);
    }

    private JsonNode payload(String itemId, String title, String price) {
        return mapper.readTree("""
                {
                  "goods": { "itemId": "%s", "title": "%s", "mainImage": "https://img/x.jpg" },
                  "skus": [ { "price": "%s", "stock": 5, "props": [ { "name": "색상", "value": "블랙" } ] } ]
                }
                """.formatted(itemId, title, price));
    }

    @Test
    @DisplayName("같은 상품을 두 번 요청해도 작업은 하나만 생긴다")
    void enqueueIsIdempotent() {
        CollectJob first = enqueue("900001");
        CollectJob second = enqueue("900001");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(jobRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 실행 상한에 도달하면 더 이상 클레임을 내주지 않는다")
    void claimRespectsConcurrencyLimit() {
        enqueue("900001");
        enqueue("900002");
        enqueue("900003");

        assertThat(service.claimNext("w1")).isPresent();
        assertThat(service.claimNext("w2")).isPresent();
        // 상한 2 이므로 세 번째 워커는 대기해야 한다
        assertThat(service.claimNext("w3")).isEmpty();
    }

    @Test
    @DisplayName("수집 결과를 제출하면 정규화까지 끝나고 상품이 저장된다")
    void submitResultCreatesProduct() {
        CollectJob job = enqueue("900010");
        service.claimNext("w1");

        Product product = service.submitResult(job.getId(), "w1", payload("900010", "테스트 상품", "12.34"));

        assertThat(product.getTitle()).isEqualTo("테스트 상품");
        assertThat(product.getPriceAmount()).isEqualByComparingTo(new BigDecimal("12.34"));
        assertThat(product.getOptions()).hasSize(1);
        assertThat(jobRepository.findById(job.getId()))
                .get()
                .extracting(CollectJob::getStatus)
                .isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("같은 상품을 다시 수집하면 새로 만들지 않고 값만 갱신한다")
    void resubmitUpdatesInsteadOfDuplicating() {
        CollectJob job = enqueue("900011");
        service.claimNext("w1");
        service.submitResult(job.getId(), "w1", payload("900011", "이전 제목", "10"));

        // 재수집 상황을 만들기 위해 작업을 다시 큐에 올린다
        CollectJob again = jobRepository.findById(job.getId()).orElseThrow();
        again.fail("재수집", java.time.Duration.ZERO);
        service.claimNext("w1");
        service.submitResult(job.getId(), "w1", payload("900011", "새 제목", "20"));

        Optional<Product> product = productRepository.findBySiteCodeAndExternalId("taobao", "900011");
        assertThat(productRepository.count()).isEqualTo(1);
        assertThat(product).get().extracting(Product::getTitle).isEqualTo("새 제목");
    }

    @Test
    @DisplayName("정규화할 수 없는 원본은 재시도하지 않고 바로 FAILED 로 확정한다")
    void normalizationFailureIsPermanent() {
        CollectJob job = enqueue("900020");
        service.claimNext("w1");
        JsonNode broken = mapper.readTree("{\"goods\": {\"title\": \"itemId 없음\"}, \"skus\": []}");

        assertThatThrownBy(() -> service.submitResult(job.getId(), "w1", broken))
                .isInstanceOf(NormalizationException.class);

        assertThat(jobRepository.findById(job.getId()))
                .get()
                .extracting(CollectJob::getStatus)
                .isEqualTo(JobStatus.FAILED);
    }

    @Test
    @DisplayName("점유하지 않은 워커가 결과를 제출하면 거부한다")
    void rejectsResultFromForeignWorker() {
        CollectJob job = enqueue("900030");
        service.claimNext("w1");

        assertThatThrownBy(() -> service.submitResult(job.getId(), "w2", payload("900030", "제목", "10")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("점유한 워커가 아닙니다");
    }

    @Test
    @DisplayName("실패를 보고하면 백오프 후 재시도 대기 상태로 돌아간다")
    void reportFailureSchedulesRetry() {
        CollectJob job = enqueue("900040");
        service.claimNext("w1");

        CollectJob failed = service.reportFailure(job.getId(), "페이지 로드 타임아웃");

        assertThat(failed.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(failed.getAttempt()).isEqualTo(1);
        assertThat(failed.getLastError()).isEqualTo("페이지 로드 타임아웃");
    }
}
