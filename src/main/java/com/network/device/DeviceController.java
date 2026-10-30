package com.network.device;

import com.network.common.ApiError;
import com.network.common.PageResponse;
import com.network.common.Pagination;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "Devices", description = "Network device management (modems and radios)")
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceService service;

    public DeviceController(DeviceService service) {
        this.service = service;
    }

    @Operation(summary = "Create a device")
    @ApiResponse(responseCode = "201", description = "Device created")
    @ApiResponse(responseCode = "400", description = "Validation error (details per field) or malformed body",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "A device with this IP address already exists",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping
    public ResponseEntity<DeviceResponse> create(@Valid @RequestBody CreateDeviceRequest request) {
        DeviceResponse created = DeviceResponse.from(service.create(request));
        return ResponseEntity.created(URI.create("/api/v1/devices/" + created.id())).body(created);
    }

    @Operation(summary = "List devices", description = "Paginated, ordered by id.")
    @ApiResponse(responseCode = "200", description = "Page of devices")
    @ApiResponse(responseCode = "400", description = "Invalid page or size",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping
    public PageResponse<DeviceResponse> list(
            @Parameter(description = "Zero-based page index", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (1-100)", example = "20")
            @RequestParam(defaultValue = "20") int size) {
        Pagination.validate(page, size);
        return PageResponse.from(service.list(page, size).map(DeviceResponse::from));
    }

    @Operation(summary = "Get a device",
            description = "Includes the last known connection status and the time of the last check.")
    @ApiResponse(responseCode = "200", description = "Device found")
    @ApiResponse(responseCode = "404", description = "Device not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{id}")
    public DeviceResponse get(@Parameter(description = "Device id", example = "1") @PathVariable Long id) {
        return DeviceResponse.from(service.get(id));
    }

    @Operation(summary = "Update a device",
            description = "Changing the IP address resets the last known status to UNKNOWN.")
    @ApiResponse(responseCode = "200", description = "Device updated")
    @ApiResponse(responseCode = "400", description = "Validation error or malformed body",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Device not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "IP address already used by another device",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PutMapping("/{id}")
    public DeviceResponse update(@Parameter(description = "Device id", example = "1") @PathVariable Long id,
                                 @Valid @RequestBody UpdateDeviceRequest request) {
        return DeviceResponse.from(service.update(id, request));
    }

    @Operation(summary = "Enable or disable monitoring of a device",
            description = "While disabled, no automatic pings are scheduled and manual pings are rejected (422).")
    @ApiResponse(responseCode = "200", description = "Monitoring flag updated")
    @ApiResponse(responseCode = "400", description = "Validation error or malformed body",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Device not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PutMapping("/{id}/monitoring")
    public DeviceResponse setMonitoring(@Parameter(description = "Device id", example = "1") @PathVariable Long id,
                                        @Valid @RequestBody MonitoringRequest request) {
        return DeviceResponse.from(service.setMonitoring(id, request.enabled()));
    }

    @Operation(summary = "Delete a device and its ping history")
    @ApiResponse(responseCode = "204", description = "Device deleted")
    @ApiResponse(responseCode = "404", description = "Device not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "A ping operation is still in progress for this device",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@Parameter(description = "Device id", example = "1") @PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}