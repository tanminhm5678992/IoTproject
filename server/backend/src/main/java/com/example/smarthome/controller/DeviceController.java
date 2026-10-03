package com.example.smarthome.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.smarthome.dto.DeviceCreateRequest;
import com.example.smarthome.dto.DeviceRegisterResponse;
import com.example.smarthome.dto.DeviceResponse;
import com.example.smarthome.dto.TelemetryResponse;
import com.example.smarthome.entity.Device;
import com.example.smarthome.service.DeviceService;
import com.example.smarthome.service.TelemetryService;
import jakarta.validation.Valid;

/**
 * API thiết bị (mục 9):
 *  POST   /api/devices         tạo thiết bị, sinh regToken (PENDING)
 *  GET    /api/devices         danh sách + telemetry mới nhất
 *  GET    /api/devices/{id}    chi tiết
 *  DELETE /api/devices/{id}    xóa (cascade telemetry + lệnh)
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final TelemetryService telemetryService;

    public DeviceController(DeviceService deviceService, TelemetryService telemetryService) {
        this.deviceService = deviceService;
        this.telemetryService = telemetryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceRegisterResponse create(@Valid @RequestBody DeviceCreateRequest request) {
        Device device = deviceService.createDevice(request);
        return new DeviceRegisterResponse(
                device.getDeviceId(), device.getRegToken(),
                DeviceRegisterResponse.topics(device.getDeviceId()));
    }

    @GetMapping
    public List<DeviceResponse> list() {
        return deviceService.listDevices().stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{deviceId}")
    public DeviceResponse get(@PathVariable String deviceId) {
        return toResponse(deviceService.requireDevice(deviceId));
    }

    @DeleteMapping("/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String deviceId) {
        deviceService.deleteDevice(deviceId);
    }

    private DeviceResponse toResponse(Device device) {
        TelemetryResponse latest = telemetryService.latest(device.getDeviceId())
                .map(TelemetryResponse::from)
                .orElse(null);
        return DeviceResponse.from(device, latest);
    }
}
