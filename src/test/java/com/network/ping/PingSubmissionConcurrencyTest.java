package com.network.ping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.network.common.ApiException;
import com.network.device.Device;
import com.network.device.DeviceRepository;
import com.network.device.DeviceType;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;

/** Duplicate and concurrent requests for the same device, with a probe the test can hold open. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "network.recovery.enabled=false",
        "network.monitoring.enabled=false",
        "network.ping.timeout=20s",
        "spring.datasource.hikari.maximum-pool-size=20",
        "spring.datasource.url=jdbc:h2:mem:concurrency-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
class PingSubmissionConcurrencyTest {

    /** Probe that blocks until the test opens the gate; counts how often it really ran. */
    static class GatedProbe implements PingProbe {
        private volatile CountDownLatch gate = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        void close() { gate = new CountDownLatch(1); calls.set(0); }
        void open() { gate.countDown(); }
        int calls() { return calls.get(); }

        @Override
        public PingResult ping(String ipAddress) throws PingProbeException {
            calls.incrementAndGet();
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PingProbeException("interrupted");
            }
            return new PingResult(true, 5L);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        GatedProbe gatedProbe() {
            return new GatedProbe();
        }
    }

    @Autowired GatedProbe probe;
    @Autowired PingSubmissionService submission;
    @Autowired PingOperationRepository operations;
    @Autowired DeviceRepository devices;
    @Autowired Clock clock;

    @BeforeEach
    void setUp() {
        probe.open(); // let leftovers from a previous test finish
        await().atMost(Duration.ofSeconds(10)).until(() ->
                operations.findAll().stream().noneMatch(o -> o.getStatus().ordinal() <= PingStatus.RUNNING.ordinal()));
        operations.deleteAll();
        devices.deleteAll();
        probe.close();
    }

    private Device newDevice(String ip, boolean monitoring) {
        return devices.save(new Device("d-" + ip, ip, DeviceType.MODEM, monitoring, clock.instant()));
    }

    private List<Object> runConcurrently(int threads, Callable<Object> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return task.call();
                } catch (ApiException e) {
                    return e;
                }
            }));
        }
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }

    private void awaitStatus(UUID id, PingStatus expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(operations.findById(id).orElseThrow().getStatus()).isEqualTo(expected));
    }

    @Test
    void concurrentRequests_forOneDevice_onlyOneOperationRuns() throws Exception {
        Device device = newDevice("10.20.0.1", true);

        List<Object> results = runConcurrently(8, () -> submission.submit(device.getId(), null));

        List<SubmissionResult> accepted = results.stream()
                .filter(SubmissionResult.class::isInstance).map(SubmissionResult.class::cast).toList();
        List<ApiException> rejected = results.stream()
                .filter(ApiException.class::isInstance).map(ApiException.class::cast).toList();
        assertThat(accepted).hasSize(1);
        assertThat(rejected).hasSize(7).allSatisfy(e -> {
            assertThat(e.getStatus().value()).isEqualTo(409);
            assertThat(e.getCode()).isEqualTo("ACTIVE_OPERATION_EXISTS");
        });
        assertThat(operations.findByDeviceIdOrderByCreatedAtDesc(device.getId(), PageRequest.of(0, 50))).hasSize(1);

        probe.open();
        awaitStatus(accepted.get(0).operation().getId(), PingStatus.SUCCEEDED);
        assertThat(probe.calls()).isEqualTo(1);
    }

    @Test
    void concurrentRequests_withTheSameIdempotencyKey_shareOneOperation() throws Exception {
        Device device = newDevice("10.20.0.2", true);

        List<Object> results = runConcurrently(8, () -> submission.submit(device.getId(), "key-1"));

        List<SubmissionResult> all = results.stream().map(SubmissionResult.class::cast).toList();
        Set<UUID> ids = new HashSet<>();
        all.forEach(r -> ids.add(r.operation().getId()));
        assertThat(ids).hasSize(1);
        assertThat(all.stream().filter(SubmissionResult::created).count()).isEqualTo(1);
        assertThat(operations.findByDeviceIdOrderByCreatedAtDesc(device.getId(), PageRequest.of(0, 50))).hasSize(1);

        probe.open();
        awaitStatus(ids.iterator().next(), PingStatus.SUCCEEDED);
        assertThat(probe.calls()).isEqualTo(1);
    }

    @Test
    void repeatingAKeyAfterCompletion_replaysTheOriginal_withoutPingingAgain() {
        Device device = newDevice("10.20.0.3", true);
        probe.open();

        SubmissionResult first = submission.submit(device.getId(), "key-2");
        awaitStatus(first.operation().getId(), PingStatus.SUCCEEDED);
        SubmissionResult replay = submission.submit(device.getId(), "key-2");

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.operation().getId()).isEqualTo(first.operation().getId());
        assertThat(replay.operation().getStatus()).isEqualTo(PingStatus.SUCCEEDED);
        assertThat(probe.calls()).isEqualTo(1);
    }

    @Test
    void differentDevices_doNotBlockEachOther() throws Exception {
        Device a = newDevice("10.20.0.4", true);
        Device b = newDevice("10.20.0.5", true);

        SubmissionResult first = submission.submit(a.getId(), null);
        SubmissionResult second = submission.submit(b.getId(), null);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isTrue();
        probe.open();
        awaitStatus(first.operation().getId(), PingStatus.SUCCEEDED);
        awaitStatus(second.operation().getId(), PingStatus.SUCCEEDED);
    }

    @Test
    void disabledMonitoring_isRejectedWith422() {
        Device device = newDevice("10.20.0.6", false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> submission.submit(device.getId(), null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("MONITORING_DISABLED");
                });
    }

    @Test
    void unknownDevice_isRejectedWith404() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> submission.submit(987654L, null))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.getCode()).isEqualTo("DEVICE_NOT_FOUND"));
    }
}
