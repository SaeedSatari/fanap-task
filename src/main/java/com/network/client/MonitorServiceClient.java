package com.network.client;

import com.network.monitor.MonitorSummaryResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** RestTemplate client for the monitor service (service #2). */
@Component
public class MonitorServiceClient {

    private static final String SERVICE = "monitor-service";

    private final RestTemplate restTemplate;
    private final DownstreamCaller caller;
    private final ServiceEndpoints endpoints;

    public MonitorServiceClient(@Qualifier("monitorRestTemplate") RestTemplate restTemplate,
                                DownstreamCaller caller, ServiceEndpoints endpoints) {
        this.restTemplate = restTemplate;
        this.caller = caller;
        this.endpoints = endpoints;
    }

    public MonitorSummaryResponse summary(Long deviceId, int window) {
        String url = endpoints.monitorBaseUrl() + "/internal/monitor/devices/{deviceId}?window={window}";
        return caller.execute(SERVICE, true,
                () -> restTemplate.getForEntity(url, MonitorSummaryResponse.class, deviceId, window),
                MonitorServiceClient::isWellFormed).getBody();
    }

    private static boolean isWellFormed(MonitorSummaryResponse s) {
        return s != null && s.deviceId() != null && s.lastStatus() != null
                && s.totalOperations() >= 0 && s.recentOperations() != null
                && (s.availabilityPercent() == null
                || (s.availabilityPercent() >= 0 && s.availabilityPercent() <= 100));
    }
}
