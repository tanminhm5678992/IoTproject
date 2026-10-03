package com.example.smarthome.dto;

import java.util.Map;

/**
 * Response của POST /api/devices: regToken backend cấp và các topic MQTT
 * mà thiết bị dùng (để nạp vào firmware + add-device.sh).
 */
public record DeviceRegisterResponse(String deviceId, String regToken, Map<String, String> mqttTopics) {

    /** Các topic của thiết bị theo bảng topic (mục 2). */
    public static Map<String, String> topics(String deviceId) {
        String prefix = "sh/" + deviceId;
        return Map.of(
                "register", prefix + "/register",
                "telemetry", prefix + "/telemetry",
                "status", prefix + "/status",
                "ack", prefix + "/ack",
                "cmd", prefix + "/cmd");
    }
}
