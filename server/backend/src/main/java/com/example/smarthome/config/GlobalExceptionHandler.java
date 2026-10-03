package com.example.smarthome.config;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.smarthome.dto.ApiError;
import com.example.smarthome.exception.DeviceNotFoundException;
import com.example.smarthome.exception.DeviceNotReadyException;
import com.example.smarthome.exception.DuplicateDeviceException;
import com.example.smarthome.exception.InvalidCommandException;
import com.example.smarthome.exception.TelemetryNotFoundException;

/**
 * Trả JSON thống nhất {timestamp,status,error,message} cho mọi lỗi REST.
 * Mã lỗi (mục 9): 400 (dữ liệu sai), 401 (chưa đăng nhập), 404 (không thấy thiết bị
 * hoặc chưa có telemetry), 409 (trùng deviceId), 422 (thiết bị chưa ACTIVE/offline).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError badRequest(Exception ex) {
        String message;
        if (ex instanceof MethodArgumentNotValidException e) {
            message = e.getBindingResult().getFieldErrors().stream()
                    .map(f -> f.getField() + ": " + f.getDefaultMessage())
                    .findFirst()
                    .orElse("Dữ liệu không hợp lệ");
        } else if (ex instanceof HttpMessageNotReadableException) {
            message = "JSON không hợp lệ hoặc thiếu trường bắt buộc";
        } else {
            message = ex.getMessage();
        }
        return error(HttpStatus.BAD_REQUEST, "Bad Request", message);
    }

    /** target/value không hợp lệ khi gửi lệnh -> 400 (con của IllegalArgumentException). */
    @ExceptionHandler(InvalidCommandException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalidCommand(InvalidCommandException ex) {
        return error(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage());
    }

    @ExceptionHandler({BadCredentialsException.class, AuthenticationException.class})
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiError unauthorized(Exception ex) {
        return error(HttpStatus.UNAUTHORIZED, "Unauthorized", "Sai username hoặc mật khẩu");
    }

    @ExceptionHandler({DeviceNotFoundException.class, TelemetryNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError notFound(Exception ex) {
        return error(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
    }

    @ExceptionHandler(DuplicateDeviceException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError conflict(DuplicateDeviceException ex) {
        return error(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
    }

    /** Thiết bị chưa ACTIVE hoặc offline -> 422 (đã chốt: gửi lệnh cần ACTIVE + online). */
    @ExceptionHandler(DeviceNotReadyException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiError notReady(DeviceNotReadyException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable Entity", ex.getMessage());
    }

    /** Endpoint không tồn tại (kể cả favicon, static) -> 404 thay vì 500. */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError noResource(Exception ex) {
        return error(HttpStatus.NOT_FOUND, "Not Found", "Không tìm thấy tài nguyên");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError internal(Exception ex) {
        // Không lộ chi tiết nội bộ ra client; log đầy đủ cho người vận hành
        log.error("Lỗi không mong muốn khi xử lý REST", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "Đã xảy ra lỗi phía server");
    }

    private ApiError error(HttpStatus status, String error, String message) {
        return new ApiError(OffsetDateTime.now(ZoneOffset.UTC), status.value(), error, message);
    }
}
