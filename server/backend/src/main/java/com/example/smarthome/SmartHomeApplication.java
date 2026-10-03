package com.example.smarthome;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Backend của hệ thống MQTT Smart Home.
 *
 * Luồng chính:
 *   thiết bị -> broker LOCAL -> bridge -> broker SERVER -> MqttInboundHandler
 *            -> DeviceService / TelemetryService / CommandService -> PostgreSQL
 *   backend -> MqttPublisher -> sh/{id}/cmd -> broker SERVER -> bridge -> thiết bị
 *   dashboard -> REST (JWT) -> controllers -> services (Phase 3)
 *   dashboard <- WebSocket STOMP /ws (RealtimePublisher)
 *
 * @EnableScheduling: bật CommandTimeoutJob (PENDING -> TIMEOUT sau 10 giây).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SmartHomeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartHomeApplication.class, args);
    }
}
