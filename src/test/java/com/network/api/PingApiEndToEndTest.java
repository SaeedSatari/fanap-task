package com.network.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "network.monitoring.enabled=false",
        "network.ping.timeout=300ms",
        "network.ping.max-attempts=2",
        "network.ping.retry-backoff=50ms",
        "network.recovery.sweep-interval-ms=100",
        "network.mock.min-latency-ms=5",
        "network.mock.max-latency-ms=20",
        "network.mock.reachable-probability=1.0",
        "network.mock.transient-error-probability=0.0",
        "spring.datasource.url=jdbc:h2:mem:e2e-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
class PingApiEndToEndTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {
    };

    @Autowired
    TestRestTemplate rest;


    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String url, Object body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange(url, method, new HttpEntity<>(body, headers), MAP);
    }

    private long createDevice(String ip) {
        return createDevice(ip, true);
    }

    private long createDevice(String ip, boolean monitoring) {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/v1/devices",
                Map.of("name", "dev-" + ip, "ipAddress", ip, "type", "MODEM", "monitoringEnabled", monitoring), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Assertions.assertNotNull(response.getBody());
        return ((Number) response.getBody().get("id")).longValue();
    }

    private Map<String, Object> awaitFinalState(String operationId) {
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            String status = (String) Objects.requireNonNull(call(HttpMethod.GET, "/api/v1/pings/" + operationId, null, null).getBody()).get("status");
            return !status.equals("PENDING") && !status.equals("RUNNING");
        });
        return call(HttpMethod.GET, "/api/v1/pings/" + operationId, null, null).getBody();
    }

    private Map<String, Object> device(long id) {
        return call(HttpMethod.GET, "/api/v1/devices/" + id, null, null).getBody();
    }


    @Test
    void invalidDevice_isRejectedWithFieldDetails() {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/v1/devices",
                Map.of("name", "", "ipAddress", "999.1.1.1", "type", "MODEM"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Assertions.assertNotNull(response.getBody());
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
        @SuppressWarnings("unchecked")
        Map<String, String> details = (Map<String, String>) response.getBody().get("details");
        assertThat(details).containsKeys("name", "ipAddress");
    }

    @Test
    void unknownDeviceType_andMalformedJson_areBadRequests() {
        ResponseEntity<Map<String, Object>> badType = call(HttpMethod.POST, "/api/v1/devices",
                Map.of("name", "x", "ipAddress", "10.9.9.1", "type", "TOASTER"), null);
        assertThat(badType.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Assertions.assertNotNull(badType.getBody());
        assertThat(badType.getBody().get("code")).isEqualTo("MALFORMED_REQUEST");

        ResponseEntity<Map<String, Object>> badJson = call(HttpMethod.POST, "/api/v1/devices", "{oops", null);
        assertThat(badJson.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deviceCrud_andDuplicateIp() {
        long id = createDevice("10.9.0.1");

        Map<String, Object> created = device(id);
        assertThat(created.get("lastStatus")).isEqualTo("UNKNOWN");
        assertThat(created.get("monitoringEnabled")).isEqualTo(true);

        ResponseEntity<Map<String, Object>> duplicate = call(HttpMethod.POST, "/api/v1/devices",
                Map.of("name", "other", "ipAddress", "10.9.0.1", "type", "RADIO"), null);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Assertions.assertNotNull(duplicate.getBody());
        assertThat(duplicate.getBody().get("code")).isEqualTo("DUPLICATE_IP");

        ResponseEntity<Map<String, Object>> updated = call(HttpMethod.PUT, "/api/v1/devices/" + id,
                Map.of("name", "renamed", "ipAddress", "10.9.0.1", "type", "RADIO"), null);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        Assertions.assertNotNull(updated.getBody());
        assertThat(updated.getBody().get("name")).isEqualTo("renamed");

        ResponseEntity<Map<String, Object>> toggled = call(HttpMethod.PUT, "/api/v1/devices/" + id + "/monitoring",
                Map.of("enabled", false), null);
        Assertions.assertNotNull(toggled.getBody());
        assertThat(toggled.getBody().get("monitoringEnabled")).isEqualTo(false);

        assertThat(call(HttpMethod.DELETE, "/api/v1/devices/" + id, null, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.GET, "/api/v1/devices/" + id, null, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void invalidPaging_isRejected() {
        assertThat(call(HttpMethod.GET, "/api/v1/devices?size=0", null, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET, "/api/v1/devices?page=abc", null, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }


    @Test
    void ping_isAsynchronous_idempotent_andUpdatesTheDevice() {
        long deviceId = createDevice("10.9.1.1");

        ResponseEntity<Map<String, Object>> accepted =
                call(HttpMethod.POST, "/api/v1/devices/" + deviceId + "/pings", null, "req-1");
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getHeaders().getLocation()).isNotNull();
        Assertions.assertNotNull(accepted.getBody());
        String operationId = (String) accepted.getBody().get("id");

        ResponseEntity<Map<String, Object>> replay =
                call(HttpMethod.POST, "/api/v1/devices/" + deviceId + "/pings", null, "req-1");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        Assertions.assertNotNull(replay.getBody());
        assertThat(replay.getBody().get("id")).isEqualTo(operationId);

        Map<String, Object> result = awaitFinalState(operationId);
        assertThat(result.get("status")).isEqualTo("SUCCEEDED");
        assertThat(result.get("reachable")).isEqualTo(true);
        assertThat(result.get("responseTimeMs")).isNotNull();
        assertThat(result.get("startedAt")).isNotNull();
        assertThat(result.get("finishedAt")).isNotNull();
        assertThat(result.get("durationMs")).isNotNull();

        Map<String, Object> deviceState = device(deviceId);
        assertThat(deviceState.get("lastStatus")).isEqualTo("UP");
        assertThat(deviceState.get("lastCheckedAt")).isNotNull();

        ResponseEntity<List<Map<String, Object>>> history = rest.exchange(
                "/api/v1/devices/" + deviceId + "/pings", HttpMethod.GET, null, LIST);
        assertThat(history.getBody()).hasSize(1);

        Map<String, Object> monitor = call(HttpMethod.GET, "/api/v1/devices/" + deviceId + "/monitor", null, null).getBody();
        Assertions.assertNotNull(monitor);
        assertThat(((Number) monitor.get("totalOperations")).intValue()).isEqualTo(1);
        assertThat(((Number) monitor.get("availabilityPercent")).doubleValue()).isEqualTo(100.0);
        assertThat(monitor.get("lastStatus")).isEqualTo("UP");
    }

    @Test
    void unreachableDevice_isASuccessfulOperation_withDownStatus() {
        long deviceId = createDevice("192.0.2.10");

        String operationId = (String) Objects.requireNonNull(call(HttpMethod.POST, "/api/v1/devices/" + deviceId + "/pings", null, null)
                .getBody()).get("id");
        Map<String, Object> result = awaitFinalState(operationId);

        assertThat(result.get("status")).isEqualTo("SUCCEEDED");
        assertThat(result.get("reachable")).isEqualTo(false);
        assertThat(device(deviceId).get("lastStatus")).isEqualTo("DOWN");
    }

    @Test
    void hangingProbe_timesOut_retries_thenEndsTimedOut_andBlocksConcurrentRequests() {
        long deviceId = createDevice("198.51.100.7");

        String operationId = (String) Objects.requireNonNull(call(HttpMethod.POST, "/api/v1/devices/" + deviceId + "/pings", null, null)
                .getBody()).get("id");

        ResponseEntity<Map<String, Object>> conflict =
                call(HttpMethod.POST, "/api/v1/devices/" + deviceId + "/pings", null, null);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Assertions.assertNotNull(conflict.getBody());
        assertThat(conflict.getBody().get("code")).isEqualTo("ACTIVE_OPERATION_EXISTS");

        assertThat(call(HttpMethod.DELETE, "/api/v1/devices/" + deviceId, null, null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        Map<String, Object> result = awaitFinalState(operationId);
        assertThat(result.get("status")).isEqualTo("TIMED_OUT");
        assertThat(((Number) result.get("attempts")).intValue()).isEqualTo(2);
        assertThat((String) result.get("errorMessage")).contains("timed out");
        assertThat(device(deviceId).get("lastStatus")).isEqualTo("UNKNOWN");

        assertThat(call(HttpMethod.POST, "/api/v1/devices/" + deviceId + "/pings", null, null).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void failingProbe_isRetried_thenEndsFailed() {
        long deviceId = createDevice("203.0.113.5");

        String operationId = (String) Objects.requireNonNull(call(HttpMethod.POST, "/api/v1/devices/" + deviceId + "/pings", null, null)
                .getBody()).get("id");
        Map<String, Object> result = awaitFinalState(operationId);

        assertThat(result.get("status")).isEqualTo("FAILED");
        assertThat(((Number) result.get("attempts")).intValue()).isEqualTo(2);
        assertThat((String) result.get("errorMessage")).contains("Simulated network error");
    }


    @Test
    void ping_onDisabledMonitoring_is422() {
        long deviceId = createDevice("10.9.2.1", false);

        ResponseEntity<Map<String, Object>> response =
                call(HttpMethod.POST, "/api/v1/devices/" + deviceId + "/pings", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        Assertions.assertNotNull(response.getBody());
        assertThat(response.getBody().get("code")).isEqualTo("MONITORING_DISABLED");
    }

    @Test
    void unknownResources_areReportedAs404_throughTheDownstreamServices() {
        ResponseEntity<Map<String, Object>> ping = call(HttpMethod.POST, "/api/v1/devices/987654/pings", null, null);
        assertThat(ping.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Assertions.assertNotNull(ping.getBody());
        assertThat(ping.getBody().get("code")).isEqualTo("DEVICE_NOT_FOUND");

        ResponseEntity<Map<String, Object>> monitor = call(HttpMethod.GET, "/api/v1/devices/987654/monitor", null, null);
        assertThat(monitor.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map<String, Object>> operation = call(HttpMethod.GET,
                "/api/v1/pings/7c9e6679-7425-40de-944b-e07fc1f90ae7", null, null);
        assertThat(operation.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Assertions.assertNotNull(operation.getBody());
        assertThat(operation.getBody().get("code")).isEqualTo("OPERATION_NOT_FOUND");
    }

    @Test
    void malformedInputs_areBadRequests() {
        long deviceId = createDevice("10.9.3.1");

        ResponseEntity<Map<String, Object>> badKey =
                call(HttpMethod.POST, "/api/v1/devices/" + deviceId + "/pings", null, "not a valid key!");
        assertThat(badKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Assertions.assertNotNull(badKey.getBody());
        assertThat(badKey.getBody().get("code")).isEqualTo("INVALID_IDEMPOTENCY_KEY");

        assertThat(call(HttpMethod.GET, "/api/v1/pings/not-a-uuid", null, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET, "/api/v1/devices/" + deviceId + "/pings?limit=0", null, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET, "/api/v1/devices/" + deviceId + "/monitor?window=1000", null, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
