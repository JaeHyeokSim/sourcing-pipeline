package io.github.jaehyeoksim.sourcing.listing.service;

import java.util.Map;

/**
 * 전송 준비가 끝난 한 건. 트랜잭션 안에서 만들어져, 트랜잭션 밖(마켓 호출)으로 넘어간다.
 *
 * <p>엔티티가 아니라 값으로 넘기는 이유: 마켓 호출은 수 초가 걸릴 수 있는데,
 * 그동안 DB 트랜잭션과 커넥션을 붙잡고 있으면 전송이 느려질수록 DB 가 먼저 막힌다.
 */
public record SendPlan(Long listingId, String marketCode, Map<String, Object> payload, String payloadHash) {
}
