package com.example.smarthome.dto;

/** Payload topic sh/{id}/cmd (mục 2): backend gửi lệnh xuống thiết bị. */
public record CommandPayload(
        String cmdId,
        String target,
        String value) {
}
