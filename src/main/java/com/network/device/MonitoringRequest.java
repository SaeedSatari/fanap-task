package com.network.device;

import jakarta.validation.constraints.NotNull;

public record MonitoringRequest(@NotNull(message = "enabled is required") Boolean enabled) {
}
