package io.github.jaehyeoksim.sourcing.common;

import io.github.jaehyeoksim.sourcing.collect.service.JobNotFoundException;
import io.github.jaehyeoksim.sourcing.listing.service.ListingNotFoundException;
import io.github.jaehyeoksim.sourcing.normalize.NormalizationException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** API 오류를 일정한 형태로 돌려준다. 워커가 상태 코드만 보고 재시도 여부를 판단할 수 있게 한다. */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ApiError(Instant timestamp, int status, String code, String message) {
    }

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<ApiError> notFound(JobNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(ListingNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ListingNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND", e.getMessage());
    }

    /** 알 수 없는 마켓 코드나 없는 상품처럼, 요청 자체가 성립하지 않는 경우 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    /** 정규화 실패는 같은 입력으로 재시도해도 소용없으므로 422 로 구분해 준다. */
    @ExceptionHandler(NormalizationException.class)
    public ResponseEntity<ApiError> unprocessable(NormalizationException e) {
        return build(HttpStatus.UNPROCESSABLE_CONTENT, "NORMALIZATION_FAILED", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> conflict(IllegalStateException e) {
        return build(HttpStatus.CONFLICT, "ILLEGAL_STATE", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse("요청 형식이 올바르지 않습니다");
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(), code, message));
    }
}
