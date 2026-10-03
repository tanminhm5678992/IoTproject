package com.example.smarthome.dto;

import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Tiện ích nhỏ cho payload MQTT: kiểm tra rỗng, đọc JSON, cắt bớt khi ghi log. */
public final class Payloads {

    private static final int MAX_PREVIEW = 200;

    private Payloads() {
    }

    /** Payload null / rỗng / chỉ khoảng trắng. */
    public static boolean isBlank(String payload) {
        return payload == null || payload.isBlank();
    }

    /**
     * Đọc JSON thành DTO. Trả về empty khi payload rỗng hoặc JSON hỏng
     * (service chỉ ghi log WARN rồi bỏ qua, không làm sập luồng nhận).
     */
    public static <T> Optional<T> parse(ObjectMapper objectMapper, String payload, Class<T> type) {
        if (isBlank(payload)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(payload, type));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /** Cắt payload dài để log không bị ngập; payload lỗi vẫn xem được phần đầu. */
    public static String preview(String payload) {
        if (payload == null) {
            return "<null>";
        }
        String trimmed = payload.trim();
        return trimmed.length() <= MAX_PREVIEW ? trimmed : trimmed.substring(0, MAX_PREVIEW) + "...";
    }
}
