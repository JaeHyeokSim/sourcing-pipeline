package io.github.jaehyeoksim.sourcing.collect.repository;

import io.github.jaehyeoksim.sourcing.collect.domain.RawProduct;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawProductRepository extends JpaRepository<RawProduct, Long> {

    List<RawProduct> findByJobId(Long jobId);
}
