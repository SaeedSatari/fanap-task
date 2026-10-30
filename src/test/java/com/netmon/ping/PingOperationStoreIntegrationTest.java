package com.netmon.ping;

import static org.assertj.core.api.Assertions.assertThat;

import com.netmon.device.ConnectionStatus;
import com.netmon.device.Device;
import com.netmon.device.DeviceRepository;
import com.netmon.device.DeviceType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** State transitions, persistence of results, and recovery after a (simulated) restart. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "netmon.recovery.enabled=false",
        "netmon.monitoring.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:store-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
class PingOperationStoreIntegrationTest {

    @Autowired DeviceRepository devices;
    @Autowired PingOperationRepository operations;
    @Autowired PingOperationStore store;
    @Autowired Clock clock;

    Device device;

    @BeforeEach
    void setUp() {
        operations.deleteAll();
        devices.deleteAll();
        device = devices.save(new Device("modem", "10.1.1.1", DeviceType.MODEM, true, clock.instant()));
    }

    private PingOperation newOperation(int maxAttempts) {
        return operations.saveAndFlush(new PingOperation(device.getId(), null, maxAttempts, clock.instant()));
    }

    private PingOperation reload(UUID id) {
        return operations.findById(id).orElseThrow();
    }

    /** Claim the operation, failing and re-queueing it until it is RUNNING on the given attempt number. */
    private ClaimedOperation driveToRunning(UUID id, int attempt) {
        ClaimedOperation claimed = null;
        for (int i = 1; i <= attempt; i++) {
            claimed = store.claim(id).orElseThrow();
            if (i < attempt) {
                store.retryLater(claimed, "retry", clock.instant());
            }
        }
        return claimed;
    }

    @Test
    void claim_movesToRunning_withLeaseAndAttemptCounter() {
        PingOperation op = newOperation(3);

        ClaimedOperation claimed = store.claim(op.getId()).orElseThrow();

        assertThat(claimed.attempt()).isEqualTo(1);
        assertThat(claimed.ipAddress()).isEqualTo("10.1.1.1");
        PingOperation reloaded = reload(op.getId());
        assertThat(reloaded.getStatus()).isEqualTo(PingStatus.RUNNING);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(reloaded.getLeaseExpiresAt()).isAfter(clock.instant());
    }

