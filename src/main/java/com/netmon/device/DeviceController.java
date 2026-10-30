package com.netmon.device;

import com.netmon.common.PageResponse;
import com.netmon.common.Pagination;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceService service;

    public DeviceController(DeviceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<DeviceResponse> create(@Valid @RequestBody CreateDeviceRequest request) {
        DeviceResponse created = DeviceResponse.from(service.create(request));
        return ResponseEntity.created(URI.create("/api/v1/devices/" + created.id())).body(created);
    }

    @GetMapping
    public PageResponse<DeviceResponse> list(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        Pagination.validate(page, size);
        return PageResponse.from(service.list(page, size).map(DeviceResponse::from));
    }

    @GetMapping("/{id}")
    public DeviceResponse get(@PathVariable Long id) {
        return DeviceResponse.from(service.get(id));
    }

    @PutMapping("/{id}")
    public DeviceResponse update(@PathVariable Long id, @Valid @RequestBody UpdateDeviceRequest request) {
        return DeviceResponse.from(service.update(id, request));
    }

    @PutMapping("/{id}/monitoring")
    public DeviceResponse setMonitoring(@PathVariable Long id, @Valid @RequestBody MonitoringRequest request) {
        return DeviceResponse.from(service.setMonitoring(id, request.enabled()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
