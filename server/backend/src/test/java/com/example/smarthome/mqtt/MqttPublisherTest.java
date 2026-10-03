package com.example.smarthome.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;

import com.example.smarthome.config.MqttProperties;
import com.example.smarthome.dto.CommandPayload;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Unit test MqttPublisher: đúng topic sh/{id}/cmd, QoS 1, retained=false (mục 2). */
@ExtendWith(MockitoExtension.class)
class MqttPublisherTest {

    @Mock
    private MessageChannel mqttOutChannel;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MqttPublisher publisher;

    @BeforeEach
    void setUp() {
        MqttProperties props = new MqttProperties();
        props.setQos(1);
        publisher = new MqttPublisher(mqttOutChannel, objectMapper, props);
    }

    @Test
    @DisplayName("publishCommand -> message có topic sh/node2/cmd, payload JSON, QoS 1, không retained")
    void publishDungTopicVaPayload() {
        when(mqttOutChannel.send(any(Message.class))).thenReturn(true);

        String json = publisher.publishCommand("node2", new CommandPayload("c-1", "relay1", "ON"));

        ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
        verify(mqttOutChannel).send(captor.capture());
        Message<?> message = captor.getValue();
        assertThat(message.getHeaders().get(MqttHeaders.TOPIC)).isEqualTo("sh/node2/cmd");
        assertThat(message.getHeaders().get(MqttHeaders.QOS)).isEqualTo(1);
        assertThat(message.getHeaders().get(MqttHeaders.RETAINED)).isEqualTo(false);
        assertThat(message.getPayload()).isEqualTo(json);
        assertThat(json).contains("\"cmdId\":\"c-1\"")
                .contains("\"target\":\"relay1\"")
                .contains("\"value\":\"ON\"");
    }

    @Test
    @DisplayName("deviceId rỗng -> IllegalArgumentException (không publish sai topic)")
    void deviceIdRongThiBaoLoi() {
        assertThatThrownBy(() -> publisher.publishCommand("  ", new CommandPayload("c-1", "relay1", "ON")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
