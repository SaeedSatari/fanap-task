package com.network.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.common.ApiException;
import com.network.config.NetworkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Single place that defines how a RestTemplate call to another service behaves when things go wrong:
 *
 * <pre>
 * timeout (connect/read)       -> 504 DOWNSTREAM_TIMEOUT           (retried if idempotent GET)
 * connection refused / DNS ... -> 503 DOWNSTREAM_UNAVAILABLE       (retried if idempotent GET)
 * HTTP 5xx                     -> 502 DOWNSTREAM_ERROR             (retried if idempotent GET)
 * HTTP 4xx                     -> same status and code as downstream (e.g. 404 DEVICE_NOT_FOUND, 409 ...)
 * unreadable / empty / wrong   -> 502 INVALID_DOWNSTREAM_RESPONSE  (never retried)
 * shape (fails validation)
 * </pre>
 */
@Component
public class DownstreamCaller {

    private static final Logger log = LoggerFactory.getLogger(DownstreamCaller.class);

    private final NetworkProperties props;
    private final ObjectMapper objectMapper;

    public DownstreamCaller(NetworkProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * @param retryable true only for idempotent calls (GET); POST is never retried automatically
     * @param validator semantic check of the body; a body that fails it is treated as an invalid response
     */
    public <T> ResponseEntity<T> execute(String service, boolean retryable,
                                         Supplier<ResponseEntity<T>> action, Predicate<T> validator) {
        int maxAttempts = retryable ? 1 + Math.max(0, props.getClients().getRetries()) : 1;
        int attempt = 1;
        while (true) {
            try {
                ResponseEntity<T> response = invoke(service, action);
                T body = response.getBody();
                if (body == null || !validator.test(body)) {
                    throw new DownstreamException(HttpStatus.BAD_GATEWAY, "INVALID_DOWNSTREAM_RESPONSE",
                            service + " returned an empty or malformed response", false, null);
                }
                return response;
            } catch (DownstreamException e) {
                if (!e.isRetryable() || attempt >= maxAttempts) {
                    throw e;
                }
                log.warn("Call to {} failed ({}), retrying (attempt {}/{})", service, e.getCode(), attempt + 1, maxAttempts);
                attempt++;
            }
        }
    }

    private <T> ResponseEntity<T> invoke(String service, Supplier<ResponseEntity<T>> action) {
        try {
            return action.get();
        } catch (HttpClientErrorException e) {
            throw translateClientError(service, e);
        } catch (HttpServerErrorException e) {
            throw new DownstreamException(HttpStatus.BAD_GATEWAY, "DOWNSTREAM_ERROR",
                    service + " failed with HTTP " + e.getStatusCode().value(), true, e);
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                throw new DownstreamException(HttpStatus.GATEWAY_TIMEOUT, "DOWNSTREAM_TIMEOUT",
                        service + " did not respond within the configured timeout", true, e);
            }
            throw new DownstreamException(HttpStatus.SERVICE_UNAVAILABLE, "DOWNSTREAM_UNAVAILABLE",
                    service + " is unreachable", true, e);
        } catch (RestClientException e) {
            // message conversion failure, unknown content type, unknown status code, ...
            throw new DownstreamException(HttpStatus.BAD_GATEWAY, "INVALID_DOWNSTREAM_RESPONSE",
                    service + " returned an unreadable or unexpected response", false, e);
        }
    }

    /**
     * A 4xx means the request itself was rejected: surface the downstream status/code unchanged.
     */
    private ApiException translateClientError(String service, HttpClientErrorException e) {
        String code = "DOWNSTREAM_REJECTED";
        String message = service + " rejected the request (HTTP " + e.getStatusCode().value() + ")";
        try {
            JsonNode tree = objectMapper.readTree(e.getResponseBodyAsString());
            if (tree != null && tree.hasNonNull("code")) {
                code = tree.get("code").asText();
            }
            if (tree != null && tree.hasNonNull("message")) {
                message = tree.get("message").asText();
            }
        } catch (Exception ignored) {
            // body is not our error format; keep the generic code/message
        }
        return new ApiException(e.getStatusCode(), code, message, e);
    }

    private static boolean isTimeout(Throwable error) {
        int depth = 0;
        for (Throwable t = error; t != null && depth < 10; t = t.getCause(), depth++) {
            if (t instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
