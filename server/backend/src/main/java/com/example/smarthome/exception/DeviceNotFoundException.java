package com.example.smarthome.exception;

/**
 * Thiết bị không tồn tại theo deviceId - HTTP 404.
 * Kế thừa IllegalArgumentException để các unit test cũ (ném IllegalArgumentException) vẫn đúng.
 */
public class DeviceNotFoundException extends IllegalArgumentException {

    public DeviceNotFoundException(String deviceId) {
        super("Không thấy thiết bị: " + deviceId);
    }
}
