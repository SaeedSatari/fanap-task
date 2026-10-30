package com.network.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/** Business / contract error that maps 1:1 to an HTTP status and a stable machine-readable code. */
public class ApiException extends RuntimeException {

    private final HttpStatusCode status;
    private final String code;

    public ApiException(HttpStatusCode status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatusCode status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatusCode getStatus() { return status; }
    public String getCode() { return code; }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
