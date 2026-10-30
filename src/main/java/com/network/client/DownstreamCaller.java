
package com.network.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.common.ApiException;
import com.network.config.NetworkProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import javax.net.ssl.SSLException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.security.cert.CertificateException;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Centralizes downstream HTTP execution, response validation,
 * exception translation, and retry handling.
 *
 * <p>Retry behavior:
 * <ul>
 *     <li>Retries are allowed only for explicitly idempotent operations.</li>
 *     <li>Only transient failures are retried.</li>
 *     <li>Retries use exponential backoff with full jitter.</li>
 *     <li>Malformed responses are never retried.</li>
 *     <li>HTTP 4xx responses are propagated without automatic retries.</li>
 * </ul>
 *
 * <p>Failure mapping:
 * <pre>
 * Timeout                         -> 504 DOWNSTREAM_TIMEOUT
 * Connection / transport failure -> 503 DOWNSTREAM_UNAVAILABLE
 * Retryable HTTP 5xx              -> 502 DOWNSTREAM_ERROR
 * Non-retryable HTTP 5xx          -> 502 DOWNSTREAM_ERROR
 * HTTP 4xx                        -> Original status and safe error code
 * Invalid response                -> 502 INVALID_DOWNSTREAM_RESPONSE
 * </pre>
 */
@Component
@Slf4j
public class DownstreamCaller {


    private static final int MAX_RETRIES = 5;

    private static final long BASE_BACKOFF_MS = 100;
    private static final long MAX_BACKOFF_MS = 1_000;

    private static final int MAX_CAUSE_DEPTH = 10;
    private static final int MAX_ERROR_CODE_LENGTH = 64;

    private static final Pattern SAFE_ERROR_CODE =
            Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final NetworkProperties props;
    private final ObjectMapper objectMapper;

