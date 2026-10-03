package com.example.smarthome.entity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

/** Bảng devices (mục 8) - thiết bị đã đăng ký trên broker LOCAL. */
@Entity
@Table(name = "devices")
@Getter
@Setter
@NoArgsConstructor
public class Device {

    /** reg_status (mục 8) */
    public static final String REG_PENDING = "PENDING";
    public static final String REG_ACTIVE = "ACTIVE";

    /** type (mục 2 - quy ước) */
    public static final String TYPE_SENSOR = "sensor";
    public static final String TYPE_ACTUATOR = "actuator";
    public static final Set<String> TYPES = Set.of(TYPE_SENSOR, TYPE_ACTUATOR);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, unique = true, length = 50)
    private String deviceId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "type", nullable = false, length = 20)
    private String type;

    /** Token cấp khi đăng ký thiết bị; thiết bị phải gửi đúng token trong payload register. */
    @Column(name = "reg_token", nullable = false, length = 64)
    private String regToken;

    @Column(name = "reg_status", nullable = false, length = 20)
    private String regStatus = REG_PENDING;

    @Column(name = "online", nullable = false)
    private boolean online = false;

    @Column(name = "fw_version", length = 20)
    private String fwVersion;

    @Column(name = "last_seen")
    private OffsetDateTime lastSeen;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
}
