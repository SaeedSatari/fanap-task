package com.network.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateDeviceRequest(
        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name must be at most 100 characters")
        String name,

        @NotBlank(message = "ipAddress is required")
        @Pattern(regexp = DeviceValidation.IPV4_REGEX, message = "ipAddress must be a valid IPv4 address")
        String ipAddress,

        @NotNull(message = "type is required (MODEM or RADIO)")
        DeviceType type,

        /** Optional, defaults to true. */
        Boolean monitoringEnabled) {
}
