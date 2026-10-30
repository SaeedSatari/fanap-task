package com.network.ping;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PingOperationRepository extends JpaRepository<PingOperation, UUID> {

    Optional<PingOperation> findByDeviceIdAndIdempotencyKey(Long deviceId, String idempotencyKey);

    boolean existsByDeviceIdAndStatusIn(Long deviceId, Collection<PingStatus> statuses);

    List<PingOperation> findByDeviceIdOrderByCreatedAtDesc(Long deviceId, Pageable pageable);

    List<PingOperation> findByStatusAndLeaseExpiresAtBefore(PingStatus status, Instant cutoff);

    @Query("select o.id from PingOperation o where o.status = com.network.ping.PingStatus.PENDING "
            + "and o.nextAttemptAt <= :now order by o.nextAttemptAt asc")
    List<UUID> findDispatchableIds(@Param("now") Instant now, Pageable pageable);

    /** PENDING -> RUNNING. Returns 1 only for the single caller that wins the race. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PingOperation o set o.status = com.network.ping.PingStatus.RUNNING, "
            + "o.attempts = o.attempts + 1, o.startedAt = :now, o.leaseExpiresAt = :lease "
            + "where o.id = :id and o.status = com.network.ping.PingStatus.PENDING and o.nextAttemptAt <= :now")
    int claim(@Param("id") UUID id, @Param("now") Instant now, @Param("lease") Instant lease);

    /**
     * RUNNING -> terminal state. Guarded by the attempt number so that a worker whose lease was
     * already taken over (recovery re-queued the operation) cannot overwrite the newer attempt.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PingOperation o set o.status = :newStatus, o.reachable = :reachable, "
            + "o.responseTimeMs = :responseTimeMs, o.errorMessage = :error, o.finishedAt = :now, "
            + "o.leaseExpiresAt = null "
            + "where o.id = :id and o.status = com.network.ping.PingStatus.RUNNING and o.attempts = :attempt")
    int finish(@Param("id") UUID id, @Param("attempt") int attempt, @Param("newStatus") PingStatus newStatus,
               @Param("reachable") Boolean reachable, @Param("responseTimeMs") Long responseTimeMs,
               @Param("error") String error, @Param("now") Instant now);

    /** RUNNING -> PENDING (retry after backoff), same attempt guard as {@link #finish}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PingOperation o set o.status = com.network.ping.PingStatus.PENDING, "
            + "o.nextAttemptAt = :next, o.errorMessage = :error, o.leaseExpiresAt = null "
            + "where o.id = :id and o.status = com.network.ping.PingStatus.RUNNING and o.attempts = :attempt")
    int requeue(@Param("id") UUID id, @Param("attempt") int attempt, @Param("next") Instant next,
                @Param("error") String error);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PingOperation o where o.deviceId = :deviceId")
    void deleteAllByDeviceId(@Param("deviceId") Long deviceId);
}
