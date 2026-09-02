package io.github.jaehyeoksim.sourcing.collect.domain;

public enum JobStatus {
    /** 대기 중. 워커가 클레임할 수 있는 상태 */
    PENDING,
    /** 워커가 점유해 수집 진행 중 */
    RUNNING,
    /** 수집 성공 후 정규화까지 완료 */
    SUCCEEDED,
    /** 재시도 상한까지 실패 */
    FAILED,
    /** 사용자가 취소 */
    CANCELED
}
