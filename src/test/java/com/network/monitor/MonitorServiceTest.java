package com.network.monitor;

import com.network.device.Device;
import com.network.device.DeviceType;
import com.network.ping.PingOperationResponse;
import com.network.ping.PingStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MonitorServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final Device device = new Device("Modem", "10.0.0.1", DeviceType.MODEM, true, NOW);

    private PingOperationResponse op(PingStatus status, Boolean reachable, Long responseTime) {
        return new PingOperationResponse(UUID.randomUUID(), 1L, status, reachable, responseTime,
                1, 3, null, NOW, NOW, NOW, 10L);
    }

    @Test
    void availability_isBasedOnConclusiveProbesOnly() {
        List<PingOperationResponse> ops = List.of(
                op(PingStatus.SUCCEEDED, true, 20L),
                op(PingStatus.SUCCEEDED, true, 40L),
                op(PingStatus.SUCCEEDED, false, null),
                op(PingStatus.FAILED, null, null),
                op(PingStatus.TIMED_OUT, null, null),
                op(PingStatus.RUNNING, null, null),
                op(PingStatus.PENDING, null, null));

        MonitorSummaryResponse summary = MonitorService.buildSummary(device, ops, 50);

        assertThat(summary.totalOperations()).isEqualTo(7);
        assertThat(summary.reachableCount()).isEqualTo(2);
        assertThat(summary.unreachableCount()).isEqualTo(1);
        assertThat(summary.failedCount()).isEqualTo(2);
        assertThat(summary.inProgressCount()).isEqualTo(2);
        assertThat(summary.availabilityPercent()).isEqualTo(66.67);
        assertThat(summary.avgResponseTimeMs()).isEqualTo(30.0);
        assertThat(summary.minResponseTimeMs()).isEqualTo(20L);
        assertThat(summary.maxResponseTimeMs()).isEqualTo(40L);
    }

    @Test
    void noHistory_yieldsNullStatistics() {
        MonitorSummaryResponse summary = MonitorService.buildSummary(device, List.of(), 50);

        assertThat(summary.totalOperations()).isZero();
        assertThat(summary.availabilityPercent()).isNull();
        assertThat(summary.avgResponseTimeMs()).isNull();
        assertThat(summary.recentOperations()).isEmpty();
    }

    @Test
    void onlyFailures_doNotProduceAvailability() {
        MonitorSummaryResponse summary = MonitorService.buildSummary(device,
                List.of(op(PingStatus.FAILED, null, null), op(PingStatus.TIMED_OUT, null, null)), 50);

        assertThat(summary.availabilityPercent()).isNull();
        assertThat(summary.failedCount()).isEqualTo(2);
    }

    @Test
    void recentOperations_areCappedAtTen() {
        List<PingOperationResponse> ops = java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> op(PingStatus.SUCCEEDED, true, 10L)).toList();

        assertThat(MonitorService.buildSummary(device, ops, 50).recentOperations()).hasSize(10);
    }
}
