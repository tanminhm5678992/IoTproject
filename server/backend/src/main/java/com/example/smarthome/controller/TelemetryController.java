package com.example.smarthome.controller;

import java.time.OffsetDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.smarthome.dto.PageResponse;
import com.example.smarthome.dto.TelemetryResponse;
import com.example.smarthome.entity.Telemetry;
import com.example.smarthome.exception.TelemetryNotFoundException;
import com.example.smarthome.service.DeviceService;
import com.example.smarthome.service.TelemetryService;

/**
 * API telemetry (mục 9):
 *  GET /api/devices/{id}/telemetry?from=&to=&limit=&page=   lịch sử (mặc định 100 mới nhất)
 *  GET /api/devices/{id}/telemetry/latest                   bản ghi mới nhất
 */
@RestController
@RequestMapping("/api/devices/{deviceId}/telemetry")
public class TelemetryController {

    private final DeviceService deviceService;
    private final TelemetryService telemetryService;

    public TelemetryController(DeviceService deviceService, TelemetryService telemetryService) {
        this.deviceService = deviceService;
        this.telemetryService = telemetryService;
    }

    @GetMapping
    public PageResponse<TelemetryResponse> history(
            @PathVariable String deviceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int limit) {
        deviceService.requireDevice(deviceId);
        var result = telemetryService.search(deviceId, from, to, page, limit);
        return PageResponse.of(
                result.getContent().stream().map(TelemetryResponse::from).toList(),
                result.getTotalElements(), result.getNumber(), result.getSize());
    }

    @GetMapping("/latest")
    public TelemetryResponse latest(@PathVariable String deviceId) {
        deviceService.requireDevice(deviceId);
        Telemetry telemetry = telemetryService.latest(deviceId)
                .orElseThrow(() -> new TelemetryNotFoundException(deviceId));
        return TelemetryResponse.from(telemetry);
    }
}
