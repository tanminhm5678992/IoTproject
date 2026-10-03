package com.example.smarthome.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.smarthome.dto.Payloads;
import com.example.smarthome.dto.TelemetryPayload;
import com.example.smarthome.entity.Device;
import com.example.smarthome.entity.Telemetry;
import com.example.smarthome.repository.DeviceRepo;
import com.example.smarthome.repository.TelemetryRepo;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Nghiệp vụ telemetry (mục 9): chỉ nhận dữ liệu của thiết bị ACTIVE,
 * kiểm tra miền giá trị, lưu bảng telemetry, cập nhật last_seen và đẩy WebSocket.
 */
@Service
public class TelemetryService {

    /** Miền giá trị hợp lệ theo mục 9. */
    public static final float TEMP_MIN = -40f;
    public static final float TEMP_MAX = 80f;
    public static final float HUM_MIN = 0f;
    public static final float HUM_MAX = 100f;

    /** ts hợp lệ: từ 2000-01-01 và không vượt quá giờ nhận 1 ngày (thiết bị chưa có NTP). */
    private static final long TS_MIN_VALID = 946_684_800L;
    private static final long TS_MAX_FUTURE_SECONDS = 86_400L;

    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private final DeviceRepo deviceRepo;
    private final TelemetryRepo telemetryRepo;
    private final ObjectMapper objectMapper;
    private final RealtimePublisher realtimePublisher;

    public TelemetryService(DeviceRepo deviceRepo, TelemetryRepo telemetryRepo,
                            ObjectMapper objectMapper, RealtimePublisher realtimePublisher) {
        this.deviceRepo = deviceRepo;
        this.telemetryRepo = telemetryRepo;
        this.objectMapper = objectMapper;
        this.realtimePublisher = realtimePublisher;
    }

    /**
     * Xử lý sh/{deviceId}/telemetry.
     *
     * @return true nếu bản ghi được lưu vào DB
     */
    @Transactional
    public boolean onTelemetry(String deviceId, String payload) {
        Optional<Device> found = deviceRepo.findByDeviceId(deviceId);
        if (found.isEmpty()) {
            log.warn("telemetry: bỏ qua vì thiết bị '{}' chưa được đăng ký trong DB", deviceId);
            return false;
        }
        Device device = found.get();
        // Chỉ nhận dữ liệu từ thiết bị đã ACTIVE (mục 9)
        if (!Device.REG_ACTIVE.equals(device.getRegStatus())) {
            log.warn("telemetry: bỏ qua vì thiết bị '{}' đang ở trạng thái {}", deviceId, device.getRegStatus());
            return false;
        }
        if (Payloads.isBlank(payload)) {
            log.warn("telemetry: payload rỗng từ thiết bị '{}'", deviceId);
            return false;
        }
        Optional<TelemetryPayload> parsed = Payloads.parse(objectMapper, payload, TelemetryPayload.class);
        if (parsed.isEmpty()) {
            log.warn("telemetry: JSON không hợp lệ từ '{}': {}", deviceId, Payloads.preview(payload));
            return false;
        }
        TelemetryPayload body = parsed.get();

        String invalid = validate(body);
        if (invalid != null) {
            log.warn("telemetry: bỏ qua bản ghi của '{}' - {}", deviceId, invalid);
            return false;
        }

        OffsetDateTime now = OffsetDateTime.now(UTC);
        Telemetry telemetry = new Telemetry();
        telemetry.setDeviceId(deviceId);
        telemetry.setTemp(body.temp());
        telemetry.setHum(body.hum());
        telemetry.setRelay1(body.relay1());
        telemetry.setRelay2(body.relay2());
        telemetry.setRecordedAt(recordedAt(body.ts(), now));
        telemetryRepo.save(telemetry);

        device.setLastSeen(now);
        deviceRepo.save(device);
        realtimePublisher.telemetrySaved(telemetry);

        log.info("telemetry: đã lưu của '{}' (temp={}, hum={}, relay1={}, relay2={}, ts={})",
                deviceId, body.temp(), body.hum(), body.relay1(), body.relay2(), body.ts());
        return true;
    }

    // ---------------------------------------------------------------------
    // REST Phase 3: đọc lịch sử telemetry
    // ---------------------------------------------------------------------

    /** Bản ghi mới nhất của thiết bị (GET .../telemetry/latest). */
    public Optional<Telemetry> latest(String deviceId) {
        return telemetryRepo.findFirstByDeviceIdOrderByRecordedAtDesc(deviceId);
    }

    /**
     * Lịch sử telemetry có phân trang + lọc khoảng thời gian (from/to nullable).
     * Dùng JPA Specification: chỉ thêm predicate khi có giá trị, tránh lỗi bind
     * NULL của PostgreSQL ("could not determine data type of parameter").
     * limit do controller giới hạn 1..500, trang bắt đầu từ 0, mới nhất đứng đầu.
     */
    public Page<Telemetry> search(String deviceId, OffsetDateTime from, OffsetDateTime to,
                                  int page, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeLimit,
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "recordedAt"));
        Specification<Telemetry> spec = (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("deviceId"), deviceId));
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("recordedAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("recordedAt"), to));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return telemetryRepo.findAll(spec, pageable);
    }

    /** @return mô tả lỗi nếu có giá trị ngoài miền cho phép, null nếu bản ghi hợp lệ */
    private String validate(TelemetryPayload body) {
        if (body.temp() != null) {
            float temp = body.temp();
            if (Float.isNaN(temp) || Float.isInfinite(temp) || temp < TEMP_MIN || temp > TEMP_MAX) {
                return "temp=" + temp + " ngoài [" + TEMP_MIN + ", " + TEMP_MAX + "]";
            }
        }
        if (body.hum() != null) {
            float hum = body.hum();
            if (Float.isNaN(hum) || Float.isInfinite(hum) || hum < HUM_MIN || hum > HUM_MAX) {
                return "hum=" + hum + " ngoài [" + HUM_MIN + ", " + HUM_MAX + "]";
            }
        }
        return null;
    }

    /** ts = epoch giây; thiếu hoặc vô lý thì dùng giờ backend nhận message. */
    private OffsetDateTime recordedAt(Long ts, OffsetDateTime now) {
        if (ts == null || ts < TS_MIN_VALID || ts > now.toEpochSecond() + TS_MAX_FUTURE_SECONDS) {
            return now;
        }
        return Instant.ofEpochSecond(ts).atOffset(UTC);
    }
}
