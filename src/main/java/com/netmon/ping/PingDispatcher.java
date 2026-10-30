package com.netmon.ping;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Hands operation ids to the bounded worker pool. Dispatching is only an optimisation: the database is
 * the source of truth and the recovery sweeper re-dispatches anything that is still PENDING and due.
 * Double dispatch is harmless because {@link PingOperationStore#claim} is an atomic compare-and-set.
 */
@Component
public class PingDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PingDispatcher.class);

    private final PingWorker worker;
    private final ExecutorService executor;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public PingDispatcher(PingWorker worker, @Qualifier("pingWorkerExecutor") ExecutorService executor) {
        this.worker = worker;
        this.executor = executor;
    }

    /** Dispatch once the surrounding transaction commits (so the worker can see the row). */
    public void dispatchAfterCommit(UUID operationId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(operationId);
                }
            });
        } else {
            dispatch(operationId);
        }
    }

    public void dispatch(UUID operationId) {
        if (!inFlight.add(operationId)) {
            return; // already queued or running in this instance
        }
        try {
            executor.execute(() -> {
                try {
                    worker.process(operationId);
                } catch (Throwable t) {
                    log.error("Worker crashed for operation {}", operationId, t);
                } finally {
                    inFlight.remove(operationId);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(operationId);
            log.warn("Worker queue full; operation {} stays PENDING and will be retried by the sweeper", operationId);
        }
    }
}
