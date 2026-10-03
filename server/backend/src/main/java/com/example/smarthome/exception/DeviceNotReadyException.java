package com.example.smarthome.exception;

/**
 * Thiết bị chưa ACTIVE hoặc đang offline nên không gửi lệnh được - HTTP 422
 * (đã chốt với người dùng: gửi lệnh yêu cầu thiết bị ACTIVE và online, nếu không thì 422).
 * Kế thừa IllegalStateException để unit test cũ (ném IllegalStateException) vẫn đúng.
 */
public class DeviceNotReadyException extends IllegalStateException {

    public DeviceNotReadyException(String message) {
        super(message);
    }
}
