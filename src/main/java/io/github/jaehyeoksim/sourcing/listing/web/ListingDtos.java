package io.github.jaehyeoksim.sourcing.listing.web;

import io.github.jaehyeoksim.sourcing.listing.domain.ListingStatus;
import io.github.jaehyeoksim.sourcing.listing.domain.MarketListing;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/** 오픈마켓 등록 API 의 요청/응답 형태 모음. */
public final class ListingDtos {

    private ListingDtos() {
    }

    public record ListingRequest(
            @NotNull Long productId,
            @NotEmpty List<String> markets,
            boolean force) {
    }

    public record ListingResponse(
            Long id,
            Long productId,
            String marketCode,
            ListingStatus status,
            String marketProductId,
            int attempt,
            int maxAttempts,
            Instant nextRunAt,
            String lastErrorCode,
            String lastError,
            Instant listedAt) {

        public static ListingResponse from(MarketListing listing) {
            return new ListingResponse(
                    listing.getId(),
                    listing.getProductId(),
                    listing.getMarketCode(),
                    listing.getStatus(),
                    listing.getMarketProductId(),
                    listing.getAttempt(),
                    listing.getMaxAttempts(),
                    listing.getNextRunAt(),
                    listing.getLastErrorCode(),
                    listing.getLastError(),
                    listing.getListedAt());
        }
    }
}
