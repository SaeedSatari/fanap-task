package com.network.ping;

import com.network.common.ApiException;
import com.network.config.NetworkProperties;
import com.network.device.Device;
import com.network.device.DeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PingSubmissionService {

    static final List<PingStatus> ACTIVE = List.of(PingStatus.PENDING, PingStatus.RUNNING);

    private final DeviceRepository devices;
    private final PingOperationRepository operations;
    private final PingDispatcher dispatcher;
    private final NetworkProperties props;
    private final Clock clock;

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
