package com.netmon.client;

import com.netmon.common.ApiException;
import org.springframework.http.HttpStatus;

/** A downstream service could not give us a usable answer (timeout, unreachable, 5xx, garbage). */
public class DownstreamException extends ApiException {

    private final boolean retryable;

    public DownstreamException(HttpStatus status, String code, String message, boolean retryable, Throwable cause) {
        super(status, code, message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
