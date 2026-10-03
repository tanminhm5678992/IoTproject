package com.example.smarthome.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.smarthome.dto.AckPayload;
import com.example.smarthome.dto.CommandPayload;
import com.example.smarthome.dto.Payloads;
import com.example.smarthome.entity.Command;
import com.example.smarthome.entity.Device;
import com.example.smarthome.exception.DeviceNotFoundException;
import com.example.smarthome.exception.DeviceNotReadyException;
import com.example.smarthome.exception.InvalidCommandException;
import com.example.smarthome.mqtt.MqttPublisher;
import com.example.smarthome.repository.CommandRepo;
import com.example.smarthome.repository.DeviceRepo;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Nghiệp vụ lệnh điều khiển (mục 9): tạo + publish lệnh xuống thiết bị,
 * cập nhật trạng thái khi thiết bị trả ack và đẩy WebSocket.
 *
 * Lỗi nghiệp vụ -> mã HTTP (GlobalExceptionHandler):
 *  - thiết bị không tồn tại        -> DeviceNotFoundException      -> 404
 *  - chưa ACTIVE hoặc offline      -> DeviceNotReadyException      -> 422 (đã chốt)
 *  - target/value sai              -> InvalidCommandException      -> 400
 */
@Service
public class CommandService {

    private static final String RESULT_OK = "ok";

    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    private final CommandRepo commandRepo;
    private final DeviceRepo deviceRepo;
    /** MQTT có thể bị tắt (mqtt.enabled=false khi chạy test) nên dùng ObjectProvider. */
    private final ObjectProvider<MqttPublisher> mqttPublisherProvider;
    private final ObjectMapper objectMapper;
    private final RealtimePublisher realtimePublisher;

    public CommandService(CommandRepo commandRepo,
                          DeviceRepo deviceRepo,
                          ObjectProvider<MqttPublisher> mqttPublisherProvider,
                          ObjectMapper objectMapper,
                          RealtimePublisher realtimePublisher) {
        this.commandRepo = commandRepo;
        this.deviceRepo = deviceRepo;
        this.mqttPublisherProvider = mqttPublisherProvider;
        this.objectMapper = objectMapper;
        this.realtimePublisher = realtimePublisher;
    }

    /**
     * Tạo lệnh PENDING rồi publish xuống sh/{deviceId}/cmd.
     *
     * @throws DeviceNotFoundException  thiết bị không tồn tại (HTTP 404)
     * @throws DeviceNotReadyException  chưa ACTIVE hoặc offline (HTTP 422 - đã chốt)
     * @throws InvalidCommandException  target/value không hợp lệ (HTTP 400)
     */
    @Transactional
    public Command sendCommand(String deviceId, String target, String value, String createdBy) {
        Device device = deviceRepo.findByDeviceId(deviceId)
                .orElseThrow(() -> new DeviceNotFoundException(deviceId));
        if (!Device.REG_ACTIVE.equals(device.getRegStatus())) {
            throw new DeviceNotReadyException(
                    "Thiết bị " + deviceId + " chưa ACTIVE nên không gửi lệnh được");
        }
        if (!device.isOnline()) {
            throw new DeviceNotReadyException(
                    "Thiết bị " + deviceId + " đang offline nên không gửi lệnh được");
        }
        // Chuẩn hóa TRƯỚC khi kiểm tra (mục 2 - quy ước): target về chữ thường,
        // value về CHỮ HOA. Firmware so sánh chính xác "ON"/"OFF" nên payload publish
        // xuống sh/{id}/cmd phải LUÔN là chữ hoa, kể cả khi client gửi "on" hay " On ".
        String targetNorm = Command.normalizeTarget(target);
        String valueNorm = Command.normalizeValue(value);
        if (!Command.TARGETS.contains(targetNorm)) {
            throw new InvalidCommandException("target không hợp lệ: " + target
                    + " (hợp lệ: " + Command.TARGETS + ")");
        }
        if (!Command.VALUES.contains(valueNorm)) {
            throw new InvalidCommandException("value không hợp lệ: " + value
                    + " (hợp lệ: " + Command.VALUES + ")");
        }

        Command command = new Command();
        command.setCmdId("c-" + UUID.randomUUID().toString().substring(0, 12));
        command.setDeviceId(deviceId);
        command.setTarget(targetNorm);
        command.setValue(valueNorm);
        command.setStatus(Command.STATUS_PENDING);
        command.setCreatedBy(createdBy);
        command.setSentAt(OffsetDateTime.now(UTC));
        commandRepo.save(command);
        realtimePublisher.commandUpdated(command);

        MqttPublisher publisher = mqttPublisherProvider.getIfAvailable();
        if (publisher == null) {
            log.warn("lệnh {}: MQTT đang tắt (mqtt.enabled=false) nên chưa publish xuống thiết bị",
                    command.getCmdId());
        } else {
            publisher.publishCommand(deviceId,
                    new CommandPayload(command.getCmdId(), targetNorm, valueNorm));
        }
        return command;
    }

    /** Lịch sử lệnh của thiết bị, mới nhất đứng đầu (GET .../commands). */
    public org.springframework.data.domain.Page<Command> history(String deviceId, int page, int limit) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(
                Math.max(page, 0), Math.min(Math.max(limit, 1), 500));
        return commandRepo.findByDeviceIdOrderBySentAtDesc(deviceId, pageable);
    }

/**
     * Xử lý sh/{deviceId}/ack: result=ok -> DONE, ngược lại ERROR (kèm acked_at).
     * Ack trùng (lệnh đã DONE/ERROR/TIMEOUT) thì bỏ qua để trạng thái cuối không bị đổi.
     */
    @Transactional
    public boolean onAck(String deviceId, String payload) {
        if (Payloads.isBlank(payload)) {
            log.warn("ack: payload rỗng từ thiết bị '{}'", deviceId);
            return false;
        }
        Optional<AckPayload> parsed = Payloads.parse(objectMapper, payload, AckPayload.class);
        if (parsed.isEmpty()) {
            log.warn("ack: JSON không hợp lệ từ '{}': {}", deviceId, Payloads.preview(payload));
            return false;
        }
        AckPayload body = parsed.get();
        if (Payloads.isBlank(body.cmdId())) {
            log.warn("ack: thiếu cmdId từ thiết bị '{}'", deviceId);
            return false;
        }

        Optional<Command> found = commandRepo.findByCmdId(body.cmdId());
        if (found.isEmpty()) {
            log.warn("ack: không thấy lệnh '{}' trong DB - bỏ qua", body.cmdId());
            return false;
        }
        Command command = found.get();
        // Chống ack chéo: lệnh gửi cho node này không được ack từ deviceId khác
        if (!command.getDeviceId().equals(deviceId)) {
            log.warn("ack: lệnh '{}' thuộc thiết bị '{}' nhưng ack đến từ '{}' - bỏ qua",
                    body.cmdId(), command.getDeviceId(), deviceId);
            return false;
        }
        if (!Command.STATUS_PENDING.equals(command.getStatus())) {
            log.warn("ack: lệnh '{}' đã ở trạng thái {} - bỏ qua ack trùng", body.cmdId(), command.getStatus());
            return false;
        }

        command.setStatus(RESULT_OK.equalsIgnoreCase(body.result()) ? Command.STATUS_DONE : Command.STATUS_ERROR);
        command.setAckedAt(OffsetDateTime.now(UTC));
        commandRepo.save(command);
        realtimePublisher.commandUpdated(command);

        log.info("ack: lệnh '{}' của '{}' -> {} (result={})",
                command.getCmdId(), deviceId, command.getStatus(), body.result());
        return true;
    }
}