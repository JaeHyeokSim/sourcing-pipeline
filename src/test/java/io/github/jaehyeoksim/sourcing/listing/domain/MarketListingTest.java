package io.github.jaehyeoksim.sourcing.listing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketListingTest {

    private static MarketListing queued() {
        return MarketListing.queue(1L, "smartstore", 3);
    }

    @Test
    @DisplayName("일시 실패는 재시도할수록 대기 간격이 두 배로 늘어난다")
    void backoffDoubles() {
        MarketListing listing = queued();

        listing.markSending();
        Instant before = Instant.now();
        assertThat(listing.fail("MARKET_BUSY", "혼잡", Duration.ofSeconds(60))).isTrue();
        // 1회차: 60초
        assertThat(listing.getNextRunAt()).isBetween(before.plusSeconds(55), before.plusSeconds(65));

        listing.markSending();
        before = Instant.now();
        assertThat(listing.fail("MARKET_BUSY", "혼잡", Duration.ofSeconds(60))).isTrue();
        // 2회차: 120초
        assertThat(listing.getNextRunAt()).isBetween(before.plusSeconds(115), before.plusSeconds(125));
    }

    @Test
    @DisplayName("시도 상한에 닿으면 재시도하지 않고 FAILED 로 확정한다")
    void stopsAtMaxAttempts() {
        MarketListing listing = queued();

        for (int i = 0; i < 2; i++) {
            listing.markSending();
            assertThat(listing.fail("MARKET_BUSY", "혼잡", Duration.ofSeconds(1))).isTrue();
        }
        listing.markSending();

        assertThat(listing.fail("MARKET_BUSY", "혼잡", Duration.ofSeconds(1))).isFalse();
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.FAILED);
        assertThat(listing.getAttempt()).isEqualTo(3);
    }

    @Test
    @DisplayName("규칙 위반은 시도 횟수가 남아 있어도 바로 확정 실패한다")
    void permanentFailureIgnoresRemainingAttempts() {
        MarketListing listing = queued();
        listing.markSending();

        listing.failPermanently("RULE_VIOLATION", "상품명이 100자를 넘습니다");

        assertThat(listing.getStatus()).isEqualTo(ListingStatus.FAILED);
        assertThat(listing.getAttempt()).isEqualTo(1);
        assertThat(listing.getLastErrorCode()).isEqualTo("RULE_VIOLATION");
    }

    @Test
    @DisplayName("재전송 요청으로 큐에 돌아가도, 이미 올라간 사실과 내용 해시는 남는다")
    void requeueKeepsListedFact() {
        MarketListing listing = queued();
        listing.markSending();
        listing.succeed("SMARTSTORE-1234", "hash-A");

        listing.requeue();

        assertThat(listing.getStatus()).isEqualTo(ListingStatus.QUEUED);
        assertThat(listing.getAttempt()).isZero();
        // 상태는 QUEUED 지만 마켓에는 올라가 있다. 같은 내용이면 다시 보내지 않아야 한다.
        assertThat(listing.isUnchanged("hash-A")).isTrue();
        assertThat(listing.isUnchanged("hash-B")).isFalse();
    }

    @Test
    @DisplayName("한 번도 올라간 적 없으면 해시가 같아도 전송을 건너뛰지 않는다")
    void neverListedIsAlwaysSent() {
        MarketListing listing = queued();

        assertThat(listing.isUnchanged("hash-A")).isFalse();
    }

    @Test
    @DisplayName("응답 없이 남은 전송은 회수되어 다시 큐로 돌아간다")
    void reclaimReturnsToQueue() {
        MarketListing listing = queued();
        listing.markSending();

        listing.reclaim(Duration.ofSeconds(60));

        assertThat(listing.getStatus()).isEqualTo(ListingStatus.QUEUED);
        assertThat(listing.getSentAt()).isNull();
        assertThat(listing.getLastErrorCode()).isEqualTo("SEND_TIMEOUT");
    }
}
