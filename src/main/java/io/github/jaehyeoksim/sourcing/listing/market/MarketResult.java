package io.github.jaehyeoksim.sourcing.listing.market;

/**
 * 마켓 전송의 끝. 성공/실패를 예외가 아니라 값으로 다룬다.
 *
 * <p>실패를 예외로 던지면 호출 쪽에서 "재시도해야 하는 실패"와 "다시 보내도 소용없는 실패"를
 * 메시지 문자열로 구분하게 된다. 그 판단은 마켓 응답을 읽은 쪽이 내려야 한다.
 */
public sealed interface MarketResult {

    /** 마켓이 상품을 받아들였다. {@code marketProductId} 가 비어 있으면 성공으로 보지 않는다. */
    record Accepted(String marketProductId) implements MarketResult {
    }

    /**
     * 마켓이 거절했다.
     *
     * @param retryable 같은 요청을 다시 보내면 통과할 여지가 있는 실패인지 (혼잡·타임아웃 등)
     */
    record Rejected(String code, String message, boolean retryable) implements MarketResult {
    }
}
