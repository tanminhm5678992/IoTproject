package com.example.smarthome.mqtt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import com.example.smarthome.config.MqttConfig;
import com.example.smarthome.service.CommandService;
import com.example.smarthome.service.DeviceService;
import com.example.smarthome.service.TelemetryService;

/**
 * Nhận message từ broker SERVER (mục 9) và tách topic sh/{deviceId}/{kind}
 * để gọi đúng service nghiệp vụ.
 */
@Component
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MqttInboundHandler {

    private static final Logger log = LoggerFactory.getLogger(MqttInboundHandler.class);

    private final DeviceService deviceService;
    private final TelemetryService telemetryService;
    private final CommandService commandService;

    public MqttInboundHandler(DeviceService deviceService,
                              TelemetryService telemetryService,
                              CommandService commandService) {
        this.deviceService = deviceService;
        this.telemetryService = telemetryService;
        this.commandService = commandService;
    }

    @ServiceActivator(inputChannel = MqttConfig.INBOUND_CHANNEL)
    public void handle(Message<?> message) {
        String topic = (String) message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC);
        String payload = String.valueOf(message.getPayload());
        try {
            String[] parts = topic == null ? new String[0] : topic.split("/");
            // Dạng hợp lệ: sh / {id} / {kind}
            if (parts.length != 3) {
                log.warn("MQTT: topic không đúng dạng sh/{{id}}/{{kind}}: {}", topic);
                return;
            }
            String deviceId = parts[1];
            switch (parts[2]) {
                case "register" -> deviceService.onRegister(deviceId, payload);
                case "telemetry" -> telemetryService.onTelemetry(deviceId, payload);
                case "status" -> deviceService.onStatus(deviceId, payload);
                case "ack" -> commandService.onAck(deviceId, payload);
                default -> log.warn("MQTT: loại message lạ '{}' trên topic {}", parts[2], topic);
            }
        } catch (Exception e) {
            // Một message lỗi không được làm chết luồng nhận của adapter
            log.warn("MQTT: bỏ qua message trên topic {} (lý do: {})", topic, e.toString());
        }
    }
}
