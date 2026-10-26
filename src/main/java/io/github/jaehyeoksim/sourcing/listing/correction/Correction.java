package io.github.jaehyeoksim.sourcing.listing.correction;

import java.util.Map;

/**
 * 보정 결과 한 건.
 *
 * @param key     적용한 규칙 이름
 * @param payload 고친 페이로드 사본
 * @param note    무엇을 어떻게 바꿨는지 (예: {@code returnCharge 5000→3000})
 */
public record Correction(String key, Map<String, Object> payload, String note) {

    public String describe() {
        return key + ": " + note;
    }
}
