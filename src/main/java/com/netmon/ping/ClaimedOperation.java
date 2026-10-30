package com.netmon.ping;

import java.util.UUID;

/** Snapshot handed to the worker that won the claim; {@code attempt} is its fencing token. */
public record ClaimedOperation(UUID id, Long deviceId, String ipAddress, int attempt, int maxAttempts) {
}
