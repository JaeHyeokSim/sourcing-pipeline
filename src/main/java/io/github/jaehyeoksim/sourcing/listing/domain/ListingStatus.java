package io.github.jaehyeoksim.sourcing.listing.domain;

public enum ListingStatus {
    /** 전송 대기 */
    QUEUED,
    /** 마켓에 전송 중 (응답 대기) */
    SENDING,
    /** 마켓에 등록 완료. 마켓 상품ID 보유 */
    LISTED,
    /** 재시도 상한까지 실패했거나, 재시도 의미가 없는 실패로 확정 */
    FAILED
}
