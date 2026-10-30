package com.netmon.monitor;

import com.netmon.device.ConnectionStatus;
import com.netmon.ping.PingOperationResponse;
import java.time.Instant;
import java.util.List;

public record MonitorSummaryResponse(
        Long deviceId,
        String deviceName,
        String ipAddress,
        boolean monitoringEnabled,
        ConnectionStatus lastStatus,
        Instant lastCheckedAt,
        int window,
        int totalOperations,
        int reachableCount,
        int unreachableCount,
        int failedCount,
        int inProgressCount,
        Double availabilityPercent,
        Double avgResponseTimeMs,
        Long minResponseTimeMs,
        Long maxResponseTimeMs,
        List<PingOperationResponse> recentOperations) {
}
