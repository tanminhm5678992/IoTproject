package com.example.smarthome.exception;

/** Trùng deviceId khi tạo thiết bị - HTTP 409. */
public class DuplicateDeviceException extends IllegalArgumentException {

    public DuplicateDeviceException(String deviceId) {
        super("deviceId đã tồn tại: " + deviceId);
    }
}
