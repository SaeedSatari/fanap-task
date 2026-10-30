package com.network.ping;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

@Component
@Slf4j
public class PingDispatcher {


    private final PingWorker worker;
    private final ExecutorService executor;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public PingDispatcher(PingWorker worker, @Qualifier("pingWorkerExecutor") ExecutorService executor) {
        this.worker = worker;
        this.executor = executor;
    }

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
            return;
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
