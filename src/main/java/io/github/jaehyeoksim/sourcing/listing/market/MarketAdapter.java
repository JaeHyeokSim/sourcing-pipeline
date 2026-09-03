package io.github.jaehyeoksim.sourcing.listing.market;

import io.github.jaehyeoksim.sourcing.catalog.domain.Product;
import java.util.Map;

/**
 * 마켓별 연동 규칙. 새 마켓 지원 = 이 인터페이스 구현체 추가 (기존 코드 수정 없음).
 *
 * <p>어댑터는 <b>무엇을 어떤 이름으로 보내는가</b>만 안다.
 * 언제 보낼지(큐), 실패하면 어떻게 할지(재시도), 얼마에 올릴지(판매가 계산)는 전부 바깥에 있다.
 */
public interface MarketAdapter {

    /** 이 어댑터가 담당하는 마켓 코드 */
    String marketCode();

    /** 전송 전에 검사할 등록 조건 */
    MarketRules rules();

    /**
     * 공통 상품을 마켓 요청 형태로 바꾼다.
     *
     * @param sellingPrice 원화 판매가 (환율·마진 적용 결과)
     */
    Map<String, Object> toPayload(Product product, long sellingPrice);
}
