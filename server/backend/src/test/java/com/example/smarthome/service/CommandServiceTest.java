package com.example.smarthome.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.beans.factory.ObjectProvider;

import com.example.smarthome.dto.CommandPayload;
import com.example.smarthome.entity.Command;
import com.example.smarthome.entity.Device;
import com.example.smarthome.mqtt.MqttPublisher;
import com.example.smarthome.repository.CommandRepo;
import com.example.smarthome.repository.DeviceRepo;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Unit test nghiệp vụ gửi lệnh + nhận ack (mục 9). */
@ExtendWith(MockitoExtension.class)
class CommandServiceTest {

    private static final String DEVICE_ID = "node2";

    @Mock
    private CommandRepo commandRepo;
    @Mock
    private DeviceRepo deviceRepo;
    @Mock
    private ObjectProvider<MqttPublisher> publisherProvider;
    @Mock
    private MqttPublisher mqttPublisher;
    @Mock
    private RealtimePublisher realtimePublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CommandService service;

    @BeforeEach
    void setUp() {
        service = new CommandService(commandRepo, deviceRepo, publisherProvider, objectMapper, realtimePublisher);
    }

    private Device device(String regStatus, boolean online) {
        Device device = new Device();
        device.setDeviceId(DEVICE_ID);
        device.setName("Node 2");
        device.setType(Device.TYPE_ACTUATOR);
        device.setRegToken("token-node2");
        device.setRegStatus(regStatus);
        device.setOnline(online);
        return device;
    }

    private Command command(String status) {
        Command command = new Command();
        command.setCmdId("c-abc123");
        command.setDeviceId(DEVICE_ID);
        command.setTarget("relay1");
        command.setValue("ON");
        command.setStatus(status);
        return command;
    }

