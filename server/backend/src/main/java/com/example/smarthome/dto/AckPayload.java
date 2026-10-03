package com.example.smarthome.dto;

/** Payload topic sh/{id}/ack (mục 2): result = ok | error. */
public record AckPayload(
        String cmdId,
        String result,
        String target,
        String value) {
}
