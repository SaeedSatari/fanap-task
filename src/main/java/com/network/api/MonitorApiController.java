package com.network.api;

import com.network.client.MonitorServiceClient;
import com.network.common.ApiException;
import com.network.monitor.MonitorSummaryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public API for monitoring. Talks to the monitor service exclusively through {@link MonitorServiceClient}. */
@RestController
@RequestMapping("/api/v1")
public class MonitorApiController {

    private final MonitorServiceClient monitorClient;

    public MonitorApiController(MonitorServiceClient monitorClient) {
        this.monitorClient = monitorClient;
    }

    @GetMapping("/devices/{deviceId}/monitor")
    public MonitorSummaryResponse monitor(@PathVariable Long deviceId,
                                          @RequestParam(defaultValue = "50") int window) {
        if (window < 1 || window > 200) {
            throw ApiException.badRequest("INVALID_WINDOW", "window must be between 1 and 200");
        }
        return monitorClient.summary(deviceId, window);
    }
}
