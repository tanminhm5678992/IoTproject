package com.example.smarthome.entity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Bảng telemetry (mục 8) - mỗi bản ghi là một lần thiết bị gửi dữ liệu cảm biến/relay. */
@Entity
@Table(name = "telemetry")
@Getter
@Setter
@NoArgsConstructor
public class Telemetry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, length = 50)
    private String deviceId;

    /** Nhiệt độ °C (DHT11). NULL nếu thiết bị không gửi. */
    @Column(name = "temp")
    private Float temp;

    /** Độ ẩm % (DHT11). NULL nếu thiết bị không gửi. */
    @Column(name = "hum")
    private Float hum;

    @Column(name = "relay1")
    private Boolean relay1;

    @Column(name = "relay2")
    private Boolean relay2;

    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt = OffsetDateTime.now(ZoneOffset.UTC);
}
