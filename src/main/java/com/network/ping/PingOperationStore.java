package com.network.ping;

import com.network.config.NetworkProperties;
import com.network.device.ConnectionStatus;
import com.network.device.Device;
import com.network.device.DeviceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Every state transition of an operation (and the matching device update) is one short transaction. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PingOperationStore {

    private static final int MAX_ERROR_LENGTH = 500;

    private final PingOperationRepository operations;
    private final DeviceRepository devices;
    private final NetworkProperties props;
    private final Clock clock;

    /** PENDING -> RUNNING with a lease. Empty when someone else already claimed it (or it is not due yet). */
    @Transactional
    public Optional<ClaimedOperation> claim(UUID id) {
        Instant now = clock.instant();
        Instant lease = now.plus(props.getPing().getTimeout()).plus(props.getPing().getLeaseMargin());
        if (operations.claim(id, now, lease) == 0) {
            return Optional.empty();
        }
        PingOperation op = operations.findById(id).orElseThrow();
        Optional<Device> device = devices.findById(op.getDeviceId());
        if (device.isEmpty()) {
            operations.finish(id, op.getAttempts(), PingStatus.FAILED, null, null, "Device no longer exists", now);
            return Optional.empty();
        }
        return Optional.of(new ClaimedOperation(id, op.getDeviceId(), device.get().getIpAddress(),
                op.getAttempts(), op.getMaxAttempts()));
    }

    /** RUNNING -> SUCCEEDED and update the device's last status. */
    @Transactional
    public boolean succeed(ClaimedOperation op, PingResult result) {
        Instant now = clock.instant();
        int updated = operations.finish(op.id(), op.attempt(), PingStatus.SUCCEEDED,
                result.reachable(), result.responseTimeMs(), null, now);
        if (updated == 0) {
            log.warn("Result of operation {} (attempt {}) discarded: it is no longer the active attempt",
                    op.id(), op.attempt());
            return false;
        }
        devices.updateStatus(op.deviceId(), result.reachable() ? ConnectionStatus.UP : ConnectionStatus.DOWN, now);
        return true;
    }

    /** RUNNING -> PENDING, to be picked up again at {@code nextAttemptAt}. */
    @Transactional
    public boolean retryLater(ClaimedOperation op, String error, Instant nextAttemptAt) {
        return operations.requeue(op.id(), op.attempt(), nextAttemptAt, truncate(error)) == 1;
    }

    /** RUNNING -> FAILED / TIMED_OUT (no attempts left). The device status becomes UNKNOWN (inconclusive). */
    @Transactional
    public boolean fail(ClaimedOperation op, PingStatus terminalStatus, String error) {
        if (terminalStatus != PingStatus.FAILED && terminalStatus != PingStatus.TIMED_OUT) {
            throw new IllegalArgumentException("Not a failure status: " + terminalStatus);
        }
        Instant now = clock.instant();
        int updated = operations.finish(op.id(), op.attempt(), terminalStatus, null, null, truncate(error), now);
        if (updated == 0) {
            return false;
        }
        devices.updateStatus(op.deviceId(), ConnectionStatus.UNKNOWN, now);
        return true;
    }

    /**
     * Resolves operations stuck in RUNNING (worker crashed, application restarted, lease expired):
     * re-queue them if attempts to remain, otherwise fail them. Probing is idempotent, so re-running is safe.
     *
     * @param cutoff operations whose lease expires before this instant are considered orphaned
     */
    @Transactional
    public int recoverStaleOperations(Instant cutoff) {
        Instant now = clock.instant();
        int recovered = 0;
        for (PingOperation op : operations.findByStatusAndLeaseExpiresAtBefore(PingStatus.RUNNING, cutoff)) {
            String message = "Interrupted (worker stopped or lease expired) during attempt " + op.getAttempts();
            int updated;
            if (op.getAttempts() >= op.getMaxAttempts()) {
                updated = operations.finish(op.getId(), op.getAttempts(), PingStatus.FAILED, null, null, message, now);
                if (updated == 1) {
                    devices.updateStatus(op.getDeviceId(), ConnectionStatus.UNKNOWN, now);
                }
            } else {
                updated = operations.requeue(op.getId(), op.getAttempts(), now, message + "; retrying");
            }
            if (updated == 1) {
                recovered++;
                log.warn("Recovered orphaned operation {} ({})", op.getId(), message);
            }
        }
        return recovered;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
