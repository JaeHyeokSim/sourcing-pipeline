package io.github.jaehyeoksim.sourcing.listing.service;

public class ListingNotFoundException extends RuntimeException {

    public ListingNotFoundException(Long id) {
        super("등록 건을 찾을 수 없습니다: " + id);
    }
}
