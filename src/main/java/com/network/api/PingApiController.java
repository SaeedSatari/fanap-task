package com.network.api;

import com.network.client.PingServiceClient;
import com.network.client.PingSubmission;
import com.network.common.ApiError;
import com.network.common.ApiException;
import com.network.ping.PingOperationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Public API for pings. Talks to the ping service exclusively through {@link PingServiceClient}.
 */
@Tag(name = "Ping", description = "Asynchronous ping operations and their history")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PingApiController {

    private final PingServiceClient pingClient;

    @Operation(summary = "Request an asynchronous ping",
            description = "Returns immediately. Poll GET /api/v1/pings/{operationId} for the result.")
    @ApiResponse(responseCode = "202", description = "New operation accepted")
    @ApiResponse(responseCode = "200",
            description = "Same Idempotency-Key replayed; the original operation is returned (header Idempotent-Replayed: true)")
    @ApiResponse(responseCode = "400", description = "Invalid Idempotency-Key",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Device not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Another operation is already active for this device",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "Monitoring is disabled for this device",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "502", description = "Ping service returned an error or an invalid response",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "Ping service unreachable",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "504", description = "Ping service timed out",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/devices/{deviceId}/pings")
    public ResponseEntity<PingOperationResponse> requestPing(
            @Parameter(description = "Device id", example = "1")
            @PathVariable Long deviceId,
            @Parameter(description = "Optional. Repeating the same key returns the original operation instead of "
                    + "pinging again. 1-100 characters of [A-Za-z0-9_.:-]", example = "demo-0001")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        PingSubmission submission = pingClient.requestPing(deviceId, idempotencyKey);
        URI location = URI.create("/api/v1/pings/" + submission.operation().id());
        if (submission.created()) {
            return ResponseEntity.accepted().location(location).body(submission.operation());
        }
        return ResponseEntity.ok().location(location).header("Idempotent-Replayed", "true")
                .body(submission.operation());
    }

    @Operation(summary = "Get the status and result of a ping operation")
    @ApiResponse(responseCode = "200", description = "Operation found")
    @ApiResponse(responseCode = "400", description = "Malformed operation id",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Operation not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/pings/{operationId}")
    public PingOperationResponse getOperation(
            @Parameter(description = "Operation id returned when the ping was requested",
                    example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
            @PathVariable UUID operationId) {
        return pingClient.getOperation(operationId);
    }

    @Operation(summary = "Ping history of a device", description = "Most recent operations first.")
    @ApiResponse(responseCode = "200", description = "History returned")
    @ApiResponse(responseCode = "400", description = "limit out of range",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Device not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/devices/{deviceId}/pings")
    public List<PingOperationResponse> history(
            @Parameter(description = "Device id", example = "1")
            @PathVariable Long deviceId,
            @Parameter(description = "Maximum number of operations (1-200)", example = "20")
            @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 200) {
            throw ApiException.badRequest("INVALID_LIMIT", "limit must be between 1 and 200");
        }
        return pingClient.history(deviceId, limit);
    }
}