    public DownstreamCaller(
            NetworkProperties props,
            ObjectMapper objectMapper) {

        this.props = Objects.requireNonNull(props, "props must not be null");
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null"
        );
    }

    public enum RetryPolicy {
        NEVER,
        IDEMPOTENT
    }

    /**
     * Executes a downstream HTTP request.
     *
     * @param service     logical name of the downstream service
     * @param retryPolicy retry policy for this operation
     * @param action      HTTP operation to execute
     * @param validator   semantic validation of the response body
     * @param <T>         expected response body type
     * @return validated downstream response
     */
    public <T> ResponseEntity<T> execute(
            String service,
            RetryPolicy retryPolicy,
            Supplier<ResponseEntity<T>> action,
            Predicate<T> validator) {

        Objects.requireNonNull(service, "service must not be null");
        Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(validator, "validator must not be null");

        if (service.isBlank()) {
            throw new IllegalArgumentException(
                    "service must not be blank"
            );
        }

        int maxAttempts = calculateMaxAttempts(retryPolicy);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {

            try {
                ResponseEntity<T> response = invoke(service, action);

                validateResponse(service, response, validator);

                return response;

            } catch (DownstreamException ex) {

                boolean canRetry =
                        retryPolicy == RetryPolicy.IDEMPOTENT
                                && ex.isRetryable()
                                && attempt < maxAttempts
                                && !Thread.currentThread().isInterrupted();

                if (!canRetry) {
                    throw ex;
                }

                long delay = calculateBackoffDelay(attempt);

                log.warn(
                        "Downstream call to {} failed with code {}. "
                                + "Retrying attempt {}/{} after {} ms",
                        service,
                        ex.getCode(),
                        attempt + 1,
                        maxAttempts,
                        delay
                );

                if (!waitBeforeRetry(delay)) {
                    throw ex;
                }
            }
        }

        throw new IllegalStateException(
                "Unexpected downstream retry loop termination"
        );
    }

    /**
     * Backward-compatible overload.
     * <p>
     * Migrate existing callers to RetryPolicy and remove this overload
     * once the clients have been updated.
     */
    @Deprecated
    public <T> ResponseEntity<T> execute(
            String service,
            boolean retryable,
            Supplier<ResponseEntity<T>> action,
            Predicate<T> validator) {

        return execute(
                service,
                retryable ? RetryPolicy.IDEMPOTENT : RetryPolicy.NEVER,
                action,
                validator
        );
    }

    /**
     * Executes one HTTP attempt and translates transport-level failures.
     */
    private <T> ResponseEntity<T> invoke(
            String service,
            Supplier<ResponseEntity<T>> action) {

        try {
            return action.get();

        } catch (HttpClientErrorException ex) {

            throw translateClientError(service, ex);

        } catch (HttpServerErrorException ex) {

            int statusCode = ex.getStatusCode().value();

            throw new DownstreamException(
                    HttpStatus.BAD_GATEWAY,
                    "DOWNSTREAM_ERROR",
                    service + " failed with HTTP " + statusCode,
                    isRetryableServerError(statusCode),
                    ex
            );

        } catch (ResourceAccessException ex) {

            if (isTimeout(ex)) {

                throw new DownstreamException(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "DOWNSTREAM_TIMEOUT",
                        service + " did not respond within the configured timeout",
                        true,
                        ex
                );
            }

            throw new DownstreamException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DOWNSTREAM_UNAVAILABLE",
                    service + " is unreachable",
                    !isPermanentTransportFailure(ex),
                    ex
            );

        } catch (RestClientException ex) {

            throw new DownstreamException(
                    HttpStatus.BAD_GATEWAY,
                    "INVALID_DOWNSTREAM_RESPONSE",
                    service + " returned an unreadable or unexpected response",
                    false,
                    ex
            );
        }
    }

    /**
     * Validates the HTTP response and its body.
     */
    private <T> void validateResponse(
            String service,
            ResponseEntity<T> response,
            Predicate<T> validator) {

        if (response == null) {
            throw invalidResponse(service, "returned a null HTTP response");
        }

        if (!response.getStatusCode().is2xxSuccessful()) {

            if (response.getStatusCode().is4xxClientError()) {
                throw new ApiException(
                        response.getStatusCode(),
                        "DOWNSTREAM_REJECTED",
                        service + " rejected the request (HTTP "
                                + response.getStatusCode().value() + ")",
                        null
                );
            }

            if (response.getStatusCode().is5xxServerError()) {
                int statusCode = response.getStatusCode().value();

                throw new DownstreamException(
                        HttpStatus.BAD_GATEWAY,
                        "DOWNSTREAM_ERROR",
                        service + " failed with HTTP " + statusCode,
                        isRetryableServerError(statusCode),
                        null
                );
            }

            throw invalidResponse(
                    service,
                    "returned an unexpected HTTP status "
                            + response.getStatusCode().value()
            );
        }

        T body = response.getBody();

        if (body == null) {
            throw invalidResponse(service, "returned an empty response body");
        }

        if (!validator.test(body)) {
            throw invalidResponse(service, "returned a malformed response body");
        }
    }

    /**
     * Translates downstream HTTP 4xx errors.
     * <p>
     * Preserves the downstream HTTP status and a validated machine-readable
     * error code, but deliberately does not expose arbitrary downstream
     * error messages to API consumers.
     */
    private ApiException translateClientError(
            String service,
            HttpClientErrorException ex) {

        String code = extractSafeErrorCode(
                ex.getResponseBodyAsString()
        );

        String message = service
                + " rejected the request (HTTP "
                + ex.getStatusCode().value()
                + ")";

        return new ApiException(
                ex.getStatusCode(),
                code,
                message,
                ex
        );
    }

    /**
     * Extracts a safe error code from a downstream JSON error response.
     */
    private String extractSafeErrorCode(String responseBody) {

        if (responseBody == null || responseBody.isBlank()) {
            return "DOWNSTREAM_REJECTED";
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            if (root == null) {
                return "DOWNSTREAM_REJECTED";
            }

            JsonNode codeNode = root.get("code");

            if (codeNode == null || !codeNode.isTextual()) {
                return "DOWNSTREAM_REJECTED";
            }

            String code = codeNode.asText();

            if (code.length() > MAX_ERROR_CODE_LENGTH
                    || !SAFE_ERROR_CODE.matcher(code).matches()) {

                return "DOWNSTREAM_REJECTED";
            }

            return code;

        } catch (JsonProcessingException ex) {

            return "DOWNSTREAM_REJECTED";
        }
    }

    /**
     * Determines whether an HTTP 5xx response is potentially transient.
     */
    private static boolean isRetryableServerError(int statusCode) {

        return statusCode == 500
                || statusCode == 502
                || statusCode == 503
                || statusCode == 504;
    }

    /**
     * Detects timeout exceptions in the cause chain.
     */
    private static boolean isTimeout(Throwable error) {

        int depth = 0;

        for (Throwable cause = error;
             cause != null && depth < MAX_CAUSE_DEPTH;
             cause = cause.getCause(), depth++) {

            if (cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException) {

                return true;
            }
        }

        return false;
    }

    /**
     * Detects transport failures that are unlikely to recover through retry.
     * <p>
     * SSL and certificate failures normally indicate a configuration or
     * trust problem rather than a temporary connectivity problem.
     */
    private static boolean isPermanentTransportFailure(Throwable error) {

        int depth = 0;

        for (Throwable cause = error;
             cause != null && depth < MAX_CAUSE_DEPTH;
             cause = cause.getCause(), depth++) {

            if (cause instanceof SSLException
                    || cause instanceof CertificateException) {

                return true;
            }
        }

        return false;
    }

    /**
     * Calculates maximum attempts using the configured retry count.
     * The configuration value represents additional retries, not attempts.
     * <p>
     * A safety cap prevents accidentally configuring an excessive retry loop.
     */
    private int calculateMaxAttempts(RetryPolicy retryPolicy) {

        if (retryPolicy == RetryPolicy.NEVER) {
            return 1;
        }

        int configuredRetries = Math.max(
                0,
                props.getClients().getRetries()
        );

        return 1 + Math.min(configuredRetries, MAX_RETRIES);
    }

    /**
     * Calculates full-jitter exponential backoff.
     * <p>
     * The delay increases exponentially but never exceeds MAX_BACKOFF_MS.
     */
    private static long calculateBackoffDelay(int failedAttempt) {

        int exponent = Math.min(failedAttempt - 1, 10);

        long exponentialDelay = BASE_BACKOFF_MS * (1L << exponent);

        long maxDelay = Math.min(
                exponentialDelay,
                MAX_BACKOFF_MS
        );

        return ThreadLocalRandom.current().nextLong(1, maxDelay + 1);
    }

    /**
     * Waits before retrying.
     * <p>
     * Preserves the interrupt flag and stops retrying when interrupted.
     */
    private static boolean waitBeforeRetry(long delayMs) {

        try {
            Thread.sleep(delayMs);
            return true;

        } catch (InterruptedException ex) {

            Thread.currentThread().interrupt();

            log.warn(
                    "Downstream retry interrupted. Cancelling further attempts."
            );

            return false;
        }
    }

    private static DownstreamException invalidResponse(
            String service,
            String detail) {

        return new DownstreamException(
                HttpStatus.BAD_GATEWAY,
                "INVALID_DOWNSTREAM_RESPONSE",
                service + " " + detail,
                false,
                null
        );
    }
}
