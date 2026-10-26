package io.github.jaehyeoksim.sourcing.listing.correction;

import io.github.jaehyeoksim.sourcing.listing.market.MarketResult;
import java.util.Map;
import java.util.Optional;

/**
 * 마켓 반려 메시지 하나를 보고 페이로드를 고쳐 다시 보낼 수 있는지 판단하는 규칙.
 *
 * <p>전송 전 규칙 검사({@code ListingValidator})로는 잡을 수 없는 실패가 있다.
 * 반품비 상한처럼 <b>카테고리·계정마다 값이 다르고 응답을 받아봐야 아는</b> 조건이다.
 * 이런 실패는 응답에 고칠 값이 들어 있는 경우가 많아, 사람이 확인하지 않아도 재전송으로 끝낼 수 있다.
 *
 * <p>구현 규칙:
 * <ul>
 *   <li>반려 문구 판정은 구현체 한 곳에만 둔다. 마켓은 같은 반려의 문구를 예고 없이 바꾼다.</li>
 *   <li>입력 페이로드를 바꾸지 않고 고친 사본을 돌려준다.</li>
 *   <li>고쳐도 결과가 같을 것이 확실하면(이미 보정된 값 등) 빈 값을 돌려준다.</li>
 *   <li><b>상품명은 고치지 않는다.</b> 셀러가 정한 노출 문구를 자동으로 바꾸면 되돌릴 근거가 남지 않는다.</li>
 * </ul>
 */
public interface PayloadCorrector {

    /** 이력에 남길 규칙 이름 */
    String key();

    boolean supports(String marketCode);

    /**
     * @return 고친 페이로드 사본과 보정 내용. 해당 없음이면 빈 값
     */
    Optional<Correction> correct(Map<String, Object> payload, MarketResult.Rejected rejection);
}
