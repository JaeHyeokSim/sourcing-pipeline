package io.github.jaehyeoksim.sourcing.listing.market;

/**
 * 마켓이 상품 등록을 받아주는 조건. 마켓마다 다르고, 대부분 문서에만 있다가 전송 실패로 알게 된다.
 *
 * <p>이 값들을 어댑터가 선언하게 해서 <b>보내기 전에</b> 걸러낸다.
 * 마켓 API 를 왕복한 뒤 거절당하면 재시도 큐에 남아 계속 재전송되지만,
 * 규칙 위반은 몇 번을 보내도 결과가 같다.
 *
 * @param maxTitleLength   상품명 최대 길이
 * @param maxOptionCount   옵션 최대 개수
 * @param requireMainImage 대표 이미지 필수 여부
 * @param minSellingPrice  최소 판매가(원)
 * @param bannedWords      상품명에 쓸 수 없는 표현
 */
public record MarketRules(
        int maxTitleLength,
        int maxOptionCount,
        boolean requireMainImage,
        long minSellingPrice,
        java.util.List<String> bannedWords) {
}
