package com.example.smarthome.dto;

import java.time.OffsetDateTime;

/**
 * JSON lỗi thống nhất cho mọi lỗi REST (mục 9):
 * {timestamp,status,error,message}.
 */
public record ApiError(OffsetDateTime timestamp, int status, String error, String message) {
}
