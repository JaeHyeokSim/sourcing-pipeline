package io.github.jaehyeoksim.sourcing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jaehyeoksim.sourcing.catalog.domain.Product;
import io.github.jaehyeoksim.sourcing.catalog.repository.ProductRepository;
import io.github.jaehyeoksim.sourcing.collect.domain.CollectJob;
import io.github.jaehyeoksim.sourcing.collect.domain.JobStatus;
import io.github.jaehyeoksim.sourcing.collect.repository.RawProductRepository;
import io.github.jaehyeoksim.sourcing.collect.service.CollectJobService;
import io.github.jaehyeoksim.sourcing.listing.domain.ListingStatus;
import io.github.jaehyeoksim.sourcing.listing.domain.MarketListing;
import io.github.jaehyeoksim.sourcing.listing.service.ListingDispatcher;
import io.github.jaehyeoksim.sourcing.listing.service.ListingService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 운영 대상인 PostgreSQL 에서 실제로 스키마가 만들어지고 동작하는지 확인한다.
 *
 * <p>개발은 H2 로 하지만 운영은 PostgreSQL 이다. 두 엔진은 타입이 미묘하게 다르고
 * (네이티브 enum, 길이 없는 varchar, 라지 오브젝트), 그 차이는 H2 테스트로는 절대 드러나지 않는다.
 * 그래서 PostgreSQL 마이그레이션도 한 번은 진짜 PostgreSQL 위에서 돌려본다.
 *
 * <p>Docker 대신 임베디드 PostgreSQL 을 쓴다. Testcontainers 가 더 일반적이지만
 * 테스트에 Docker 를 요구하게 되고, 클론해서 {@code ./gradlew test} 만 치면 도는 상태를 우선했다.
 * 임베디드 쪽은 실제 PostgreSQL 바이너리를 받아 띄우므로 검증 대상은 같다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PostgresSchemaTest {

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
    private DataSource dataSource;

    @Autowired
    private CollectJobService collectJobService;

    @Autowired
    private ListingService listingService;

    @Autowired
    private ListingDispatcher dispatcher;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private RawProductRepository rawProductRepository;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("PostgreSQL 마이그레이션이 적용되고, JPA 매핑 검증을 통과한다")
    void migrationAppliesAndValidates() {
        // 컨텍스트가 떴다는 것 자체가 ddl-auto=validate 통과를 뜻한다.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        String version = jdbc.queryForObject(
                "select version from flyway_schema_history order by installed_rank desc limit 1", String.class);
        assertThat(version).isEqualTo("1");

        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public' order by table_name",
                String.class);
        assertThat(tables)
                .contains("collect_job", "raw_product", "product", "product_option", "market_listing");
    }

    @Test
    @DisplayName("H2 에서 갈리던 두 컬럼이 PostgreSQL 에서도 문자열로 만들어진다")
    void vendorSensitiveColumnsAreText() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // @Enumerated(STRING) 을 그대로 두면 H2 는 네이티브 enum 을 만든다
        String status = jdbc.queryForObject(
                """
                select data_type from information_schema.columns
                where table_name = 'collect_job' and column_name = 'status'
                """,
                String.class);
        assertThat(status).isEqualTo("character varying");

        // @Lob 을 그대로 두면 PostgreSQL 은 oid(라지 오브젝트)를 만든다
        String payload = jdbc.queryForObject(
                """
                select data_type from information_schema.columns
                where table_name = 'raw_product' and column_name = 'payload'
                """,
                String.class);
        assertThat(payload).isEqualTo("character varying");
    }

    @Test
    @DisplayName("PostgreSQL 위에서 수집 → 정규화 → 마켓 등록이 끝까지 흐른다")
    void pipelineRunsOnPostgres() {
        CollectJob job = collectJobService.enqueue("taobao", "PG-1", "https://item.taobao.com/item.htm?id=PG-1");
        collectJobService.claimNext("worker-pg");
        collectJobService.submitResult(
                job.getId(),
                "worker-pg",
                mapper.readTree(
                        """
                        {"goods":{"itemId":"PG-1","title":"무선 이어폰","mainImage":"https://img/1.jpg"},
                         "skus":[{"price":"75.50","props":[{"name":"색상","value":"블랙"}]}]}
                        """));

        Product product = productRepository.findBySiteCodeAndExternalId("taobao", "PG-1").orElseThrow();
        assertThat(collectJobService.get(job.getId()).getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        // 원본 보존(텍스트 컬럼)도 읽히는지 함께 본다 — oid 였다면 여기서 드러난다
        assertThat(rawProductRepository.findByJobId(job.getId()).get(0).getPayload()).contains("PG-1");

        MarketListing listing = listingService.request(product.getId(), "smartstore", false);
        dispatcher.dispatch();

        MarketListing after = listingService.get(listing.getId());
        assertThat(after.getStatus()).isEqualTo(ListingStatus.LISTED);
        assertThat(after.getMarketProductId()).isNotBlank();
    }
}
