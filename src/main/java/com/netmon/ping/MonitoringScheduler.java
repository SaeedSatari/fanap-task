package com.netmon.ping;

import com.netmon.common.ApiException;
import com.netmon.config.NetmonProperties;
import com.netmon.device.Device;
import com.netmon.device.DeviceRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic monitoring: enqueues a ping for every device whose monitoring is enabled. The idempotency
 * key is derived from the time bucket, so a delayed or duplicated tick cannot enqueue the same
 * round twice.
 */
@Component
@ConditionalOnProperty(prefix = "netmon.monitoring", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MonitoringScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitoringScheduler.class);

    private final DeviceRepository devices;
    private final PingSubmissionService submissionService;
    private final NetmonProperties props;
    private final Clock clock;

    public MonitoringScheduler(DeviceRepository devices, PingSubmissionService submissionService,
                               NetmonProperties props, Clock clock) {
        this.devices = devices;
        this.submissionService = submissionService;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${netmon.monitoring.interval-ms:30000}",
            fixedDelayString = "${netmon.monitoring.interval-ms:30000}")
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
