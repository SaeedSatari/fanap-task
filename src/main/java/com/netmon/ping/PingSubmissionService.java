package com.netmon.ping;

import com.netmon.common.ApiException;
import com.netmon.config.NetmonProperties;
import com.netmon.device.Device;
import com.netmon.device.DeviceRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PingSubmissionService {

    static final List<PingStatus> ACTIVE = List.of(PingStatus.PENDING, PingStatus.RUNNING);

    private final DeviceRepository devices;
    private final PingOperationRepository operations;
    private final PingDispatcher dispatcher;
    private final NetmonProperties props;
    private final Clock clock;

    public PingSubmissionService(DeviceRepository devices, PingOperationRepository operations,
                                 PingDispatcher dispatcher, NetmonProperties props, Clock clock) {
        this.devices = devices;
        this.operations = operations;
        this.dispatcher = dispatcher;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Accepts a ping request and returns immediately; the probe runs asynchronously.
     * <ol>
     *   <li>The device row is locked, so concurrent submissions for one device are serialised.</li>
     *   <li>Same idempotency key => the original operation is returned (nothing is re-executed).</li>
     *   <li>Monitoring disabled => 422. Another operation still active => 409.</li>
     * </ol>
     */
    @Transactional
    public SubmissionResult submit(Long deviceId, String idempotencyKey) {
        Device device = devices.findByIdForUpdate(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "Device " + deviceId + " not found"));

        if (idempotencyKey != null) {
            Optional<PingOperation> existing = operations.findByDeviceIdAndIdempotencyKey(deviceId, idempotencyKey);
            if (existing.isPresent()) {
                return new SubmissionResult(existing.get(), false);
            }
        }
        if (!device.isMonitoringEnabled()) {
            throw ApiException.unprocessable("MONITORING_DISABLED",
                    "Monitoring is disabled for device " + deviceId);
        }
        if (operations.existsByDeviceIdAndStatusIn(deviceId, ACTIVE)) {
            throw ApiException.conflict("ACTIVE_OPERATION_EXISTS",
                    "Device " + deviceId + " already has a ping operation in progress");
        }

        PingOperation operation = new PingOperation(deviceId, idempotencyKey,
                Math.max(1, props.getPing().getMaxAttempts()), clock.instant());
        operations.saveAndFlush(operation);
        dispatcher.dispatchAfterCommit(operation.getId());
        return new SubmissionResult(operation, true);
    }
}
