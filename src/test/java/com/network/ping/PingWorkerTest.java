package com.network.ping;

import com.network.config.NetworkProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PingWorkerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    PingOperationStore store;
    @Mock
    PingProbe probe;

    final ExecutorService probeExecutor = Executors.newCachedThreadPool();
    final NetworkProperties props = new NetworkProperties();
    final UUID id = UUID.randomUUID();
    PingWorker worker;

    @BeforeEach
    void setUp() {
        props.getPing().setTimeout(Duration.ofMillis(200));
        props.getPing().setRetryBackoff(Duration.ofSeconds(1));
        props.getPing().setMaxRetryBackoff(Duration.ofSeconds(30));
        worker = new PingWorker(store, probe, probeExecutor, props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        probeExecutor.shutdownNow();
    }

    private ClaimedOperation claimed(int attempt) {
        return new ClaimedOperation(id, 1L, "10.0.0.1", attempt, 3);
    }

    @Test
    void successfulProbe_isRecordedAsSucceeded() throws Exception {
        ClaimedOperation op = claimed(1);
        when(store.claim(id)).thenReturn(Optional.of(op));
        when(probe.ping("10.0.0.1")).thenReturn(new PingResult(true, 42L));

        worker.process(id);

        verify(store).succeed(op, new PingResult(true, 42L));
        verify(store, never()).retryLater(any(), any(), any());
        verify(store, never()).fail(any(), any(), any());
    }

    @Test
    void unreachableDevice_isStillASuccessfulOperation() throws Exception {
        ClaimedOperation op = claimed(1);
        when(store.claim(id)).thenReturn(Optional.of(op));
        when(probe.ping("10.0.0.1")).thenReturn(new PingResult(false, null));

        worker.process(id);

        verify(store).succeed(op, new PingResult(false, null));
    }

    @Test
    void operationClaimedByAnotherWorker_isNotExecuted() {
        when(store.claim(id)).thenReturn(Optional.empty());

        worker.process(id);

        verifyNoInteractions(probe);
        verify(store, never()).succeed(any(), any());
    }

    @Test
    void probeError_withAttemptsLeft_isRetriedWithExponentialBackoff() throws Exception {
        ClaimedOperation op = claimed(2);
        when(store.claim(id)).thenReturn(Optional.of(op));
        when(probe.ping(any())).thenThrow(new PingProbeException("boom"));

        worker.process(id);

        // attempt 2 -> backoff = 1s * 2^(2-1) = 2s
        verify(store).retryLater(op, "boom", NOW.plusSeconds(2));
        verify(store, never()).fail(any(), any(), any());
    }

    @Test
    void probeError_onLastAttempt_failsTheOperation() throws Exception {
        ClaimedOperation op = claimed(3);
        when(store.claim(id)).thenReturn(Optional.of(op));
        when(probe.ping(any())).thenThrow(new PingProbeException("boom"));

        worker.process(id);

        verify(store).fail(op, PingStatus.FAILED, "boom");
        verify(store, never()).retryLater(any(), any(), any());
    }

    @Test
    void slowProbe_timesOut_andIsRetriedWhenAttemptsRemain() throws Exception {
        ClaimedOperation op = claimed(1);
        when(store.claim(id)).thenReturn(Optional.of(op));
        when(probe.ping(any())).thenAnswer(inv -> {
            Thread.sleep(5_000);
            return new PingResult(true, 1L);
        });

        worker.process(id);

        verify(store).retryLater(eq(op), eq("Ping timed out after 200 ms"), eq(NOW.plusSeconds(1)));
        verify(store, never()).succeed(any(), any());
    }

    @Test
    void slowProbe_onLastAttempt_endsAsTimedOut() throws Exception {
        ClaimedOperation op = claimed(3);
        when(store.claim(id)).thenReturn(Optional.of(op));
        when(probe.ping(any())).thenAnswer(inv -> {
            Thread.sleep(5_000);
            return new PingResult(true, 1L);
        });

        worker.process(id);

        verify(store).fail(op, PingStatus.TIMED_OUT, "Ping timed out after 200 ms");
    }

    @Test
    void unexpectedRuntimeError_isTreatedAsAFailedAttempt() throws Exception {
        ClaimedOperation op = claimed(3);
        when(store.claim(id)).thenReturn(Optional.of(op));
        when(probe.ping(any())).thenThrow(new IllegalStateException("bug"));

        worker.process(id);

        verify(store).fail(op, PingStatus.FAILED, "Unexpected error: IllegalStateException");
    }

    @Test
    void backoff_isExponentialAndCapped() {
        props.getPing().setRetryBackoff(Duration.ofSeconds(1));
        props.getPing().setMaxRetryBackoff(Duration.ofSeconds(10));

        assertThat(worker.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(worker.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(worker.backoff(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(worker.backoff(10)).isEqualTo(Duration.ofSeconds(10));
    }
}
