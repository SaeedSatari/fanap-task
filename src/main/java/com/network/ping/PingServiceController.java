package com.network.ping;

import com.network.common.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * "Ping service" (service #1). Internal contract; it is called over HTTP by {@code PingServiceClient}
 * so it can be moved to its own deployment without code changes. Do not expose /internal/** publicly.
 */
@RestController
@RequestMapping("/internal/ping")
public class PingServiceController {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_.:\\-]{1,100}$");
    private static final int MAX_LIMIT = 200;

    private final PingSubmissionService submissionService;
    private final PingQueryService queryService;

    public PingServiceController(PingSubmissionService submissionService, PingQueryService queryService) {
        this.submissionService = submissionService;
        this.queryService = queryService;
    }

    /**
     * 202 = new operation accepted; 200 + Idempotent-Replayed header = earlier request replayed.
     */
    @PostMapping("/devices/{deviceId}/pings")
    public ResponseEntity<PingOperationResponse> submit(
            @PathVariable Long deviceId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        SubmissionResult result = submissionService.submit(deviceId, normalizeKey(idempotencyKey));
        PingOperationResponse body = PingOperationResponse.from(result.operation());
        if (result.created()) {
            return ResponseEntity.accepted().body(body);
        }
        return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
    }

    @GetMapping("/pings/{operationId}")
    public PingOperationResponse get(@PathVariable UUID operationId) {
        return PingOperationResponse.from(queryService.get(operationId));
    }

    @GetMapping("/devices/{deviceId}/pings")
    public List<PingOperationResponse> history(@PathVariable Long deviceId,
                                               @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.badRequest("INVALID_LIMIT", "limit must be between 1 and " + MAX_LIMIT);
        }
        return queryService.history(deviceId, limit).stream().map(PingOperationResponse::from).toList();
    }

    private static String normalizeKey(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        if (!KEY_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1-100 characters of [A-Za-z0-9_.:-]");
        }
        return trimmed;
    }
}
