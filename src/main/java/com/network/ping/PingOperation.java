package com.network.ping;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * One asynchronous ping request. All state transitions after creation are done with guarded
 * (compare-and-set) UPDATE statements in {@link PingOperationRepository}, never by mutating this entity.
 */
@Entity
@Table(name = "ping_operations",
        uniqueConstraints = @UniqueConstraint(name = "uk_ping_device_idempotency",
                columnNames = {"device_id", "idempotency_key"}),
        indexes = {
                @Index(name = "idx_ping_status_next", columnList = "status,next_attempt_at"),
                @Index(name = "idx_ping_device_created", columnList = "device_id,created_at")
        })
public class PingOperation {

    @Id
    private UUID id;

    @Version
    private Long version;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PingStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    private Boolean reachable;

    @Column(name = "response_time_ms")
    private Long responseTimeMs;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected PingOperation() { }

    public PingOperation(Long deviceId, String idempotencyKey, int maxAttempts, Instant now) {
        this.id = UUID.randomUUID();
        this.deviceId = deviceId;
        this.idempotencyKey = idempotencyKey;
        this.status = PingStatus.PENDING;
        this.attempts = 0;
        this.maxAttempts = maxAttempts;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public Long getDeviceId() { return deviceId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public PingStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public Boolean getReachable() { return reachable; }
    public Long getResponseTimeMs() { return responseTimeMs; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
