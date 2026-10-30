package com.network.ping;

import java.util.UUID;

public record ClaimedOperation(UUID id, Long deviceId, String ipAddress, int attempt, int maxAttempts) {
}
