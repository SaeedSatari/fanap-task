package com.network.device;

import com.network.common.ApiException;
import com.network.ping.PingOperationRepository;
import com.network.ping.PingStatus;
import java.time.Clock;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceService {

    private final DeviceRepository devices;
    private final PingOperationRepository operations;
    private final Clock clock;

    public DeviceService(DeviceRepository devices, PingOperationRepository operations, Clock clock) {
        this.devices = devices;
        this.operations = operations;
        this.clock = clock;
    }

    @Transactional
    public Device create(CreateDeviceRequest request) {
        String ip = request.ipAddress().trim();
        if (devices.existsByIpAddress(ip)) {
            throw duplicateIp(ip);
        }
        boolean monitoring = request.monitoringEnabled() == null || request.monitoringEnabled();
        Device device = new Device(request.name().trim(), ip, request.type(), monitoring, clock.instant());
        try {
            return devices.saveAndFlush(device);
        } catch (DataIntegrityViolationException e) {
            throw duplicateIp(ip); // lost a race with a concurrent create
        }
    }

    @Transactional(readOnly = true)
    public Device get(Long id) {
        return devices.findById(id).orElseThrow(() -> notFound(id));
    }

    @Transactional(readOnly = true)
    public Page<Device> list(int page, int size) {
        return devices.findAll(PageRequest.of(page, size, Sort.by("id")));
    }

    @Transactional
    public Device update(Long id, UpdateDeviceRequest request) {
        Device device = get(id);
        String ip = request.ipAddress().trim();
        if (!ip.equals(device.getIpAddress()) && devices.existsByIpAddressAndIdNot(ip, id)) {
            throw duplicateIp(ip);
        }
        device.updateDetails(request.name().trim(), ip, request.type(), clock.instant());
        try {
            return devices.saveAndFlush(device);
        } catch (DataIntegrityViolationException e) {
            throw duplicateIp(ip);
        }
    }

    @Transactional
    public Device setMonitoring(Long id, boolean enabled) {
        Device device = get(id);
        device.setMonitoringEnabled(enabled, clock.instant());
        return devices.save(device);
    }

    @Transactional
    public void delete(Long id) {
        Device device = devices.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        if (operations.existsByDeviceIdAndStatusIn(id, List.of(PingStatus.PENDING, PingStatus.RUNNING))) {
            throw ApiException.conflict("ACTIVE_OPERATION_EXISTS",
                    "Device " + id + " has a ping operation in progress; try again when it finishes");
        }
        operations.deleteAllByDeviceId(id);
        devices.delete(device);
    }

    private static ApiException notFound(Long id) {
        return ApiException.notFound("DEVICE_NOT_FOUND", "Device " + id + " not found");
    }

    private static ApiException duplicateIp(String ip) {
        return ApiException.conflict("DUPLICATE_IP", "A device with IP address " + ip + " already exists");
    }
}
