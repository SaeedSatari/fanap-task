package com.network.ping;

import com.network.common.ApiException;
import com.network.config.NetworkProperties;
import com.network.device.Device;
import com.network.device.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Periodic monitoring: enqueues a ping for every device whose monitoring is enabled. The idempotency
 * key is derived from the time bucket, so a delayed or duplicated tick cannot enqueue the same
 * round twice.
 */
@Component
@ConditionalOnProperty(prefix = "network.monitoring", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class MonitoringScheduler {


    private final DeviceRepository devices;
    private final PingSubmissionService submissionService;
    private final NetworkProperties props;
    private final Clock clock;

    @Scheduled(initialDelayString = "${network.monitoring.interval-ms:30000}",
            fixedDelayString = "${network.monitoring.interval-ms:30000}")
    public void tick() {
        long bucketSeconds = Math.max(1, props.getMonitoring().getIntervalMs() / 1000);
        String bucketKey = "auto-" + (clock.instant().getEpochSecond() / bucketSeconds);
        for (Device device : devices.findByMonitoringEnabledTrue()) {
            try {
                submissionService.submit(device.getId(), bucketKey);
            } catch (ApiException e) {
                log.debug("Skipped automatic ping for device {}: {}", device.getId(), e.getCode());
            } catch (RuntimeException e) {
                log.error("Automatic ping submission failed for device {}", device.getId(), e);
            }
        }
    }
}
