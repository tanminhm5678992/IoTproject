package com.example.smarthome.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.smarthome.entity.Device;
import com.example.smarthome.entity.Telemetry;
import com.example.smarthome.repository.DeviceRepo;
import com.example.smarthome.repository.TelemetryRepo;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Unit test nghiệp vụ telemetry (mục 9): chỉ nhận từ thiết bị ACTIVE + kiểm tra miền giá trị. */
@ExtendWith(MockitoExtension.class)
class TelemetryServiceTest {

    private static final String DEVICE_ID = "node1";

    @Mock
    private DeviceRepo deviceRepo;
    @Mock
    private TelemetryRepo telemetryRepo;
    @Mock
    private RealtimePublisher realtimePublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TelemetryService service;

    @BeforeEach
    void setUp() {
        service = new TelemetryService(deviceRepo, telemetryRepo, objectMapper, realtimePublisher);
    }

    private Device device(String regStatus) {
        Device device = new Device();
        device.setDeviceId(DEVICE_ID);
        device.setName("Node 1 - cảm biến");
        device.setType(Device.TYPE_SENSOR);
        device.setRegToken("token-node1");
        device.setRegStatus(regStatus);
        return device;
    }

    @Test
    @DisplayName("Thiết bị ACTIVE: lưu bản ghi, dùng ts của thiết bị và cập nhật last_seen")
    void luuKhiActive() {
        Device stored = device(Device.REG_ACTIVE);
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(stored));
        long ts = 1_700_000_000L;

        boolean saved = service.onTelemetry(DEVICE_ID,
                "{\"temp\":27.5,\"hum\":60,\"relay1\":true,\"relay2\":false,\"ts\":" + ts + "}");

        assertThat(saved).isTrue();
        ArgumentCaptor<Telemetry> captor = ArgumentCaptor.forClass(Telemetry.class);
        verify(telemetryRepo).save(captor.capture());
        Telemetry telemetry = captor.getValue();
        assertThat(telemetry.getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(telemetry.getTemp()).isEqualTo(27.5f);
        assertThat(telemetry.getHum()).isEqualTo(60f);
        assertThat(telemetry.getRelay1()).isTrue();
        assertThat(telemetry.getRelay2()).isFalse();
        assertThat(telemetry.getRecordedAt().toInstant()).isEqualTo(Instant.ofEpochSecond(ts));
        verify(deviceRepo).save(stored);
        assertThat(stored.getLastSeen()).isNotNull();
    }

    @Test
    @DisplayName("T9: telemetry của thiết bị chưa ACTIVE -> không lưu DB")
    void khongLuuKhiChuaActive() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(device(Device.REG_PENDING)));

        assertThat(service.onTelemetry(DEVICE_ID, "{\"temp\":27.5,\"hum\":60}")).isFalse();

        verify(telemetryRepo, never()).save(any());
        verify(deviceRepo, never()).save(any());
    }

    @Test
    @DisplayName("thiết bị chưa đăng ký -> không lưu DB")
    void khongLuuKhiThietBiChuaDangKy() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.empty());

        assertThat(service.onTelemetry(DEVICE_ID, "{\"temp\":27.5,\"hum\":60}")).isFalse();

        verify(telemetryRepo, never()).save(any());
    }

    @Test
    @DisplayName("temp ngoài [-40, 80] -> bỏ qua bản ghi")
    void boQuaKhiTempNgoaiMien() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(device(Device.REG_ACTIVE)));

        assertThat(service.onTelemetry(DEVICE_ID, "{\"temp\":85.2,\"hum\":60}")).isFalse();

        verify(telemetryRepo, never()).save(any());
    }

    @Test
    @DisplayName("hum ngoài [0, 100] -> bỏ qua bản ghi")
    void boQuaKhiHumNgoaiMien() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(device(Device.REG_ACTIVE)));

        assertThat(service.onTelemetry(DEVICE_ID, "{\"temp\":27.5,\"hum\":-1}")).isFalse();

        verify(telemetryRepo, never()).save(any());
    }

    @Test
    @DisplayName("payload rỗng hoặc JSON hỏng -> bỏ qua, không ném exception")
    void boQuaKhiJsonHong() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(device(Device.REG_ACTIVE)));

        assertThat(service.onTelemetry(DEVICE_ID, "nhiet-do-27.5")).isFalse();
        assertThat(service.onTelemetry(DEVICE_ID, "   ")).isFalse();

        verify(telemetryRepo, never()).save(any());
    }

    @Test
    @DisplayName("Thiếu ts (thiết bị chưa có NTP) -> dùng giờ backend")
    void dungGioBackendKhiThieuTs() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(device(Device.REG_ACTIVE)));
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

        assertThat(service.onTelemetry(DEVICE_ID, "{\"temp\":27.5,\"hum\":60}")).isTrue();

        ArgumentCaptor<Telemetry> captor = ArgumentCaptor.forClass(Telemetry.class);
        verify(telemetryRepo).save(captor.capture());
        assertThat(captor.getValue().getRecordedAt()).isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("ts vô lý (bằng 0) -> dùng giờ backend")
    void dungGioBackendKhiTsVoLy() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(device(Device.REG_ACTIVE)));

        assertThat(service.onTelemetry(DEVICE_ID, "{\"temp\":27.5,\"hum\":60,\"ts\":0}")).isTrue();

        ArgumentCaptor<Telemetry> captor = ArgumentCaptor.forClass(Telemetry.class);
        verify(telemetryRepo).save(captor.capture());
        assertThat(captor.getValue().getRecordedAt())
                .isAfter(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    }
}