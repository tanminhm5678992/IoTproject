package com.example.smarthome.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.smarthome.entity.Device;
import com.example.smarthome.repository.DeviceRepo;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit test nghiệp vụ register/status (mục 9) bằng Mockito:
 * không cần PostgreSQL, không cần broker.
 */
@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    private static final String DEVICE_ID = "node2";
    private static final String TOKEN = "token-cua-node2";

    @Mock
    private DeviceRepo deviceRepo;
    @Mock
    private RealtimePublisher realtimePublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DeviceService service;

    @BeforeEach
    void setUp() {
        service = new DeviceService(deviceRepo, objectMapper, realtimePublisher);
    }

    private Device device(String regStatus, boolean online) {
        Device device = new Device();
        device.setDeviceId(DEVICE_ID);
        device.setName("Node 2 - phòng khách");
        device.setType(Device.TYPE_ACTUATOR);
        device.setRegToken(TOKEN);
        device.setRegStatus(regStatus);
        device.setOnline(online);
        return device;
    }

    private String registerJson(String token) {
        return "{\"deviceId\":\"" + DEVICE_ID + "\",\"type\":\"actuator\",\"fw\":\"1.0.0\","
                + "\"regToken\":\"" + token + "\"}";
    }

    @Test
    @DisplayName("T7: register đúng token -> ACTIVE, online=true, cập nhật fw_version và last_seen")
    void registerDungToken() {
        Device stored = device(Device.REG_PENDING, false);
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(stored));

        boolean activated = service.onRegister(DEVICE_ID, registerJson(TOKEN));

        assertThat(activated).isTrue();
        ArgumentCaptor<Device> saved = ArgumentCaptor.forClass(Device.class);
        verify(deviceRepo).save(saved.capture());
        assertThat(saved.getValue().getRegStatus()).isEqualTo(Device.REG_ACTIVE);
        assertThat(saved.getValue().isOnline()).isTrue();
        assertThat(saved.getValue().getFwVersion()).isEqualTo("1.0.0");
        assertThat(saved.getValue().getLastSeen()).isNotNull();
    }

    @Test
    @DisplayName("T8: register sai token -> bỏ qua, không ghi DB, giữ nguyên PENDING")
    void registerSaiToken() {
        Device stored = device(Device.REG_PENDING, false);
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(stored));

        assertThat(service.onRegister(DEVICE_ID, registerJson("token-sai"))).isFalse();

        verify(deviceRepo, never()).save(any());
        assertThat(stored.getRegStatus()).isEqualTo(Device.REG_PENDING);
        assertThat(stored.isOnline()).isFalse();
    }

    @Test
    @DisplayName("register thiết bị chưa có trong DB -> bỏ qua")
    void registerThietBiChuaCoTrongDb() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.empty());

        assertThat(service.onRegister(DEVICE_ID, registerJson(TOKEN))).isFalse();

        verify(deviceRepo, never()).save(any());
    }

    @Test
    @DisplayName("register JSON hỏng -> bỏ qua (không ném exception)")
    void registerJsonHong() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(device(Device.REG_PENDING, false)));

        assertThat(service.onRegister(DEVICE_ID, "{khong-phai-json")).isFalse();

        verify(deviceRepo, never()).save(any());
    }

    @Test
    @DisplayName("deviceId trong payload khác topic -> bỏ qua")
    void registerSaiDeviceIdTrongPayload() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(device(Device.REG_PENDING, false)));

        assertThat(service.onRegister(DEVICE_ID, "{\"deviceId\":\"node1\",\"regToken\":\"" + TOKEN + "\"}"))
                .isFalse();

        verify(deviceRepo, never()).save(any());
    }

    @Test
    @DisplayName("T15: register gửi lặp lại (thiết bị đã ACTIVE) -> vẫn ACTIVE, không lỗi")
    void registerLapLaiIdempotent() {
        Device stored = device(Device.REG_ACTIVE, true);
        stored.setFwVersion("0.9.0");
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(stored));

        boolean activated = service.onRegister(DEVICE_ID, registerJson(TOKEN));

        assertThat(activated).isTrue();
        verify(deviceRepo).save(stored);
        assertThat(stored.getRegStatus()).isEqualTo(Device.REG_ACTIVE);
        assertThat(stored.isOnline()).isTrue();
        assertThat(stored.getFwVersion()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("status=offline -> devices.online=false")
    void statusOffline() {
        Device stored = device(Device.REG_ACTIVE, true);
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(stored));

        assertThat(service.onStatus(DEVICE_ID, "offline")).isTrue();

        verify(deviceRepo).save(stored);
        assertThat(stored.isOnline()).isFalse();
    }

    @Test
    @DisplayName("status=online -> devices.online=true")
    void statusOnline() {
        Device stored = device(Device.REG_ACTIVE, false);
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(stored));

        assertThat(service.onStatus(DEVICE_ID, "online")).isTrue();

        verify(deviceRepo).save(stored);
        assertThat(stored.isOnline()).isTrue();
    }

    @Test
    @DisplayName("status payload lạ -> bỏ qua, không ghi DB")
    void statusPayloadLa() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(device(Device.REG_ACTIVE, true)));

        assertThat(service.onStatus(DEVICE_ID, "ON")).isFalse();

        verify(deviceRepo, never()).save(any());
    }

    @Test
    @DisplayName("status của thiết bị chưa đăng ký -> bỏ qua")
    void statusThietBiChuaDangKy() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.empty());

        assertThat(service.onStatus(DEVICE_ID, "online")).isFalse();

        verify(deviceRepo, never()).save(any());
    }
}