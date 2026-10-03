package com.example.smarthome.dto;

import java.time.OffsetDateTime;

import com.example.smarthome.entity.Telemetry;

/** Một bản ghi telemetry trả về API (mục 9). */
public record TelemetryResponse(
        String deviceId,
        Float temp,
        Float hum,
        Boolean relay1,
        Boolean relay2,
        OffsetDateTime recordedAt) {

    public static TelemetryResponse from(Telemetry t) {
        return new TelemetryResponse(t.getDeviceId(), t.getTemp(), t.getHum(),
                t.getRelay1(), t.getRelay2(), t.getRecordedAt());
    }
}
