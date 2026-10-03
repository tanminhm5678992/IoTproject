package com.example.smarthome.exception;

/** Chưa có bản ghi telemetry nào cho thiết bị - HTTP 404 (GET .../telemetry/latest). */
public class TelemetryNotFoundException extends IllegalArgumentException {

    public TelemetryNotFoundException(String deviceId) {
        super("Chưa có telemetry nào cho thiết bị: " + deviceId);
    }
}
