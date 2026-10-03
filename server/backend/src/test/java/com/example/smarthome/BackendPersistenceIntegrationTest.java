package com.example.smarthome;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.smarthome.entity.Command;
import com.example.smarthome.entity.Device;
import com.example.smarthome.entity.Telemetry;
import com.example.smarthome.repository.CommandRepo;
import com.example.smarthome.repository.DeviceRepo;
import com.example.smarthome.repository.TelemetryRepo;
import com.example.smarthome.service.CommandService;
import com.example.smarthome.service.DeviceService;
import com.example.smarthome.service.TelemetryService;

/**
 * Integration test Phase 2 với PostgreSQL thật (Testcontainers):
 *  - Flyway tạo schema đúng và spring.jpa.hibernate.ddl-auto=validate phải chạy được;
 *  - register -> telemetry -> status -> lệnh -> ack lưu và đổi trạng thái đúng trong DB.
 *
 * MQTT tắt (mqtt.enabled=false) nên không cần broker; luồng MQTT vào/ra được kiểm tra
 * riêng bằng MqttInboundHandlerTest và MqttPublisherTest.
 */
@SpringBootTest(properties = {"mqtt.enabled=false", "app.command-timeout.enabled=false"})
@Testcontainers(disabledWithoutDocker = true)
class BackendPersistenceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String DEVICE_ID = "node2";
    private static final String TOKEN = "token-integration-node2";

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DeviceService deviceService;
    @Autowired
    private TelemetryService telemetryService;
    @Autowired
    private CommandService commandService;
    @Autowired
    private DeviceRepo deviceRepo;
    @Autowired
    private TelemetryRepo telemetryRepo;
    @Autowired
    private CommandRepo commandRepo;

    /** Mỗi test bắt đầu với DB sạch (xóa con trước để không vi phạm khóa ngoại). */
    @BeforeEach
    void xoaDuLieu() {
        commandRepo.deleteAll();
        telemetryRepo.deleteAll();
        deviceRepo.deleteAll();
    }

    private Device themThietBi(String regStatus, boolean online) {
        Device device = new Device();
        device.setDeviceId(DEVICE_ID);
        device.setName("Node 2 - phòng khách");
        device.setType(Device.TYPE_ACTUATOR);
        device.setRegToken(TOKEN);
        device.setRegStatus(regStatus);
        device.setOnline(online);
        return deviceRepo.save(device);
    }

    private String registerJson() {
        return "{\"deviceId\":\"" + DEVICE_ID + "\",\"type\":\"actuator\",\"fw\":\"1.0.0\","
                + "\"regToken\":\"" + TOKEN + "\"}";
    }

    @Test
    @DisplayName("Flyway tạo đủ 4 bảng theo mục 8 và Hibernate validate khớp entity")
    void flywayTaoDuSchema() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);

        assertThat(tables).contains("users", "devices", "telemetry", "commands", "flyway_schema_history");
    }

@Test
    @DisplayName("T7+T9: telemetry của thiết bị PENDING bị bỏ; sau register ACTIVE thì được lưu kèm last_seen")
    void registerRoiMoiNhanTelemetry() {
        themThietBi(Device.REG_PENDING, false);
        String telemetryJson = "{\"temp\":27.5,\"hum\":60.5,\"relay1\":true,\"relay2\":false}";

        // T9: chưa ACTIVE -> không lưu
        assertThat(telemetryService.onTelemetry(DEVICE_ID, telemetryJson)).isFalse();
        assertThat(telemetryRepo.findByDeviceIdOrderByRecordedAtDesc(DEVICE_ID)).isEmpty();

        // T7: register đúng token -> ACTIVE
        assertThat(deviceService.onRegister(DEVICE_ID, registerJson())).isTrue();
        Device activated = deviceRepo.findByDeviceId(DEVICE_ID).orElseThrow();
        assertThat(activated.getRegStatus()).isEqualTo(Device.REG_ACTIVE);
        assertThat(activated.isOnline()).isTrue();
        assertThat(activated.getFwVersion()).isEqualTo("1.0.0");

        // telemetry được lưu, last_seen cập nhật
        assertThat(telemetryService.onTelemetry(DEVICE_ID, telemetryJson)).isTrue();
        List<Telemetry> saved = telemetryRepo.findByDeviceIdOrderByRecordedAtDesc(DEVICE_ID);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getTemp()).isEqualTo(27.5f);
        assertThat(saved.get(0).getHum()).isEqualTo(60.5f);
        assertThat(saved.get(0).getRelay1()).isTrue();
        assertThat(saved.get(0).getRecordedAt()).isNotNull();
        assertThat(deviceRepo.findByDeviceId(DEVICE_ID).orElseThrow().getLastSeen()).isNotNull();

        // status offline (LWT) -> online=false trong DB
        assertThat(deviceService.onStatus(DEVICE_ID, "offline")).isTrue();
        assertThat(deviceRepo.findByDeviceId(DEVICE_ID).orElseThrow().isOnline()).isFalse();
    }

    @Test
    @DisplayName("T10 (phần DB): lệnh lưu PENDING rồi DONE sau ack, có acked_at")
    void lenhPendingRoiDoneSauAck() {
        themThietBi(Device.REG_ACTIVE, true);

        Command sent = commandService.sendCommand(DEVICE_ID, "relay1", "ON", "admin");
        assertThat(sent.getStatus()).isEqualTo(Command.STATUS_PENDING);
        assertThat(commandRepo.findByCmdId(sent.getCmdId()).orElseThrow().getStatus())
                .isEqualTo(Command.STATUS_PENDING);

        assertThat(commandService.onAck(DEVICE_ID,
                "{\"cmdId\":\"" + sent.getCmdId() + "\",\"result\":\"ok\",\"target\":\"relay1\",\"value\":\"ON\"}"))
                .isTrue();

        Command acked = commandRepo.findByCmdId(sent.getCmdId()).orElseThrow();
        assertThat(acked.getStatus()).isEqualTo(Command.STATUS_DONE);
        assertThat(acked.getAckedAt()).isNotNull();
    }

    @Test
    @DisplayName("T15: register lặp lại nhiều lần -> vẫn một bản ghi, ACTIVE, không lỗi")
    void registerLapLaiIdempotent() {
        themThietBi(Device.REG_PENDING, false);

        assertThat(deviceService.onRegister(DEVICE_ID, registerJson())).isTrue();
        assertThat(deviceService.onRegister(DEVICE_ID, registerJson())).isTrue();
        assertThat(deviceService.onRegister(DEVICE_ID, registerJson())).isTrue();

        assertThat(deviceRepo.findAll()).hasSize(1);
        Device device = deviceRepo.findByDeviceId(DEVICE_ID).orElseThrow();
        assertThat(device.getRegStatus()).isEqualTo(Device.REG_ACTIVE);
        assertThat(device.isOnline()).isTrue();
    }

    @Test
    @DisplayName("register sai token -> DB vẫn PENDING, không tạo bản ghi lạ")
    void registerSaiTokenKhongDoiDb() {
        themThietBi(Device.REG_PENDING, false);

        assertThat(deviceService.onRegister(DEVICE_ID,
                "{\"deviceId\":\"" + DEVICE_ID + "\",\"regToken\":\"token-sai\"}")).isFalse();

        Device device = deviceRepo.findByDeviceId(DEVICE_ID).orElseThrow();
        assertThat(device.getRegStatus()).isEqualTo(Device.REG_PENDING);
        assertThat(device.isOnline()).isFalse();
        assertThat(deviceRepo.findAll()).hasSize(1);
    }
}