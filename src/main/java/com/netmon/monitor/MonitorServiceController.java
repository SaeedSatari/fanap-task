package com.netmon.monitor;

import com.netmon.common.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** "Monitor service" (service #2): aggregated view of a device's ping history. Internal contract. */
@RestController
@RequestMapping("/internal/monitor")
public class MonitorServiceController {

    private static final int MAX_WINDOW = 200;

    private final MonitorService service;

    public MonitorServiceController(MonitorService service) {
        this.service = service;
    }

    @GetMapping("/devices/{deviceId}")
    public MonitorSummaryResponse summary(@PathVariable Long deviceId,
                                          @RequestParam(defaultValue = "50") int window) {
        if (window < 1 || window > MAX_WINDOW) {
            throw ApiException.badRequest("INVALID_WINDOW", "window must be between 1 and " + MAX_WINDOW);
        }
        return service.summarize(deviceId, window);
    }
}
