package com.network.ping;

public record PingResult(boolean reachable, Long responseTimeMs) {
}
