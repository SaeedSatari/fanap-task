package com.network.client;

import com.network.common.ApiException;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class DownstreamException extends ApiException {

    private final boolean retryable;

    public DownstreamException(HttpStatus status, String code, String message, boolean retryable, Throwable cause) {
        super(status, code, message, cause);
        this.retryable = retryable;
    }
}
