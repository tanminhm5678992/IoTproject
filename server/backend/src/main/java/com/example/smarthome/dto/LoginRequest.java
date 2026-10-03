package com.example.smarthome.dto;

import jakarta.validation.constraints.NotBlank;

/** Body của POST /api/auth/login. */
public record LoginRequest(
        @NotBlank(message = "username không được để trống") String username,
        @NotBlank(message = "password không được để trống") String password) {
}
