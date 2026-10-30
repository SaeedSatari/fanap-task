package com.netmon.device;

import java.time.Instant;

public record DeviceResponse(
        Long id,
        String name,
        String ipAddress,
        DeviceType type,
        boolean monitoringEnabled,
        ConnectionStatus lastStatus,
        Instant lastCheckedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static DeviceResponse from(Device d) {
        return new DeviceResponse(d.getId(), d.getName(), d.getIpAddress(), d.getType(),
                d.isMonitoringEnabled(), d.getLastStatus(), d.getLastCheckedAt(),
                d.getCreatedAt(), d.getUpdatedAt());
    }
}
