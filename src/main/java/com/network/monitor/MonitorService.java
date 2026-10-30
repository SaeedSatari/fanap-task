package com.network.monitor;

import com.network.common.ApiException;
import com.network.device.Device;
import com.network.device.DeviceRepository;
import com.network.ping.PingOperationRepository;
import com.network.ping.PingOperationResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonitorService {

    private static final int RECENT = 10;

    private final DeviceRepository devices;
    private final PingOperationRepository operations;

    public MonitorService(DeviceRepository devices, PingOperationRepository operations) {
        this.devices = devices;
        this.operations = operations;
    }

    @Transactional(readOnly = true)
    public MonitorSummaryResponse summarize(Long deviceId, int window) {
        Device device = devices.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "Device " + deviceId + " not found"));
        List<PingOperationResponse> ops = operations
                .findByDeviceIdOrderByCreatedAtDesc(deviceId, PageRequest.of(0, window))
                .stream().map(PingOperationResponse::from).toList();
        return buildSummary(device, ops, window);
    }

    /**
     * Availability is computed only from conclusive probes (SUCCEEDED operations): an operation that
     * FAILED or TIMED_OUT says nothing about the device, only about the probe.
     */
    static MonitorSummaryResponse buildSummary(Device device, List<PingOperationResponse> ops, int window) {
        int reachable = 0;
        int unreachable = 0;
        int failed = 0;
        int inProgress = 0;
        List<Long> responseTimes = new ArrayList<>();

        for (PingOperationResponse op : ops) {
            switch (op.status()) {
                case SUCCEEDED -> {
                    if (Boolean.TRUE.equals(op.reachable())) {
                        reachable++;
                        if (op.responseTimeMs() != null) {
                            responseTimes.add(op.responseTimeMs());
                        }
                    } else {
                        unreachable++;
                    }
                }
                case FAILED, TIMED_OUT -> failed++;
                case PENDING, RUNNING -> inProgress++;
            }
        }

        int conclusive = reachable + unreachable;
        Double availability = conclusive == 0 ? null : round2(100.0 * reachable / conclusive);
        Double avg = responseTimes.isEmpty() ? null
                : round2(responseTimes.stream().mapToLong(Long::longValue).average().orElse(0));
        Long min = responseTimes.stream().min(Long::compare).orElse(null);
        Long max = responseTimes.stream().max(Long::compare).orElse(null);

        return new MonitorSummaryResponse(device.getId(), device.getName(), device.getIpAddress(),
                device.isMonitoringEnabled(), device.getLastStatus(), device.getLastCheckedAt(),
                window, ops.size(), reachable, unreachable, failed, inProgress,
                availability, avg, min, max, ops.stream().limit(RECENT).toList());
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
