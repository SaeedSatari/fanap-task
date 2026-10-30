package com.network.api;

import com.network.client.PingServiceClient;
import com.network.client.PingSubmission;
import com.network.common.ApiException;
import com.network.ping.PingOperationResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Public API for pings. Talks to the ping service exclusively through {@link PingServiceClient}.
 */
@RestController
@RequestMapping("/api/v1")
public class PingApiController {

    private final PingServiceClient pingClient;

    public PingApiController(PingServiceClient pingClient) {
        this.pingClient = pingClient;
    }

    /**
     * Returns immediately: 202 + operation to poll; 200 if the same Idempotency-Key was already used.
     */
    @PostMapping("/devices/{deviceId}/pings")
    public ResponseEntity<PingOperationResponse> requestPing(
            @PathVariable Long deviceId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        PingSubmission submission = pingClient.requestPing(deviceId, idempotencyKey);
        URI location = URI.create("/api/v1/pings/" + submission.operation().id());
        if (submission.created()) {
            return ResponseEntity.accepted().location(location).body(submission.operation());
        }
        return ResponseEntity.ok().location(location).header("Idempotent-Replayed", "true")
                .body(submission.operation());
    }

    @GetMapping("/pings/{operationId}")
    public PingOperationResponse getOperation(@PathVariable UUID operationId) {
        return pingClient.getOperation(operationId);
    }

    @GetMapping("/devices/{deviceId}/pings")
    public List<PingOperationResponse> history(@PathVariable Long deviceId,
                                               @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 200) {
            throw ApiException.badRequest("INVALID_LIMIT", "limit must be between 1 and 200");
        }
        return pingClient.history(deviceId, limit);
    }
}
