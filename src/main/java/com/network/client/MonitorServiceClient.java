
package com.network.client;

import com.network.monitor.MonitorSummaryResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class MonitorServiceClient {

    private static final String SERVICE = "monitor-service";

    private static final String SUMMARY_PATH =
            "/internal/monitor/devices/{deviceId}?window={window}";

    private final RestTemplate restTemplate;
    private final DownstreamCaller caller;
    private final ServiceEndpoints endpoints;

    public MonitorServiceClient(
            @Qualifier("monitorRestTemplate") RestTemplate restTemplate,
            DownstreamCaller caller,
            ServiceEndpoints endpoints) {

        this.restTemplate = restTemplate;
        this.caller = caller;
        this.endpoints = endpoints;
    }

    public MonitorSummaryResponse summary(Long deviceId, int window) {

        String url = endpoints.monitorBaseUrl() + SUMMARY_PATH;

        ResponseEntity<MonitorSummaryResponse> response = caller.execute(
                SERVICE,
                DownstreamCaller.RetryPolicy.IDEMPOTENT,
                () -> restTemplate.getForEntity(
                        url,
                        MonitorSummaryResponse.class,
                        deviceId,
                        window
                ),
                summary -> isWellFormed(summary, deviceId)
        );

        requireExpectedStatus(response);

        return response.getBody();
    }

    private static boolean isWellFormed(
            MonitorSummaryResponse summary,
            Long expectedDeviceId) {

        return summary != null
                && summary.deviceId() != null
                && summary.deviceId().equals(expectedDeviceId)
                && summary.lastStatus() != null
                && summary.totalOperations() >= 0
                && summary.recentOperations() != null
                && (summary.availabilityPercent() == null
                || (summary.availabilityPercent() >= 0
                && summary.availabilityPercent() <= 100));
    }

    private static void requireExpectedStatus(
            ResponseEntity<?> response) {

        if (response.getStatusCode().value() != HttpStatus.OK.value()) {

            throw new DownstreamException(
                    HttpStatus.BAD_GATEWAY,
                    "INVALID_DOWNSTREAM_RESPONSE",
                    SERVICE + " returned an unexpected HTTP status: "
                            + response.getStatusCode().value(),
                    false,
                    null
            );
        }
    }
}
