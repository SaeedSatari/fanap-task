package com.network.ping;

/**
 * PENDING --claim--> RUNNING --ok--> SUCCEEDED
 *                    RUNNING --error/timeout, attempts left--> PENDING (after backoff)
 *                    RUNNING --error, no attempts left--> FAILED
 *                    RUNNING --timeout, no attempts left--> TIMED_OUT
 *                    RUNNING --lease expired / restart--> PENDING (attempts left) or FAILED
 */
public enum PingStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT
}
