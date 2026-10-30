package com.network.ping;

/** Outcome of one probe. {@code reachable=false} is a valid, conclusive result (device is DOWN). */
public record PingResult(boolean reachable, Long responseTimeMs) {
}
