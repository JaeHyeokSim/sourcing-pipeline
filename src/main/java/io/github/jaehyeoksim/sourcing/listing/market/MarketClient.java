package io.github.jaehyeoksim.sourcing.listing.market;

import java.util.Map;

/**
 * 마켓에 실제로 요청을 보내는 지점. 어댑터(무엇을 보낼지)와 전송(어떻게 보낼지)을 갈라놓는다.
 *
 * <p>이 경계 덕분에 마켓 계정 없이도 큐·재시도·상태 추적을 끝까지 검증할 수 있다.
 */
public interface MarketClient {

    MarketResult send(String marketCode, Map<String, Object> payload);
}
