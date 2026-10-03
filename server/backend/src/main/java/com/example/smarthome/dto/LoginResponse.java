package com.example.smarthome.dto;

/** Kết quả POST /api/auth/login: token JWT (hết hạn sau jwt.expire-minutes). */
public record LoginResponse(String token, String username, String role, long expiresInSeconds) {
}
