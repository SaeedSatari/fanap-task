package com.network.client;

import com.network.ping.PingOperationResponse;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** RestTemplate client for the ping service (service #1). */
@Component
public class PingServiceClient {

    private static final String SERVICE = "ping-service";

    private final RestTemplate restTemplate;
    private final DownstreamCaller caller;
    private final ServiceEndpoints endpoints;

    public PingServiceClient(@Qualifier("pingRestTemplate") RestTemplate restTemplate,
                             DownstreamCaller caller, ServiceEndpoints endpoints) {
        this.restTemplate = restTemplate;
        this.caller = caller;
        this.endpoints = endpoints;
    }

    /** POST is never retried automatically; the caller can safely retry by re-sending the same Idempotency-Key. */
    public PingSubmission requestPing(Long deviceId, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        String url = endpoints.pingBaseUrl() + "/internal/ping/devices/{deviceId}/pings";
        ResponseEntity<PingOperationResponse> response = caller.execute(SERVICE, false,
                () -> restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(headers),
                        PingOperationResponse.class, deviceId),
                PingServiceClient::isWellFormed);
        return new PingSubmission(response.getBody(), response.getStatusCode().value() == 202);
    }

    public PingOperationResponse getOperation(UUID operationId) {
        String url = endpoints.pingBaseUrl() + "/internal/ping/pings/{operationId}";
        return caller.execute(SERVICE, true,
                () -> restTemplate.getForEntity(url, PingOperationResponse.class, operationId),
                PingServiceClient::isWellFormed).getBody();
    }

    public List<PingOperationResponse> history(Long deviceId, int limit) {
        String url = endpoints.pingBaseUrl() + "/internal/ping/devices/{deviceId}/pings?limit={limit}";
        PingOperationResponse[] body = caller.execute(SERVICE, true,
                () -> restTemplate.getForEntity(url, PingOperationResponse[].class, deviceId, limit),
                array -> Arrays.stream(array).allMatch(PingServiceClient::isWellFormed)).getBody();
        return Arrays.asList(body);
    }

    private static boolean isWellFormed(PingOperationResponse r) {
        return r != null && r.id() != null && r.deviceId() != null && r.status() != null;
    }
}
