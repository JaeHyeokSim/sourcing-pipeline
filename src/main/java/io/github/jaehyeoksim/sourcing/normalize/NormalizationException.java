package io.github.jaehyeoksim.sourcing.normalize;

/** 원본이 기대한 형태가 아니라 정규화할 수 없을 때. 재시도해도 소용없는 실패로 취급한다. */
public class NormalizationException extends RuntimeException {

    public NormalizationException(String message) {
        super(message);
    }
}
