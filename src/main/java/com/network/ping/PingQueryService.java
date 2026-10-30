package com.network.ping;

import com.network.common.ApiException;
import com.network.device.DeviceRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PingQueryService {

    private final PingOperationRepository operations;
    private final DeviceRepository devices;

    public PingQueryService(PingOperationRepository operations, DeviceRepository devices) {
        this.operations = operations;
        this.devices = devices;
    }

    @Transactional(readOnly = true)
    public PingOperation get(UUID id) {
        return operations.findById(id)
                .orElseThrow(() -> ApiException.notFound("OPERATION_NOT_FOUND", "Ping operation " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<PingOperation> history(Long deviceId, int limit) {
        if (!devices.existsById(deviceId)) {
            throw ApiException.notFound("DEVICE_NOT_FOUND", "Device " + deviceId + " not found");
        }
        return operations.findByDeviceIdOrderByCreatedAtDesc(deviceId, PageRequest.of(0, limit));
    }
}
