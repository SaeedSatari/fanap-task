package com.netmon.common;

public final class Pagination {

    public static final int MAX_PAGE_SIZE = 100;

    private Pagination() { }

    public static void validate(int page, int size) {
        if (page < 0) {
            throw ApiException.badRequest("INVALID_PAGE", "page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("INVALID_PAGE_SIZE", "size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }
}
