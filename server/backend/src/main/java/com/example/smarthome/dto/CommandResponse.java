package com.example.smarthome.dto;

import java.time.OffsetDateTime;

import com.example.smarthome.entity.Command;

/** Một lệnh điều khiển trả về API (PENDING/DONE/ERROR/TIMEOUT). */
public record CommandResponse(
        String cmdId,
        String deviceId,
        String target,
        String value,
        String status,
        String createdBy,
        OffsetDateTime sentAt,
        OffsetDateTime ackedAt) {

    public static CommandResponse from(Command c) {
        return new CommandResponse(
                c.getCmdId(), c.getDeviceId(), c.getTarget(), c.getValue(),
                c.getStatus(), c.getCreatedBy(), c.getSentAt(), c.getAckedAt());
    }
}
