package com.network.device;

import com.network.common.ApiException;
import com.network.ping.PingOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    DeviceRepository devices;
    @Mock
    PingOperationRepository operations;

    DeviceService service;

    @BeforeEach
    void setUp() {
        service = new DeviceService(devices, operations, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Device existing() {
        Device device = new Device("Modem", "10.0.0.1", DeviceType.MODEM, true, NOW);
        ReflectionTestUtils.setField(device, "id", 1L);
        return device;
    }

    @Test
    void create_trimsInput_andDefaultsMonitoringToEnabled() {
        when(devices.existsByIpAddress("10.0.0.1")).thenReturn(false);
        when(devices.saveAndFlush(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));

        Device created = service.create(new CreateDeviceRequest("  Modem-1 ", " 10.0.0.1 ", DeviceType.MODEM, null));

        assertThat(created.getName()).isEqualTo("Modem-1");
        assertThat(created.getIpAddress()).isEqualTo("10.0.0.1");
        assertThat(created.isMonitoringEnabled()).isTrue();
        assertThat(created.getLastStatus()).isEqualTo(ConnectionStatus.UNKNOWN);
        assertThat(created.getLastCheckedAt()).isNull();
    }

    @Test
    void create_withDuplicateIp_isRejectedWithConflict() {
        when(devices.existsByIpAddress("10.0.0.1")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateDeviceRequest("A", "10.0.0.1", DeviceType.RADIO, true)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("DUPLICATE_IP");
                });
        verify(devices, never()).saveAndFlush(any());
    }

    @Test
    void get_unknownDevice_isNotFound() {
        when(devices.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(7L))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(404);
                    assertThat(e.getCode()).isEqualTo("DEVICE_NOT_FOUND");
                });
    }

    @Test
    void update_toIpOfAnotherDevice_isRejected() {
        when(devices.findById(1L)).thenReturn(Optional.of(existing()));
        when(devices.existsByIpAddressAndIdNot("10.0.0.2", 1L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(1L, new UpdateDeviceRequest("X", "10.0.0.2", DeviceType.MODEM)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE_IP"));
    }

    @Test
    void update_changingIp_resetsConnectivityKnowledge() {
        Device device = existing();
        ReflectionTestUtils.setField(device, "lastStatus", ConnectionStatus.UP);
        ReflectionTestUtils.setField(device, "lastCheckedAt", NOW);
        when(devices.findById(1L)).thenReturn(Optional.of(device));
        when(devices.existsByIpAddressAndIdNot("10.0.0.9", 1L)).thenReturn(false);
        when(devices.saveAndFlush(device)).thenReturn(device);

        Device updated = service.update(1L, new UpdateDeviceRequest("New", "10.0.0.9", DeviceType.RADIO));

        assertThat(updated.getIpAddress()).isEqualTo("10.0.0.9");
        assertThat(updated.getType()).isEqualTo(DeviceType.RADIO);
        assertThat(updated.getLastStatus()).isEqualTo(ConnectionStatus.UNKNOWN);
        assertThat(updated.getLastCheckedAt()).isNull();
    }

    @Test
    void setMonitoring_togglesFlag() {
        Device device = existing();
        when(devices.findById(1L)).thenReturn(Optional.of(device));
        when(devices.save(device)).thenReturn(device);

        assertThat(service.setMonitoring(1L, false).isMonitoringEnabled()).isFalse();
        assertThat(service.setMonitoring(1L, true).isMonitoringEnabled()).isTrue();
    }

    @Test
    void delete_whileOperationIsActive_isRejected() {
        Device device = existing();
        when(devices.findByIdForUpdate(1L)).thenReturn(Optional.of(device));
        when(operations.existsByDeviceIdAndStatusIn(eq(1L), any())).thenReturn(true);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("ACTIVE_OPERATION_EXISTS");
                });
        verify(devices, never()).delete(any());
    }

    @Test
    void delete_removesHistoryAndDevice() {
        Device device = existing();
        when(devices.findByIdForUpdate(1L)).thenReturn(Optional.of(device));
        when(operations.existsByDeviceIdAndStatusIn(eq(1L), any())).thenReturn(false);

        service.delete(1L);

        verify(operations).deleteAllByDeviceId(1L);
        verify(devices).delete(device);
    }
}
