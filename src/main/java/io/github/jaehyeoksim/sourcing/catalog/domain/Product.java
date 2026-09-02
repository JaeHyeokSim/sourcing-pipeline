package io.github.jaehyeoksim.sourcing.catalog.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 정규화된 상품. 사이트별 원본 구조 차이를 여기서 흡수해 하위(마켓 연동)가 한 가지 형태만 다루게 한다.
 */
@Entity
@Table(
        name = "product",
        uniqueConstraints = @UniqueConstraint(name = "uk_product_source", columnNames = {"site_code", "external_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_code", nullable = false, length = 32)
    private String siteCode;

    @Column(name = "external_id", nullable = false, length = 128)
    private String externalId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "price_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal priceAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "main_image_url", length = 1024)
    private String mainImageUrl;

    @Column(name = "source_url", nullable = false, length = 1024)
    private String sourceUrl;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductOption> options = new ArrayList<>();

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Product(String siteCode, String externalId, String title, BigDecimal priceAmount,
            String currency, String mainImageUrl, String sourceUrl) {
        this.siteCode = siteCode;
        this.externalId = externalId;
        this.title = title;
        this.priceAmount = priceAmount;
        this.currency = currency;
        this.mainImageUrl = mainImageUrl;
        this.sourceUrl = sourceUrl;
        this.collectedAt = Instant.now();
        this.updatedAt = this.collectedAt;
    }

    /** 같은 상품을 다시 수집했을 때 값만 갱신한다 (재수집 멱등성). */
    public void refresh(String title, BigDecimal priceAmount, String currency, String mainImageUrl) {
        this.title = title;
        this.priceAmount = priceAmount;
        this.currency = currency;
        this.mainImageUrl = mainImageUrl;
        this.updatedAt = Instant.now();
    }

    public void replaceOptions(List<ProductOption> newOptions) {
        this.options.clear();
        for (ProductOption o : newOptions) {
            o.attachTo(this);
            this.options.add(o);
        }
        this.updatedAt = Instant.now();
    }
}
