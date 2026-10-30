package com.netmon.ping;

import com.netmon.config.NetmonProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Executes one operation: claim, probe with timeout, then record success / retry / failure. */
@Component
public class PingWorker {

    private static final Logger log = LoggerFactory.getLogger(PingWorker.class);

    private final PingOperationStore store;
    private final PingProbe probe;
    private final ExecutorService probeExecutor;
    private final NetmonProperties props;
    private final Clock clock;

    public PingWorker(PingOperationStore store, PingProbe probe,
                      @Qualifier("pingProbeExecutor") ExecutorService probeExecutor,
                      NetmonProperties props, Clock clock) {
        this.store = store;
        this.probe = probe;
        this.probeExecutor = probeExecutor;
        this.props = props;
        this.clock = clock;
    }

    public void process(UUID operationId) {
        Optional<ClaimedOperation> claimed = store.claim(operationId);
        if (claimed.isEmpty()) {
            log.debug("Operation {} not claimable (already running, finished or not due)", operationId);
            return;
        }
        ClaimedOperation op = claimed.get();
        try {
            PingResult result = executeWithTimeout(op.ipAddress());
            store.succeed(op, result);
        } catch (TimeoutException e) {
            onFailure(op, PingStatus.TIMED_OUT,
                    "Ping timed out after " + props.getPing().getTimeout().toMillis() + " ms");
        } catch (PingProbeException e) {
            onFailure(op, PingStatus.FAILED, e.getMessage());
        } catch (InterruptedException e) {
            // Application shutting down: leave it RUNNING; lease expiry / startup recovery will resolve it.
            Thread.currentThread().interrupt();
            log.warn("Worker interrupted while processing {}; recovery will handle it", operationId);
        } catch (RuntimeException e) {
            log.error("Unexpected error while processing operation {}", operationId, e);
            onFailure(op, PingStatus.FAILED, "Unexpected error: " + e.getClass().getSimpleName());
        }
    }

    private PingResult executeWithTimeout(String ipAddress)
            throws PingProbeException, TimeoutException, InterruptedException {
        Future<PingResult> future = probeExecutor.submit(() -> probe.ping(ipAddress));
        try {
            PingResult result = future.get(props.getPing().getTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (result == null) {
                throw new PingProbeException("Probe returned no result");
            }
            return result;
        } catch (TimeoutException | InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof PingProbeException probeException) {
                throw probeException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new PingProbeException("Probe failed: " + cause, cause);
        }
    }

    private void onFailure(ClaimedOperation op, PingStatus terminalStatus, String message) {
        if (op.attempt() < op.maxAttempts()) {
            Duration backoff = backoff(op.attempt());
            log.info("Operation {} attempt {}/{} failed ({}); retrying in {} ms",
                    op.id(), op.attempt(), op.maxAttempts(), message, backoff.toMillis());
            store.retryLater(op, message, clock.instant().plus(backoff));
        } else {
            log.warn("Operation {} failed permanently after {} attempts: {}", op.id(), op.attempt(), message);
            store.fail(op, terminalStatus, message);
        }
    }

    /** Exponential backoff: base * 2^(attempt-1), capped. */
    Duration backoff(int attempt) {
        long factor = 1L << Math.min(Math.max(attempt - 1, 0), 10);
        long millis = props.getPing().getRetryBackoff().toMillis() * factor;
        return Duration.ofMillis(Math.min(millis, props.getPing().getMaxRetryBackoff().toMillis()));
    }
}