    @Test
    void claim_isExclusive_evenUnderConcurrency() throws Exception {
        PingOperation op = newOperation(3);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<ClaimedOperation>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return store.claim(op.getId());
            }));
        }
        start.countDown();
        int winners = 0;
        for (Future<Optional<ClaimedOperation>> future : futures) {
            if (future.get(20, TimeUnit.SECONDS).isPresent()) {
                winners++;
            }
        }
        pool.shutdownNow();

        assertThat(winners).isEqualTo(1);
        assertThat(reload(op.getId()).getAttempts()).isEqualTo(1);
    }

    @Test
    void succeed_storesResult_andUpdatesDeviceStatus() {
        PingOperation op = newOperation(3);
        ClaimedOperation claimed = store.claim(op.getId()).orElseThrow();

        assertThat(store.succeed(claimed, new PingResult(true, 37L))).isTrue();

        PingOperation reloaded = reload(op.getId());
        assertThat(reloaded.getStatus()).isEqualTo(PingStatus.SUCCEEDED);
        assertThat(reloaded.getReachable()).isTrue();
        assertThat(reloaded.getResponseTimeMs()).isEqualTo(37L);
        assertThat(reloaded.getStartedAt()).isNotNull();
        assertThat(reloaded.getFinishedAt()).isNotNull();
        assertThat(reloaded.getLeaseExpiresAt()).isNull();
        Device updated = devices.findById(device.getId()).orElseThrow();
        assertThat(updated.getLastStatus()).isEqualTo(ConnectionStatus.UP);
        assertThat(updated.getLastCheckedAt()).isNotNull();
    }

    @Test
    void succeed_withUnreachableResult_marksDeviceDown() {
        PingOperation op = newOperation(3);
        ClaimedOperation claimed = store.claim(op.getId()).orElseThrow();

        store.succeed(claimed, new PingResult(false, null));

        assertThat(reload(op.getId()).getStatus()).isEqualTo(PingStatus.SUCCEEDED);
        assertThat(reload(op.getId()).getReachable()).isFalse();
        assertThat(devices.findById(device.getId()).orElseThrow().getLastStatus()).isEqualTo(ConnectionStatus.DOWN);
    }

    @Test
    void fail_marksOperationFailed_andDeviceUnknown() {
        PingOperation op = newOperation(1);
        ClaimedOperation claimed = store.claim(op.getId()).orElseThrow();

        assertThat(store.fail(claimed, PingStatus.TIMED_OUT, "Ping timed out after 3000 ms")).isTrue();

        PingOperation reloaded = reload(op.getId());
        assertThat(reloaded.getStatus()).isEqualTo(PingStatus.TIMED_OUT);
        assertThat(reloaded.getErrorMessage()).contains("timed out");
        assertThat(reloaded.getFinishedAt()).isNotNull();
        assertThat(devices.findById(device.getId()).orElseThrow().getLastStatus()).isEqualTo(ConnectionStatus.UNKNOWN);
    }

    @Test
    void retryLater_requeues_butIsNotClaimableBeforeBackoffElapses() {
        PingOperation op = newOperation(3);
        ClaimedOperation claimed = store.claim(op.getId()).orElseThrow();

        store.retryLater(claimed, "boom", clock.instant().plus(Duration.ofHours(1)));

        PingOperation reloaded = reload(op.getId());
        assertThat(reloaded.getStatus()).isEqualTo(PingStatus.PENDING);
        assertThat(reloaded.getErrorMessage()).isEqualTo("boom");
        assertThat(store.claim(op.getId())).isEmpty();
        assertThat(operations.findDispatchableIds(clock.instant(), org.springframework.data.domain.PageRequest.of(0, 10)))
                .doesNotContain(op.getId());
    }

    @Test
    void terminalOperation_cannotBeClaimedOrModifiedAgain() {
        PingOperation op = newOperation(3);
        ClaimedOperation claimed = store.claim(op.getId()).orElseThrow();
        store.succeed(claimed, new PingResult(true, 10L));

        assertThat(store.claim(op.getId())).isEmpty();
        assertThat(store.fail(claimed, PingStatus.FAILED, "late")).isFalse();
        assertThat(reload(op.getId()).getStatus()).isEqualTo(PingStatus.SUCCEEDED);
    }

    @Test
    void staleAttempt_cannotOverwriteANewerAttempt() {
        PingOperation op = newOperation(3);
        ClaimedOperation firstAttempt = store.claim(op.getId()).orElseThrow();
        store.retryLater(firstAttempt, "recovered elsewhere", clock.instant());
        ClaimedOperation secondAttempt = store.claim(op.getId()).orElseThrow();
        assertThat(secondAttempt.attempt()).isEqualTo(2);

        // the "zombie" first worker finally reports back: it must be ignored
        assertThat(store.succeed(firstAttempt, new PingResult(true, 1L))).isFalse();
        assertThat(reload(op.getId()).getStatus()).isEqualTo(PingStatus.RUNNING);

        assertThat(store.succeed(secondAttempt, new PingResult(true, 2L))).isTrue();
        assertThat(reload(op.getId()).getResponseTimeMs()).isEqualTo(2L);
    }

    @Test
    void recovery_afterRestart_requeuesOrphanedOperations_andFailsExhaustedOnes() {
        PingOperation retryable = newOperation(3);
        driveToRunning(retryable.getId(), 1);                  // attempt 1 of 3, then "process dies"

        Device other = devices.save(new Device("radio", "10.1.1.2", DeviceType.RADIO, true, clock.instant()));
        PingOperation exhausted = operations.saveAndFlush(
                new PingOperation(other.getId(), null, 2, clock.instant()));
        driveToRunning(exhausted.getId(), 2);                  // attempt 2 of 2, then "process dies"

        // On startup every RUNNING operation is orphaned (cutoff far in the future)
        int recovered = store.recoverStaleOperations(clock.instant().plus(Duration.ofDays(1)));

        assertThat(recovered).isEqualTo(2);
        PingOperation requeued = reload(retryable.getId());
        assertThat(requeued.getStatus()).isEqualTo(PingStatus.PENDING);
        assertThat(requeued.getErrorMessage()).contains("Interrupted");
        assertThat(store.claim(retryable.getId())).isPresent();   // and it runs again as attempt 2

        PingOperation failed = reload(exhausted.getId());
        assertThat(failed.getStatus()).isEqualTo(PingStatus.FAILED);
        assertThat(failed.getErrorMessage()).contains("Interrupted");
        assertThat(devices.findById(other.getId()).orElseThrow().getLastStatus()).isEqualTo(ConnectionStatus.UNKNOWN);
    }

    @Test
    void recovery_leavesOperationsWithAValidLeaseAlone() {
        PingOperation op = newOperation(3);
        store.claim(op.getId()).orElseThrow();

        int recovered = store.recoverStaleOperations(clock.instant());

        assertThat(recovered).isZero();
        assertThat(reload(op.getId()).getStatus()).isEqualTo(PingStatus.RUNNING);
    }

    @Test
    void deviceStatus_isNeverOverwrittenByAnOlderResult() {
        Instant newer = clock.instant();
        devices.updateStatus(device.getId(), ConnectionStatus.UP, newer);

        int updated = devices.updateStatus(device.getId(), ConnectionStatus.DOWN, newer.minusSeconds(60));

        assertThat(updated).isZero();
        assertThat(devices.findById(device.getId()).orElseThrow().getLastStatus()).isEqualTo(ConnectionStatus.UP);
    }
}
