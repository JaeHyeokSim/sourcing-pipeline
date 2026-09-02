package io.github.jaehyeoksim.sourcing.catalog.service;

import io.github.jaehyeoksim.sourcing.catalog.domain.Product;
import io.github.jaehyeoksim.sourcing.catalog.domain.ProductOption;
import io.github.jaehyeoksim.sourcing.catalog.repository.ProductRepository;
import io.github.jaehyeoksim.sourcing.normalize.NormalizedProduct;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정규화 결과를 카탈로그에 반영한다.
 * 같은 상품을 몇 번 수집하든 결과가 같도록 (siteCode, externalId) 기준 upsert 로 처리한다.
 */
@Service
public class ProductUpsertService {

    private final ProductRepository productRepository;

    public ProductUpsertService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public Product upsert(NormalizedProduct source) {
        Product product = productRepository
                .findBySiteCodeAndExternalId(source.siteCode(), source.externalId())
                .map(existing -> {
                    existing.refresh(source.title(), source.priceAmount(), source.currency(), source.mainImageUrl());
                    return existing;
                })
                .orElseGet(() -> productRepository.save(new Product(
                        source.siteCode(),
                        source.externalId(),
                        source.title(),
                        source.priceAmount(),
                        source.currency(),
                        source.mainImageUrl(),
                        source.sourceUrl())));

        product.replaceOptions(toOptions(source));
        return product;
    }

    private static List<ProductOption> toOptions(NormalizedProduct source) {
        return source.options().stream()
                .map(o -> new ProductOption(o.name(), o.value(), o.extraPrice(), o.stockQuantity(), o.imageUrl()))
                .toList();
    }
}
