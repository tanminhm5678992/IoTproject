package com.example.smarthome.mqtt;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import com.example.smarthome.service.CommandService;
import com.example.smarthome.service.DeviceService;
import com.example.smarthome.service.TelemetryService;

/**
 * Unit test MqttInboundHandler: tách topic sh/{deviceId}/{kind} và gọi đúng service,
 * message lỗi không được làm sập luồng nhận (mục 9).
 */
@ExtendWith(MockitoExtension.class)
class MqttInboundHandlerTest {

    @Mock
    private DeviceService deviceService;
    @Mock
    private TelemetryService telemetryService;
    @Mock
    private CommandService commandService;

    private MqttInboundHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MqttInboundHandler(deviceService, telemetryService, commandService);
    }

    private Message<String> message(String topic, String payload) {
        return MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.RECEIVED_TOPIC, topic)
                .build();
    }

    @Test
    @DisplayName("sh/node2/register -> DeviceService.onRegister")
    void routeRegister() {
        handler.handle(message("sh/node2/register", "{\"regToken\":\"abc\"}"));

        verify(deviceService).onRegister("node2", "{\"regToken\":\"abc\"}");
    }

    @Test
    @DisplayName("sh/node1/telemetry -> TelemetryService.onTelemetry")
    void routeTelemetry() {
        handler.handle(message("sh/node1/telemetry", "{\"temp\":27.5}"));

        verify(telemetryService).onTelemetry("node1", "{\"temp\":27.5}");
    }

    @Test
    @DisplayName("sh/node2/status -> DeviceService.onStatus")
    void routeStatus() {
        handler.handle(message("sh/node2/status", "online"));

        verify(deviceService).onStatus("node2", "online");
    }

    @Test
    @DisplayName("sh/node2/ack -> CommandService.onAck")
    void routeAck() {
        handler.handle(message("sh/node2/ack", "{\"cmdId\":\"c-1\",\"result\":\"ok\"}"));

        verify(commandService).onAck("node2", "{\"cmdId\":\"c-1\",\"result\":\"ok\"}");
    }

    @Test
    @DisplayName("Topic sai dạng hoặc loại lạ -> không gọi service nào")
    void routeTopicSai() {
        handler.handle(message("sh/node2", "online"));
        handler.handle(message("sh/node2/cmd/extra", "online"));
        handler.handle(message("sh/node2/unknown", "online"));

        verifyNoInteractions(deviceService, telemetryService, commandService);
    }

    @Test
    @DisplayName("Service ném exception -> handler không ném tiếp (không chết luồng nhận)")
    void khongNemExceptionRaNgoai() {
        org.mockito.Mockito.doThrow(new IllegalStateException("DB down"))
                .when(telemetryService).onTelemetry(eq("node1"), anyString());

        assertThatCode(() -> handler.handle(message("sh/node1/telemetry", "{\"temp\":27.5}")))
                .doesNotThrowAnyException();

        verify(telemetryService).onTelemetry("node1", "{\"temp\":27.5}");
    }
}
