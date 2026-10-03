package com.example.smarthome.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body của POST /api/devices (đăng ký thiết bị).
 * deviceId: 2-50 ký tự chữ thường/số/gạch, khớp username MQTT của thiết bị.
 */
public record DeviceCreateRequest(
        @NotBlank(message = "deviceId không được để trống")
        @Size(min = 2, max = 50, message = "deviceId phải 2-50 ký tự")
        @Pattern(regexp = "^[a-z0-9][a-z0-9_-]*$",
                message = "deviceId chỉ gồm chữ thường, số, '-', '_' và bắt đầu bằng chữ thường/số")
        @Schema(description = "Mã thiết bị (chữ thường, khớp username MQTT)", example = "node1")
        String deviceId,
        @NotBlank(message = "name không được để trống")
        @Size(max = 100, message = "name tối đa 100 ký tự")
        @Schema(description = "Tên hiển thị", example = "Node 1 - phòng khách")
        String name,
        @NotBlank(message = "type không được để trống")
        @Schema(description = "Loại thiết bị - CHỮ THƯỜNG (gửi \"ACTUATOR\" sẽ bị 400)",
                example = "actuator", allowableValues = {"sensor", "actuator"})
        String type) {
}
