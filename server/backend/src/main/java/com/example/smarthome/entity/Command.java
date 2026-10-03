package com.example.smarthome.entity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Bảng commands (mục 8) - lệnh điều khiển gửi xuống thiết bị và trạng thái ack. */
@Entity
@Table(name = "commands")
@Getter
@Setter
@NoArgsConstructor
public class Command {

    /** status (mục 8) */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    /** target hợp lệ (mục 2 - quy ước) */
    public static final Set<String> TARGETS = Set.of("led1", "led2", "relay1", "relay2");
    /** value hợp lệ (mục 2 - quy ước) */
    public static final Set<String> VALUES = Set.of("ON", "OFF");

    /**
     * Chuẩn hóa target tại biên API: bỏ khoảng trắng + về chữ thường.
     *
     * <p>Đặc tả mục 2 quy ước target là chữ thường (led1/led2/relay1/relay2) và firmware
     * so sánh chuỗi CHÍNH XÁC, nên mọi giá trị vào hệ thống phải được chuẩn hóa trước khi
     * lưu DB và trước khi publish xuống {@code sh/{id}/cmd}.
     */
    public static String normalizeTarget(String target) {
        return target == null ? null : target.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Chuẩn hóa value tại biên API: bỏ khoảng trắng + về CHỮ HOA.
     *
     * <p>Firmware so sánh chính xác {@code String(value) == "ON"} (mục 7), nên "on"/"On"
     * phải thành "ON". Nhờ chuẩn hóa ở đây mà payload publish xuống {@code sh/{id}/cmd}
     * LUÔN là "ON"/"OFF" viết hoa, không phụ thuộc client gửi gì.
     */
    public static String normalizeValue(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mã lệnh do backend sinh (ví dụ c-001), thiết bị trả lại trong ack. */
    @Column(name = "cmd_id", nullable = false, unique = true, length = 40)
    private String cmdId;

    @Column(name = "device_id", nullable = false, length = 50)
    private String deviceId;

    @Column(name = "target", nullable = false, length = 20)
    private String target;

    @Column(name = "value", nullable = false, length = 10)
    private String value;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_PENDING;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt = OffsetDateTime.now(ZoneOffset.UTC);

    @Column(name = "acked_at")
    private OffsetDateTime ackedAt;
}
