package com.example.smarthome.service;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.smarthome.dto.DeviceCreateRequest;
import com.example.smarthome.dto.Payloads;
import com.example.smarthome.dto.RegisterPayload;
import com.example.smarthome.entity.Device;
import com.example.smarthome.exception.DeviceNotFoundException;
import com.example.smarthome.exception.DuplicateDeviceException;
import com.example.smarthome.repository.DeviceRepo;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Nghiệp vụ thiết bị (mục 9):
 *  - MQTT: xử lý topic register và status;
 *  - REST Phase 3: tạo (sinh regToken), xem, xóa thiết bị.
 */
@Service
public class DeviceService {

    /** Trạng thái gửi lên topic status (mục 2). */
    private static final String STATUS_ONLINE = "online";
    private static final String STATUS_OFFLINE = "offline";

    /** Sinh regToken: SecureRandom 32 byte -> 64 hex (đủ mạnh, vừa cột reg_token). */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceRepo deviceRepo;
    private final ObjectMapper objectMapper;
    private final RealtimePublisher realtimePublisher;

    public DeviceService(DeviceRepo deviceRepo, ObjectMapper objectMapper,
                         RealtimePublisher realtimePublisher) {
        this.deviceRepo = deviceRepo;
        this.objectMapper = objectMapper;
        this.realtimePublisher = realtimePublisher;
    }

    /**
     * Xử lý sh/{deviceId}/register.
     *
     * Điều kiện: thiết bị phải có trong DB và regToken phải khớp.
     * Idempotent: thiết bị gửi lại mỗi 5 phút, kết quả cuối vẫn là ACTIVE + online=true.
     *
     * @return true nếu thiết bị đã được xác thực token (đã chuyển ACTIVE)
     */
    @Transactional
    public boolean onRegister(String deviceId, String payload) {
        Optional<Device> found = deviceRepo.findByDeviceId(deviceId);
        if (found.isEmpty()) {
            log.warn("register: bỏ qua vì thiết bị '{}' chưa được đăng ký trong DB", deviceId);
            return false;
        }
        if (Payloads.isBlank(payload)) {
            log.warn("register: payload rỗng từ thiết bị '{}'", deviceId);
            return false;
        }
        Optional<RegisterPayload> parsed = Payloads.parse(objectMapper, payload, RegisterPayload.class);
        if (parsed.isEmpty()) {
            log.warn("register: JSON không hợp lệ từ '{}': {}", deviceId, Payloads.preview(payload));
            return false;
        }
        RegisterPayload body = parsed.get();
        if (body.deviceId() != null && !body.deviceId().equals(deviceId)) {
            log.warn("register: deviceId trong payload ('{}') khác deviceId trên topic ('{}') - bỏ qua",
                    body.deviceId(), deviceId);
            return false;
        }
        Device device = found.get();
        if (body.regToken() == null || !body.regToken().equals(device.getRegToken())) {
            log.warn("register: regToken sai cho thiết bị '{}' - bỏ qua", deviceId);
            return false;
        }

        boolean firstActivation = !Device.REG_ACTIVE.equals(device.getRegStatus());
        device.setRegStatus(Device.REG_ACTIVE);
        if (!Payloads.isBlank(body.fw())) {
            device.setFwVersion(body.fw().trim());
        }
        device.setOnline(true);
        device.setLastSeen(OffsetDateTime.now(UTC));
        deviceRepo.save(device);
        realtimePublisher.deviceChanged(device);

        log.info("register: thiết bị '{}' ACTIVE, online=true, fw={}{}", deviceId, device.getFwVersion(),
                firstActivation ? "" : " (nhận lặp lại - idempotent)");
        return true;
    }

/**
     * Xử lý sh/{deviceId}/status với payload "online" hoặc "offline" (LWT của thiết bị).
     * Payload lạ thì bỏ qua. Gọi lặp lại không gây lỗi.
     */
    @Transactional
    public boolean onStatus(String deviceId, String payload) {
        Optional<Device> found = deviceRepo.findByDeviceId(deviceId);
        if (found.isEmpty()) {
            log.warn("status: bỏ qua vì thiết bị '{}' chưa được đăng ký trong DB", deviceId);
            return false;
        }
        String value = payload == null ? "" : payload.trim().toLowerCase();
        boolean online;
        if (STATUS_ONLINE.equals(value)) {
            online = true;
        } else if (STATUS_OFFLINE.equals(value)) {
            online = false;
        } else {
            log.warn("status: payload không hợp lệ từ '{}' (chỉ chấp nhận online/offline): {}",
                    deviceId, Payloads.preview(payload));
            return false;
        }

        Device device = found.get();
        if (device.isOnline() == online) {
            log.debug("status: thiết bị '{}' đã ở trạng thái {} - bỏ qua", deviceId, value);
            return false;
        }
        device.setOnline(online);
        deviceRepo.save(device);
        realtimePublisher.deviceChanged(device);
        log.info("status: thiết bị '{}' -> {}", deviceId, value);
        return true;
    }

    // ---------------------------------------------------------------------
    // REST Phase 3: tạo / xem / xóa thiết bị
    // ---------------------------------------------------------------------

    /** Tra thiết bị theo deviceId hoặc ném DeviceNotFoundException -> HTTP 404. */
    public Device requireDevice(String deviceId) {
        return deviceRepo.findByDeviceId(deviceId)
                .orElseThrow(() -> new DeviceNotFoundException(deviceId));
    }

    /** Danh sách thiết bị (kèm telemetry mới nhất do controller tra). */
    public List<Device> listDevices() {
        return deviceRepo.findAll();
    }

    /**
     * POST /api/devices: tạo thiết bị PENDING với regToken sinh ngẫu nhiên (SecureRandom).
     *
     * @throws DuplicateDeviceException trùng deviceId -> HTTP 409
     * @throws IllegalArgumentException  type không phải sensor/actuator -> HTTP 400
     */
    @Transactional
    public Device createDevice(DeviceCreateRequest request) {
        if (deviceRepo.existsByDeviceId(request.deviceId())) {
            throw new DuplicateDeviceException(request.deviceId());
        }
        if (!Device.TYPES.contains(request.type())) {
            throw new IllegalArgumentException("type không hợp lệ: " + request.type()
                    + " (hợp lệ: sensor, actuator)");
        }
        byte[] random = new byte[32];
        SECURE_RANDOM.nextBytes(random);

        Device device = new Device();
        device.setDeviceId(request.deviceId());
        device.setName(request.name().trim());
        device.setType(request.type());
        device.setRegToken(HEX.formatHex(random));
        device.setRegStatus(Device.REG_PENDING);
        device.setOnline(false);
        device.setCreatedAt(OffsetDateTime.now(UTC));
        deviceRepo.save(device);
        log.info("device REST: đã tạo '{}' ({}), reg_status=PENDING", device.getDeviceId(), device.getType());
        return device;
    }

    /** DELETE /api/devices/{deviceId} (telemetry/lệnh tự xóa theo ON DELETE CASCADE). */
    @Transactional
    public void deleteDevice(String deviceId) {
        Device device = requireDevice(deviceId);
        deviceRepo.delete(device);
        log.info("device REST: đã xóa '{}' (kèm telemetry/lệnh theo cascade)", deviceId);
    }
}