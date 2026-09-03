package io.github.jaehyeoksim.sourcing.listing.domain;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 1건을 마켓 1곳에 올리는 등록 건. 수집 큐와 같은 원리의 상태 기계다.
 *
 * <pre>
 * QUEUED ──begin──▶ SENDING ──마켓 수용──▶ LISTED
 *    ▲                 │
 *    └──백오프 재시도──┼── 일시 실패(시도 &lt; 상한)
 *                      ├── 일시 실패(시도 = 상한) ──▶ FAILED
 *                      ├── 규칙 위반/응답 이상 ─────▶ FAILED (재시도 안 함)
 *                      └── 응답 없음(전송 타임아웃) ─▶ 회수 후 QUEUED
 * </pre>
 *
 * <p>상품 애그리게잇을 참조로 물지 않고 {@code productId} 값만 들고 있다.
 * 등록 이력은 상품보다 오래 남아야 하고(상품이 지워져도 "무엇을 왜 못 올렸는지"는 남는다),
 * 마켓 전송은 상품 트랜잭션과 생명주기가 다르기 때문이다.
 */
@Entity
@Table(
        name = "market_listing",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_listing_target", columnNames = {"product_id", "market_code"}),
        indexes = {
            @Index(name = "ix_listing_dispatch", columnList = "status, next_run_at"),
            @Index(name = "ix_listing_stuck", columnList = "status, sent_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketListing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    /** 대상 마켓 (smartstore, coupang ...) */
    @Column(name = "market_code", nullable = false, length = 32)
    private String marketCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ListingStatus status;

    /** 마켓이 발급한 상품 식별자. 이 값이 있어야 등록 성공으로 본다. */
    @Column(name = "market_product_id", length = 128)
    private String marketProductId;

    /**
     * 마지막으로 전송한 페이로드의 해시.
     * 내용이 그대로면 다시 보내지 않는다(재전송 멱등). 마켓 API 호출은 비싸고, 중복 등록은 되돌리기 어렵다.
     */
    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    /** 전송을 시작한 시각. 응답이 오지 않은 채 오래 머무는 건을 찾는 데 쓴다. */
    @Column(name = "sent_at")
    private Instant sentAt;

    /** 실패 사유 코드. 화면에서 "왜 실패했는지"를 분류해 보여주기 위한 값 */
    @Column(name = "last_error_code", length = 40)
    private String lastErrorCode;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "listed_at")
    private Instant listedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    private MarketListing(Long productId, String marketCode, int maxAttempts) {
        this.productId = productId;
        this.marketCode = marketCode;
        this.status = ListingStatus.QUEUED;
        this.attempt = 0;
        this.maxAttempts = maxAttempts;
        this.nextRunAt = Instant.now();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static MarketListing queue(Long productId, String marketCode, int maxAttempts) {
        return new MarketListing(productId, marketCode, maxAttempts);
    }

    /** 실패했거나 이미 올라간 건을 다시 큐에 넣는다. 시도 횟수는 초기화한다. */
    public void requeue() {
        this.status = ListingStatus.QUEUED;
        this.attempt = 0;
        this.nextRunAt = Instant.now();
        this.sentAt = null;
        touch();
    }

    public void markSending() {
        this.status = ListingStatus.SENDING;
        this.attempt += 1;
        this.sentAt = Instant.now();
        touch();
    }

    public void succeed(String marketProductId, String payloadHash) {
        this.status = ListingStatus.LISTED;
        this.marketProductId = marketProductId;
        this.payloadHash = payloadHash;
        this.listedAt = Instant.now();
        this.sentAt = null;
        this.lastErrorCode = null;
        this.lastError = null;
        touch();
    }

    /**
     * 일시적 실패. 재시도 여지가 남아 있으면 지수 백오프로 큐에 되돌린다.
     *
     * @return 재시도로 되돌렸으면 true
     */
    public boolean fail(String code, String reason, Duration baseDelay) {
        this.lastErrorCode = code;
        this.lastError = truncate(reason);
        this.sentAt = null;
        if (this.attempt >= this.maxAttempts) {
            this.status = ListingStatus.FAILED;
            touch();
            return false;
        }
        this.status = ListingStatus.QUEUED;
        long multiplier = 1L << Math.max(0, this.attempt - 1);
        this.nextRunAt = Instant.now().plus(baseDelay.multipliedBy(multiplier));
        touch();
        return true;
    }

    /** 다시 보내도 결과가 같은 실패(마켓 규칙 위반 등). 시도 횟수와 무관하게 확정한다. */
    public void failPermanently(String code, String reason) {
        this.lastErrorCode = code;
        this.lastError = truncate(reason);
        this.status = ListingStatus.FAILED;
        this.sentAt = null;
        touch();
    }

    /** 응답 없이 SENDING 에 머문 건을 큐로 되돌린다. */
    public void reclaim(Duration baseDelay) {
        fail("SEND_TIMEOUT", "마켓 응답 없음 (전송 타임아웃)", baseDelay);
    }

    /**
     * 이미 마켓에 올라가 있고 보낼 내용도 직전과 같은지.
     *
     * <p>상태가 아니라 <b>마켓 상품ID 보유 여부</b>로 판단한다. 재전송 요청이 들어오면 상태는
     * QUEUED 로 돌아가지만, 그렇다고 마켓에 올라가 있다는 사실이 없어지는 것은 아니다.
     */
    public boolean isUnchanged(String newPayloadHash) {
        return this.marketProductId != null
                && this.payloadHash != null
                && this.payloadHash.equals(newPayloadHash);
    }

    /** 보낼 내용이 그대로라 전송을 건너뛴 경우. 등록 상태로 되돌려 큐에 남지 않게 한다. */
    public void keepListed() {
        this.status = ListingStatus.LISTED;
        this.sentAt = null;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
