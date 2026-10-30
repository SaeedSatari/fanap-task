package com.network.ping;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record PingOperationResponse(
        UUID id,
        Long deviceId,
        PingStatus status,
        Boolean reachable,
        Long responseTimeMs,
        int attempts,
        int maxAttempts,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs) {

    public static PingOperationResponse from(PingOperation o) {
        Long duration = null;
        if (o.getStartedAt() != null && o.getFinishedAt() != null && !o.getFinishedAt().isBefore(o.getStartedAt())) {
            duration = Duration.between(o.getStartedAt(), o.getFinishedAt()).toMillis();
        }
        return new PingOperationResponse(o.getId(), o.getDeviceId(), o.getStatus(), o.getReachable(),
                o.getResponseTimeMs(), o.getAttempts(), o.getMaxAttempts(), o.getErrorMessage(),
                o.getCreatedAt(), o.getStartedAt(), o.getFinishedAt(), duration);
    }
}
