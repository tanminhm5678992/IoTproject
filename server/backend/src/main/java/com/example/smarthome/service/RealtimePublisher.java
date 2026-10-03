package com.example.smarthome.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.example.smarthome.entity.Command;
import com.example.smarthome.entity.Device;
import com.example.smarthome.entity.Telemetry;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Đẩy dữ liệu realtime tới dashboard qua WebSocket STOMP (mục 9):
 *  - /topic/telemetry/{deviceId}  mỗi telemetry mới
 *  - /topic/devices                thay đổi online/offline (và register)
 *  - /topic/commands/{deviceId}    cập nhật trạng thái lệnh (PENDING/DONE/ERROR/TIMEOUT)
 * Không có broker WebSocket (test tự động) thì bỏ qua, không làm hỏng luồng nghiệp vụ.
 */
@Component
public class RealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

    private final ObjectProvider<SimpMessagingTemplate> templateProvider;
    private final ObjectMapper objectMapper;

    public RealtimePublisher(ObjectProvider<SimpMessagingTemplate> templateProvider,
                             ObjectMapper objectMapper) {
        this.templateProvider = templateProvider;
        this.objectMapper = objectMapper;
    }

    /** Telemetry vừa được lưu (sau khi TelemetryService lưu DB). */
    public void telemetrySaved(Telemetry t) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceId", t.getDeviceId());
        body.put("temp", t.getTemp());
        body.put("hum", t.getHum());
        body.put("relay1", t.getRelay1());
        body.put("relay2", t.getRelay2());
        body.put("recordedAt", t.getRecordedAt());
        send("/topic/telemetry/" + t.getDeviceId(), body);
    }

    /** Thiết bị đổi trạng thái (online/offline, register -> ACTIVE). */
    public void deviceChanged(Device d) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceId", d.getDeviceId());
        body.put("online", d.isOnline());
        body.put("regStatus", d.getRegStatus());
        body.put("fwVersion", d.getFwVersion());
        body.put("lastSeen", d.getLastSeen());
        send("/topic/devices", body);
    }

    /** Lệnh đổi trạng thái (tạo PENDING, ack -> DONE/ERROR, job -> TIMEOUT). */
    public void commandUpdated(Command c) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cmdId", c.getCmdId());
        body.put("deviceId", c.getDeviceId());
        body.put("target", c.getTarget());
        body.put("value", c.getValue());
        body.put("status", c.getStatus());
        body.put("createdBy", c.getCreatedBy());
        body.put("sentAt", c.getSentAt());
        body.put("ackedAt", c.getAckedAt());
        send("/topic/commands/" + c.getDeviceId(), body);
    }

    private void send(String destination, Map<String, Object> body) {
        Optional<SimpMessagingTemplate> template = Optional.ofNullable(templateProvider.getIfAvailable());
        if (template.isEmpty()) {
            return;
        }
        try {
            template.get().convertAndSend(destination, body);
            log.debug("WebSocket: đã đẩy {}", destination);
        } catch (Exception e) {
            // Lỗi gửi realtime không được làm hỏng nghiệp vụ chính
            log.warn("WebSocket: không đẩy được {}: {}", destination, e.toString());
        }
    }
}
