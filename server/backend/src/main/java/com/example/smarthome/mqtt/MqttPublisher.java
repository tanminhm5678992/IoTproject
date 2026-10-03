package com.example.smarthome.mqtt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import com.example.smarthome.config.MqttConfig;
import com.example.smarthome.config.MqttProperties;
import com.example.smarthome.dto.CommandPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Publish lệnh điều khiển xuống thiết bị qua topic sh/{deviceId}/cmd (mục 2, QoS 1). */
@Component
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MqttPublisher {

    private static final Logger log = LoggerFactory.getLogger(MqttPublisher.class);

    private final MessageChannel mqttOutChannel;
    private final ObjectMapper objectMapper;
    private final MqttProperties props;

    public MqttPublisher(@Qualifier(MqttConfig.OUTBOUND_CHANNEL) MessageChannel mqttOutChannel,
                         ObjectMapper objectMapper,
                         MqttProperties props) {
        this.mqttOutChannel = mqttOutChannel;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /**
     * Gửi một lệnh tới thiết bị.
     *
     * @param deviceId deviceId của thiết bị (node2...)
     * @param command  cmdId + target + value
     * @return payload JSON đã gửi (tiện cho log/test)
     */
    public String publishCommand(String deviceId, CommandPayload command) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId không được để trống khi gửi lệnh");
        }
        String topic = "sh/" + deviceId + "/cmd";
        String json;
        try {
            json = objectMapper.writeValueAsString(command);
        } catch (JsonProcessingException e) {
            // Payload do backend tự sinh nên lỗi này là lỗi lập trình, không phải lỗi thiết bị
            throw new IllegalStateException("Không tạo được JSON cho lệnh " + command.cmdId(), e);
        }
        boolean sent = mqttOutChannel.send(MessageBuilder.withPayload(json)
                .setHeader(MqttHeaders.TOPIC, topic)
                .setHeader(MqttHeaders.QOS, props.getQos())
                .setHeader(MqttHeaders.RETAINED, false)
                .build());
        if (sent) {
            log.info("Đã publish lệnh {} -> {} : {}", command.cmdId(), topic, json);
        } else {
            log.warn("Publish lệnh {} -> {} không được nhận (channel đóng?)", command.cmdId(), topic);
        }
        return json;
    }
}
