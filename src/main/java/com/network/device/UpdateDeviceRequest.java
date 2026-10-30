package com.network.device;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Fields of a device that can be changed. All fields are required (full replacement).")
public record UpdateDeviceRequest(
        @Schema(description = "Display name (max 100 characters)", example = "Modem Tehran-01 (renamed)")
        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name must be at most 100 characters")
        String name,

        @Schema(description = "IPv4 address, must be unique. Changing it resets the last known status to UNKNOWN.",
                example = "10.0.0.11")
        @NotBlank(message = "ipAddress is required")
        @Pattern(regexp = DeviceValidation.IPV4_REGEX, message = "ipAddress must be a valid IPv4 address")
        String ipAddress,

        @Schema(description = "Device type", example = "MODEM")
        @NotNull(message = "type is required (MODEM or RADIO)")
        DeviceType type) {
}