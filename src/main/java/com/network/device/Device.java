package com.network.device;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "devices",
        uniqueConstraints = @UniqueConstraint(name = "uk_devices_ip", columnNames = "ip_address"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false, length = 20)
    private DeviceType type;

    @Column(name = "monitoring_enabled", nullable = false)
    private boolean monitoringEnabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_status", nullable = false, length = 20)
    private ConnectionStatus lastStatus = ConnectionStatus.UNKNOWN;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Device(String name, String ipAddress, DeviceType type, boolean monitoringEnabled, Instant now) {
        this.name = name;
        this.ipAddress = ipAddress;
        this.type = type;
        this.monitoringEnabled = monitoringEnabled;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateDetails(String name, String ipAddress, DeviceType type, Instant now) {
        if (!this.ipAddress.equals(ipAddress)) {
            this.lastStatus = ConnectionStatus.UNKNOWN;
            this.lastCheckedAt = null;
        }
        this.name = name;
        this.ipAddress = ipAddress;
        this.type = type;
        this.updatedAt = now;
    }

    public void setMonitoringEnabled(boolean enabled, Instant now) {
        this.monitoringEnabled = enabled;
        this.updatedAt = now;
    }
}
