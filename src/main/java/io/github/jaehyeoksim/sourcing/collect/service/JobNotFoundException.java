package io.github.jaehyeoksim.sourcing.collect.service;

public class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(Long jobId) {
        super("작업을 찾을 수 없습니다: " + jobId);
    }
}
