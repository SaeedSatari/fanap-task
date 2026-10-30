
package com.network.client;

import com.network.ping.PingOperationResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Component
public class PingServiceClient {

    private static final String SERVICE = "ping-service";

    private static final String SUBMIT_PATH =
            "/internal/ping/devices/{deviceId}/pings";

    private static final String OPERATION_PATH =
            "/internal/ping/pings/{operationId}";

    private static final String HISTORY_PATH =
            "/internal/ping/devices/{deviceId}/pings?limit={limit}";

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final RestTemplate restTemplate;
    private final DownstreamCaller caller;
    private final ServiceEndpoints endpoints;

    public PingServiceClient(
            @Qualifier("pingRestTemplate") RestTemplate restTemplate,
            DownstreamCaller caller,
            ServiceEndpoints endpoints) {

        this.restTemplate = restTemplate;
        this.caller = caller;
        this.endpoints = endpoints;
    }

    /**
     * Submits an asynchronous ping operation.
     * <p>
     * POST is deliberately configured without automatic retries.
     */
    public PingSubmission requestPing(
            Long deviceId,
            String idempotencyKey) {

        HttpHeaders headers = new HttpHeaders();

        if (idempotencyKey != null) {
            headers.set(IDEMPOTENCY_KEY, idempotencyKey);
        }

        String url = endpoints.pingBaseUrl() + SUBMIT_PATH;

        ResponseEntity<PingOperationResponse> response = caller.execute(
                SERVICE,
                DownstreamCaller.RetryPolicy.NEVER,
                () -> restTemplate.exchange(
                        url,
                        HttpMethod.POST,
                        new HttpEntity<>(headers),
                        PingOperationResponse.class,
                        deviceId
                ),
                operation -> isWellFormed(operation)
                        && operation.deviceId().equals(deviceId)
        );

        requireExpectedStatus(
                response,
                HttpStatus.OK,
                HttpStatus.ACCEPTED
        );

        return new PingSubmission(
                response.getBody(),
                response.getStatusCode().value() == HttpStatus.ACCEPTED.value()
        );
    }

    /**
     * Retrieves a previously submitted ping operation.
     */
    public PingOperationResponse getOperation(UUID operationId) {

        String url = endpoints.pingBaseUrl() + OPERATION_PATH;

        ResponseEntity<PingOperationResponse> response = caller.execute(
                SERVICE,
                DownstreamCaller.RetryPolicy.IDEMPOTENT,
                () -> restTemplate.getForEntity(
                        url,
                        PingOperationResponse.class,
                        operationId
                ),
                operation -> isWellFormed(operation)
                        && operationId.equals(operation.id())
        );

        requireExpectedStatus(response, HttpStatus.OK);

        return response.getBody();
    }

    /**
     * Retrieves the most recent ping operations for a device.
     */
    public List<PingOperationResponse> history(
            Long deviceId,
            int limit) {

        String url = endpoints.pingBaseUrl() + HISTORY_PATH;

        ResponseEntity<PingOperationResponse[]> response = caller.execute(
                SERVICE,
                DownstreamCaller.RetryPolicy.IDEMPOTENT,
                () -> restTemplate.getForEntity(
                        url,
                        PingOperationResponse[].class,
                        deviceId,
                        limit
                ),
                operations -> operations.length <= limit
                        && Arrays.stream(operations)
                        .allMatch(operation ->
                                isWellFormed(operation)
                                        && operation.deviceId().equals(deviceId)
                        )
        );

        requireExpectedStatus(response, HttpStatus.OK);

        return List.copyOf(Arrays.asList(response.getBody()));
    }

    private static boolean isWellFormed(PingOperationResponse operation) {

        return operation != null
                && operation.id() != null
                && operation.deviceId() != null
                && operation.status() != null;
    }

    private static void requireExpectedStatus(
            ResponseEntity<?> response,
            HttpStatus... expectedStatuses) {

        int actualStatus = response.getStatusCode().value();

        boolean accepted = Arrays.stream(expectedStatuses)
                .anyMatch(status -> status.value() == actualStatus);

        if (!accepted) {

            throw new DownstreamException(
                    HttpStatus.BAD_GATEWAY,
                    "INVALID_DOWNSTREAM_RESPONSE",
                    SERVICE + " returned an unexpected HTTP status: "
                            + actualStatus,
                    false,
                    null
            );
        }
    }
}