    @Test
    @DisplayName("Gửi lệnh khi thiết bị ACTIVE + online -> lưu PENDING và publish xuống sh/{id}/cmd")
    void sendCommandHople() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(device(Device.REG_ACTIVE, true)));
        when(publisherProvider.getIfAvailable()).thenReturn(mqttPublisher);

        Command sent = service.sendCommand(DEVICE_ID, "relay1", "ON", "admin");

        assertThat(sent.getCmdId()).startsWith("c-");
        assertThat(sent.getStatus()).isEqualTo(Command.STATUS_PENDING);
        assertThat(sent.getSentAt()).isNotNull();
        verify(commandRepo).save(sent);

        ArgumentCaptor<CommandPayload> payload = ArgumentCaptor.forClass(CommandPayload.class);
        verify(mqttPublisher).publishCommand(org.mockito.ArgumentMatchers.eq(DEVICE_ID), payload.capture());
        assertThat(payload.getValue().cmdId()).isEqualTo(sent.getCmdId());
        assertThat(payload.getValue().target()).isEqualTo("relay1");
        assertThat(payload.getValue().value()).isEqualTo("ON");
    }

    // ---- chuẩn hóa target/value (mục 2 - quy ước, mục 7 so sánh chính xác) ----

    @Test
    @DisplayName("Chuẩn hóa: target ' RELAY1 ' + value 'on' -> relay1/'ON' (publish xuống broker đúng chữ HOA)")
    void chuanHoaTargetValue() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(device(Device.REG_ACTIVE, true)));
        when(publisherProvider.getIfAvailable()).thenReturn(mqttPublisher);

        Command sent = service.sendCommand(DEVICE_ID, "  RELAY1 ", "on", "admin");

        // Lưu DB đã chuẩn hóa - dashboard đọc đúng định dạng
        assertThat(sent.getTarget()).isEqualTo("relay1");
        assertThat(sent.getValue()).isEqualTo("ON");

        // Payload trên wire PHẢI là chữ hoa (firmware: String(value) == "ON")
        ArgumentCaptor<CommandPayload> payload = ArgumentCaptor.forClass(CommandPayload.class);
        verify(mqttPublisher).publishCommand(org.mockito.ArgumentMatchers.eq(DEVICE_ID), payload.capture());
        assertThat(payload.getValue().target()).isEqualTo("relay1");
        assertThat(payload.getValue().value()).isEqualTo("ON");
    }

    @Test
    @DisplayName("Chuẩn hóa: value 'Off' -> 'OFF' trước khi lưu và publish")
    void chuanHoaValueOff() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(device(Device.REG_ACTIVE, true)));
        when(publisherProvider.getIfAvailable()).thenReturn(mqttPublisher);

        Command sent = service.sendCommand(DEVICE_ID, "led2", "Off", "admin");

        assertThat(sent.getValue()).isEqualTo("OFF");
        ArgumentCaptor<CommandPayload> payload = ArgumentCaptor.forClass(CommandPayload.class);
        verify(mqttPublisher).publishCommand(org.mockito.ArgumentMatchers.eq(DEVICE_ID), payload.capture());
        assertThat(payload.getValue().value()).isEqualTo("OFF");
    }

    @Test
    @DisplayName("Chuẩn hóa: value 'bat' sau chuẩn hóa vẫn không phải ON/OFF -> lỗi, không lưu, không publish")
    void valueSaiSauChuanHoa() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(device(Device.REG_ACTIVE, true)));

        assertThatThrownBy(() -> service.sendCommand(DEVICE_ID, "relay1", "bat", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value");

        verify(commandRepo, never()).save(any());
        verify(mqttPublisher, never()).publishCommand(any(), any());
    }

    @Test
    @DisplayName("Thiết bị đang offline -> IllegalStateException, không lưu lệnh (Phase 3 trả HTTP 422)")
    void sendCommandKhiOffline() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(device(Device.REG_ACTIVE, false)));

        assertThatThrownBy(() -> service.sendCommand(DEVICE_ID, "relay1", "ON", "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("offline");

        verify(commandRepo, never()).save(any());
        verify(mqttPublisher, never()).publishCommand(any(), any());
    }

    @Test
    @DisplayName("Thiết bị chưa ACTIVE -> IllegalStateException")
    void sendCommandKhiChuaActive() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(device(Device.REG_PENDING, true)));

        assertThatThrownBy(() -> service.sendCommand(DEVICE_ID, "relay1", "ON", "admin"))
                .isInstanceOf(IllegalStateException.class);

        verify(commandRepo, never()).save(any());
    }

    @Test
    @DisplayName("target/value không hợp lệ -> IllegalArgumentException")
    void sendCommandTargetValueSai() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(device(Device.REG_ACTIVE, true)));

        assertThatThrownBy(() -> service.sendCommand(DEVICE_ID, "den-bep", "ON", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.sendCommand(DEVICE_ID, "relay1", "BAT", "admin"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(commandRepo, never()).save(any());
    }

    @Test
    @DisplayName("Không thấy thiết bị -> IllegalArgumentException (Phase 3 trả HTTP 404)")
    void sendCommandThietBiKhongTonTai() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendCommand(DEVICE_ID, "relay1", "ON", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("MQTT bị tắt -> vẫn lưu PENDING, không ném exception")
    void sendCommandKhiMqttTat() {
        when(deviceRepo.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(device(Device.REG_ACTIVE, true)));
        when(publisherProvider.getIfAvailable()).thenReturn(null);

        Command sent = service.sendCommand(DEVICE_ID, "relay1", "ON", "admin");

        assertThat(sent.getStatus()).isEqualTo(Command.STATUS_PENDING);
        verify(commandRepo).save(sent);
        verify(mqttPublisher, never()).publishCommand(any(), any());
    }

    @Test
    @DisplayName("ack result=ok -> lệnh DONE, ghi acked_at")
    void ackOk() {
        Command pending = command(Command.STATUS_PENDING);
        when(commandRepo.findByCmdId("c-abc123")).thenReturn(Optional.of(pending));

        boolean updated = service.onAck(DEVICE_ID, "{\"cmdId\":\"c-abc123\",\"result\":\"ok\",\"target\":\"relay1\"}");

        assertThat(updated).isTrue();
        verify(commandRepo).save(pending);
        assertThat(pending.getStatus()).isEqualTo(Command.STATUS_DONE);
        assertThat(pending.getAckedAt()).isNotNull();
    }

    @Test
    @DisplayName("ack result=error -> lệnh ERROR")
    void ackError() {
        Command pending = command(Command.STATUS_PENDING);
        when(commandRepo.findByCmdId("c-abc123")).thenReturn(Optional.of(pending));

        assertThat(service.onAck(DEVICE_ID, "{\"cmdId\":\"c-abc123\",\"result\":\"error\"}")).isTrue();

        assertThat(pending.getStatus()).isEqualTo(Command.STATUS_ERROR);
        assertThat(pending.getAckedAt()).isNotNull();
    }

    @Test
    @DisplayName("ack cho cmdId không có trong DB -> bỏ qua")
    void ackCmdIdKhongTonTai() {
        when(commandRepo.findByCmdId("c-la")).thenReturn(Optional.empty());

        assertThat(service.onAck(DEVICE_ID, "{\"cmdId\":\"c-la\",\"result\":\"ok\"}")).isFalse();

        verify(commandRepo, never()).save(any());
    }

    @Test
    @DisplayName("ack trùng (lệnh đã DONE) -> bỏ qua, trạng thái cuối không đổi")
    void ackTrung() {
        Command done = command(Command.STATUS_DONE);
        when(commandRepo.findByCmdId("c-abc123")).thenReturn(Optional.of(done));

        assertThat(service.onAck(DEVICE_ID, "{\"cmdId\":\"c-abc123\",\"result\":\"error\"}")).isFalse();

        verify(commandRepo, never()).save(any());
        assertThat(done.getStatus()).isEqualTo(Command.STATUS_DONE);
    }

    @Test
    @DisplayName("ack đến từ thiết bị khác -> bỏ qua (chống ack chéo)")
    void ackSaiThietBi() {
        Command pending = command(Command.STATUS_PENDING);
        when(commandRepo.findByCmdId("c-abc123")).thenReturn(Optional.of(pending));

        assertThat(service.onAck("node1", "{\"cmdId\":\"c-abc123\",\"result\":\"ok\"}")).isFalse();

        verify(commandRepo, never()).save(any());
        assertThat(pending.getStatus()).isEqualTo(Command.STATUS_PENDING);
    }

    @Test
    @DisplayName("ack thiếu cmdId hoặc JSON hỏng -> bỏ qua, không ném exception")
    void ackPayloadHong() {
        assertThat(service.onAck(DEVICE_ID, "{\"result\":\"ok\"}")).isFalse();
        assertThat(service.onAck(DEVICE_ID, "khong-phai-json")).isFalse();
        assertThat(service.onAck(DEVICE_ID, "")).isFalse();

        verify(commandRepo, never()).findByCmdId(any());
        verify(commandRepo, never()).save(any());
    }
}