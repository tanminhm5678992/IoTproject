package com.example.smarthome.dto;

/**
 * Payload topic sh/{id}/telemetry (mục 2).
 * ts = epoch giây; thiết bị chưa có NTP có thể bỏ trống -> backend dùng giờ nhận.
 */
public record TelemetryPayload(
        Float temp,
        Float hum,
        Boolean relay1,
        Boolean relay2,
        Long ts) {
}
