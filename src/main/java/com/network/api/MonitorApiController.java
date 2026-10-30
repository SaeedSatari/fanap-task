package com.network.api;

import com.network.client.MonitorServiceClient;
import com.network.common.ApiError;
import com.network.common.ApiException;
import com.network.monitor.MonitorSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Public API for monitoring. Talks to the monitor service exclusively through {@link MonitorServiceClient}.
 */
@Tag(name = "Monitor", description = "Availability and latency statistics from ping history")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MonitorApiController {

    private final MonitorServiceClient monitorClient;

    @Operation(summary = "Monitoring summary of a device",
            description = "Availability is computed from conclusive probes only (SUCCEEDED operations). "
                    + "FAILED and TIMED_OUT operations are counted separately and do not affect availability.")
    @ApiResponse(responseCode = "200", description = "Summary returned")
    @ApiResponse(responseCode = "400", description = "window out of range",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Device not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "502", description = "Monitor service returned an error or an invalid response",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "Monitor service unreachable",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "504", description = "Monitor service timed out",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/devices/{deviceId}/monitor")
    public MonitorSummaryResponse monitor(
            @Parameter(description = "Device id", example = "1")
            @PathVariable Long deviceId,
            @Parameter(description = "How many of the most recent operations to include (1-200)", example = "50")
            @RequestParam(defaultValue = "50") int window) {
        if (window < 1 || window > 200) {
            throw ApiException.badRequest("INVALID_WINDOW", "window must be between 1 and 200");
        }
        return monitorClient.summary(deviceId, window);
    }
}