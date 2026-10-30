package com.network.device;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Turns monitoring of a device on or off")
public record MonitoringRequest(
        @Schema(description = "true: automatic pings are scheduled and manual pings are allowed. "
                + "false: no automatic pings, and manual pings are rejected with 422.",
                example = "true")
        @NotNull(message = "enabled is required")
        Boolean enabled) {
}