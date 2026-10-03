package com.example.smarthome.dto;

/** Payload topic sh/{id}/register (mục 2). */
public record RegisterPayload(
        String deviceId,
        String type,
        String fw,
        String regToken) {
}
