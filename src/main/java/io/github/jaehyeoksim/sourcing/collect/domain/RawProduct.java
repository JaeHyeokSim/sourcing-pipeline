package io.github.jaehyeoksim.sourcing.collect.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 워커가 보내온 원본 페이로드. 정규화 로직이 바뀌어도 재처리할 수 있도록 원본을 그대로 남긴다.
 */
@Entity
@Table(name = "raw_product", indexes = @Index(name = "ix_raw_job", columnList = "job_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "site_code", nullable = false, length = 32)
    private String siteCode;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    public RawProduct(Long jobId, String siteCode, String payload) {
        this.jobId = jobId;
        this.siteCode = siteCode;
        this.payload = payload;
        this.collectedAt = Instant.now();
    }
}
