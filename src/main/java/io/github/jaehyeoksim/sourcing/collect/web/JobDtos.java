package io.github.jaehyeoksim.sourcing.collect.web;

import tools.jackson.databind.JsonNode;
import io.github.jaehyeoksim.sourcing.collect.domain.CollectJob;
import io.github.jaehyeoksim.sourcing.collect.domain.JobStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** 수집 API 의 요청/응답 형태 모음. */
public final class JobDtos {

    private JobDtos() {
    }

    public record EnqueueRequest(
            @NotBlank String siteCode,
            @NotBlank String externalId,
            @NotBlank String sourceUrl) {
    }

    public record ResultRequest(
            String workerId,
            @NotNull JsonNode payload) {
    }

    public record FailureRequest(
            @NotBlank String reason) {
    }

    public record JobResponse(
            Long id,
            String siteCode,
            String externalId,
            String sourceUrl,
            JobStatus status,
            int attempt,
            int maxAttempts,
            Instant nextRunAt,
            String workerId,
            String lastError) {

        public static JobResponse from(CollectJob job) {
            return new JobResponse(
                    job.getId(),
                    job.getSiteCode(),
                    job.getExternalId(),
                    job.getSourceUrl(),
                    job.getStatus(),
                    job.getAttempt(),
                    job.getMaxAttempts(),
                    job.getNextRunAt(),
                    job.getWorkerId(),
                    job.getLastError());
        }
    }

    /** 원본을 해석할 수 없어 확정 실패로 끝난 경우의 응답 */
    public record RejectedResponse(Long jobId, JobStatus status, String reason) {
    }

    public record ProductResponse(Long id, String siteCode, String externalId, String title, int optionCount) {
    }
}
