package com.network.ping;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ping_operations",
        uniqueConstraints = @UniqueConstraint(name = "uk_ping_device_idempotency",
                columnNames = {"device_id", "idempotency_key"}),
        indexes = {
                @Index(name = "idx_ping_status_next", columnList = "status,next_attempt_at"),
                @Index(name = "idx_ping_device_created", columnList = "device_id,created_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PingOperation {

    @Id
    private UUID id;

    @Version
    @Getter(AccessLevel.NONE)
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
}