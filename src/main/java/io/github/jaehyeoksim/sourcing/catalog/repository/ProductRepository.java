package io.github.jaehyeoksim.sourcing.catalog.repository;

import io.github.jaehyeoksim.sourcing.catalog.domain.Product;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySiteCodeAndExternalId(String siteCode, String externalId);

    Page<Product> findBySiteCode(String siteCode, Pageable pageable);
}
