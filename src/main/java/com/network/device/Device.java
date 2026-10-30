package com.network.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "devices",
        uniqueConstraints = @UniqueConstraint(name = "uk_devices_ip", columnNames = "ip_address"))
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

    protected Device() { }

    public Device(String name, String ipAddress, DeviceType type, boolean monitoringEnabled, Instant now) {
        this.name = name;
        this.ipAddress = ipAddress;
        this.type = type;
        this.monitoringEnabled = monitoringEnabled;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Changing the address invalidates what we knew about connectivity. */
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

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getIpAddress() { return ipAddress; }
    public DeviceType getType() { return type; }
    public boolean isMonitoringEnabled() { return monitoringEnabled; }
    public ConnectionStatus getLastStatus() { return lastStatus; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
