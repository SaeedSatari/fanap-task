package com.network.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(Instant timestamp, int status, String code, String message, Map<String, String> details) {

    public static ApiError of(int status, String code, String message) {
        return new ApiError(Instant.now(), status, code, message, null);
    }

    public static ApiError of(int status, String code, String message, Map<String, String> details) {
        return new ApiError(Instant.now(), status, code, message, details);
    }
}
