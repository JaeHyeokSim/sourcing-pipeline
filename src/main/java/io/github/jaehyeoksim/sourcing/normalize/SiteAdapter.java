package io.github.jaehyeoksim.sourcing.normalize;

import tools.jackson.databind.JsonNode;

/**
 * 사이트별 정규화 규칙. 새 사이트 지원 = 이 인터페이스 구현체 추가 (기존 코드 수정 없음).
 */
public interface SiteAdapter {

    /** 이 어댑터가 담당하는 사이트 코드 */
    String siteCode();

    /** 워커가 보낸 원본 JSON 을 공통 형태로 변환 */
    NormalizedProduct normalize(JsonNode raw, String sourceUrl);
}
