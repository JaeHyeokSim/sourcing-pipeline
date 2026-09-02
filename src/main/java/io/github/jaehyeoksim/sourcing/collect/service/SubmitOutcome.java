package io.github.jaehyeoksim.sourcing.collect.service;

import io.github.jaehyeoksim.sourcing.catalog.domain.Product;
import io.github.jaehyeoksim.sourcing.collect.domain.CollectJob;

/**
 * 결과 제출의 두 가지 끝.
 *
 * <p>정규화 실패를 예외로 던지면 같은 트랜잭션에서 기록한 FAILED 상태까지 함께 롤백되어
 * 작업이 RUNNING 으로 남는다. 그래서 "실패했다"는 사실은 예외가 아니라 반환값으로 다룬다.
 */
public sealed interface SubmitOutcome {

    record Succeeded(Product product) implements SubmitOutcome {
    }

    record Rejected(CollectJob job, String reason) implements SubmitOutcome {
    }
}
