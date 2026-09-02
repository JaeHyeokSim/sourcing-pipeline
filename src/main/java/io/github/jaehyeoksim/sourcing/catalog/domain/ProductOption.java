package io.github.jaehyeoksim.sourcing.catalog.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "product_option", indexes = @Index(name = "ix_option_product", columnList = "product_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** 옵션 축 이름 (색상, 사이즈 ...) */
    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 200)
    private String value;

    @Column(name = "extra_price", precision = 15, scale = 2)
    private BigDecimal extraPrice;

    @Column(name = "stock_quantity")
    private Integer stockQuantity;

    @Column(name = "image_url", length = 1024)
    private String imageUrl;

    public ProductOption(String name, String value, BigDecimal extraPrice, Integer stockQuantity, String imageUrl) {
        this.name = name;
        this.value = value;
        this.extraPrice = extraPrice;
        this.stockQuantity = stockQuantity;
        this.imageUrl = imageUrl;
    }

    void attachTo(Product product) {
        this.product = product;
    }
}
