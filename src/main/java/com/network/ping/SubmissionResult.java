package com.network.ping;

/** {@code created=false} means an earlier request with the same idempotency key was replayed. */
public record SubmissionResult(PingOperation operation, boolean created) {
}
