package com.network.device;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    boolean existsByIpAddress(String ipAddress);

    boolean existsByIpAddressAndIdNot(String ipAddress, Long id);

    List<Device> findByMonitoringEnabledTrue();


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Device d where d.id = :id")
    Optional<Device> findByIdForUpdate(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Device d set d.lastStatus = :status, d.lastCheckedAt = :at "
            + "where d.id = :id and (d.lastCheckedAt is null or d.lastCheckedAt <= :at)")
    int updateStatus(@Param("id") Long id, @Param("status") ConnectionStatus status, @Param("at") Instant at);
}
