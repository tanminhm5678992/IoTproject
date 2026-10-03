package com.example.smarthome.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Body của POST /api/devices/{deviceId}/commands (target/value kiểm tra trong service).
 *
 * <p>Sau chuẩn hóa tại service: target về chữ thường, value về CHỮ HOA ("on" -&gt; "ON"),
 * vì firmware so sánh chính xác chuỗi (mục 7).
 */
public record CommandCreateRequest(
        @NotBlank(message = "target không được để trống")
        @Schema(description = "Thiết bị đích (chữ thường)", example = "relay1",
                allowableValues = {"led1", "led2", "relay1", "relay2"})
        String target,
        @NotBlank(message = "value không được để trống")
        @Schema(description = "Trạng thái yêu cầu - chữ HOA ON/OFF (gửi \"on\" sẽ tự chuẩn hóa thành \"ON\")",
                example = "ON", allowableValues = {"ON", "OFF"})
        String value) {
}
