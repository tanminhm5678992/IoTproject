package com.example.smarthome.dto;

import java.time.OffsetDateTime;

import com.example.smarthome.entity.Device;

/** Response của GET /api/devices và GET /api/devices/{deviceId} (kèm telemetry mới nhất). */
public record DeviceResponse(
        String deviceId,
        String name,
        String type,
        String regStatus,
        boolean online,
        String fwVersion,
        OffsetDateTime lastSeen,
        OffsetDateTime createdAt,
        TelemetryResponse latestTelemetry) {

    public static DeviceResponse from(Device device, TelemetryResponse latest) {
        return new DeviceResponse(
                device.getDeviceId(), device.getName(), device.getType(),
                device.getRegStatus(), device.isOnline(), device.getFwVersion(),
                device.getLastSeen(), device.getCreatedAt(), latest);
    }
}
