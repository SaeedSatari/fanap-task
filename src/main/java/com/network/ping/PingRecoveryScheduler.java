package com.network.ping;

import com.network.config.NetworkProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Guarantees no operation stays in an undefined state:
 * <ul>
 *   <li>at startup, anything still RUNNING belongs to a dead process and is re-queued / failed;</li>
 *   <li>periodically, RUNNING operations with an expired lease are recovered (crashed worker);</li>
 *   <li>periodically, PENDING operations that are due (new, retry, recovered) are dispatched.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "network.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PingRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PingRecoveryScheduler.class);

    private final PingOperationStore store;
    private final PingOperationRepository operations;
    private final PingDispatcher dispatcher;
    private final NetworkProperties props;
    private final Clock clock;

    public PingRecoveryScheduler(PingOperationStore store, PingOperationRepository operations,
                                 PingDispatcher dispatcher, NetworkProperties props, Clock clock) {
        this.store = store;
        this.operations = operations;
        this.dispatcher = dispatcher;
        this.props = props;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            if (props.getRecovery().isRequeueRunningOnStartup()) {
                int recovered = store.recoverStaleOperations(clock.instant().plus(Duration.ofDays(365)));
                if (recovered > 0) {
                    log.warn("Startup recovery resolved {} operation(s) left RUNNING by the previous process", recovered);
                }
            }
            dispatchDue();
        } catch (RuntimeException e) {
            log.error("Startup recovery failed", e);
        }
    }

    @Scheduled(fixedDelayString = "${network.recovery.sweep-interval-ms:1000}")
    public void sweep() {
        try {
            Instant now = clock.instant();
            store.recoverStaleOperations(now);
            dispatchDue();
        } catch (RuntimeException e) {
            log.error("Recovery sweep failed", e);
        }
    }

    private void dispatchDue() {
        List<UUID> due = operations.findDispatchableIds(clock.instant(),
                PageRequest.of(0, Math.max(1, props.getRecovery().getDispatchBatchSize())));
        due.forEach(dispatcher::dispatch);
    }
}
