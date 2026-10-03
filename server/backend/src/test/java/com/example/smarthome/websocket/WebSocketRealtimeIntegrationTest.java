package com.example.smarthome.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Type;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.example.smarthome.entity.Command;
import com.example.smarthome.entity.Device;
import com.example.smarthome.repository.CommandRepo;
import com.example.smarthome.repository.DeviceRepo;
import com.example.smarthome.repository.TelemetryRepo;
import com.example.smarthome.security.JwtService;
import com.example.smarthome.service.CommandService;
import com.example.smarthome.service.DeviceService;
import com.example.smarthome.service.TelemetryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Integration test WebSocket STOMP Phase 3 (mục 9) - chạy trên cổng HTTP thật:
 *  - kết nối /ws với JWT hợp lệ trong frame STOMP CONNECT -&gt; nhận được frame của
 *    /topic/telemetry/{id}, /topic/commands/{id} và /topic/devices;
 *  - thiếu token hoặc token sai -&gt; KHÔNG kết nối được (bị từ chối ở frame CONNECT).
 * MQTT tắt (mqtt.enabled=false); timeout job tắt để trạng thái lệnh không đổi giữa test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mqtt.enabled=false",
        "app.command-timeout.enabled=false",
        "jwt.secret=integration-test-secret-0123456789abcdef0123456789abcdef",
        "app.admin.username=admin-test",
        "app.admin.password=admin-test-pass-123",
        "app.cors.allowed-origins=http://localhost:5173,http://localhost"
})
@Testcontainers(disabledWithoutDocker = true)
class WebSocketRealtimeIntegrationTest {

    private static final String DEVICE_ID = "node9";
    private static final String ADMIN = "admin-test";
    /** Thời gian tối đa chờ 1 frame WebSocket tới (giây). */
    private static final long TIMEOUT_SECONDS = 5;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DeviceRepo deviceRepo;
    @Autowired
    private TelemetryRepo telemetryRepo;
    @Autowired
    private CommandRepo commandRepo;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private DeviceService deviceService;
    @Autowired
    private TelemetryService telemetryService;
    @Autowired
    private CommandService commandService;

    private WebSocketStompClient stompClient;
    private StompSession session;

    @BeforeEach
    void setUp() {
        commandRepo.deleteAll();
        telemetryRepo.deleteAll();
        deviceRepo.deleteAll();

        Device device = new Device();
        device.setDeviceId(DEVICE_ID);
        device.setName("Node 9");
        device.setType(Device.TYPE_ACTUATOR);
        device.setRegToken("token-node9");
        device.setRegStatus(Device.REG_ACTIVE);
        device.setOnline(true);
        deviceRepo.save(device);

        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        stompClient.start();
    }

    @AfterEach
    void tearDown() {
        if (session != null && session.isConnected()) {
            try {
                session.disconnect();
            } catch (Exception ignored) {
                // phiên đã đóng hoặc lỗi khi đóng - test đã chạy xong
            }
        }
        session = null;
        if (stompClient != null) {
            stompClient.stop();
        }
    }

    // ---- tiện ích -------------------------------------------------------------

    /**
     * Kết nối STOMP tới /ws; token null = không gửi header Authorization.
     *
     * @throws Exception nếu server từ chối (token sai/thiếu) hoặc quá thời gian chờ
     */
    private StompSession ketNoi(String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        if (token != null) {
            connectHeaders.add("Authorization", "Bearer " + token);
        }
        CompletableFuture<StompSession> connected = new CompletableFuture<>();
        stompClient.connect("ws://localhost:" + port + "/ws",
                (WebSocketHttpHeaders) null, connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession stompSession, StompHeaders connectedHeaders) {
                        connected.complete(stompSession);
                    }

                    @Override
                    public void handleException(StompSession stompSession, StompCommand command,
                                                StompHeaders headers, byte[] payload, Throwable exception) {
                        connected.completeExceptionally(exception);
                    }

                    @Override
                    public void handleTransportError(StompSession stompSession, Throwable exception) {
                        connected.completeExceptionally(exception);
                    }
                });
        // TimeoutException nếu server không bao giờ trả STOMP CONNECTED (bị từ chối âm thầm)
        return connected.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Đăng ký nhận 1 topic và chờ broker ghi nhận subscription. */
    private NhanFrame dangKy(String destination) throws Exception {
        NhanFrame handler = new NhanFrame(objectMapper);
        session.subscribe(destination, handler);
        // SUBSCRIBE xử lý bất đồng bộ - chờ broker ghi nhận trước khi tạo sự kiện
        Thread.sleep(500);
        return handler;
    }

    /** Chờ 1 frame tới; fail nếu quá thời gian. */
    private JsonNode nhanFrame(NhanFrame handler) throws InterruptedException {
        JsonNode frame = handler.poll(TIMEOUT_SECONDS);
        assertThat(frame).as("không nhận được frame WebSocket trong " + TIMEOUT_SECONDS + "s").isNotNull();
        return frame;
    }

    /** Hàng đợi frame của 1 subscription (payload luôn về dạng JSON). */
    private static final class NhanFrame implements StompFrameHandler {
        private final BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
        private final ObjectMapper objectMapper;

        private NhanFrame(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return JsonNode.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            try {
                if (payload instanceof JsonNode node) {
                    frames.add(node);
                } else if (payload instanceof byte[] bytes) {
                    frames.add(objectMapper.readTree(bytes));
                } else if (payload instanceof String text) {
                    frames.add(objectMapper.readTree(text));
                } else {
                    frames.add(objectMapper.valueToTree(payload));
                }
            } catch (Exception e) {
                frames.add(objectMapper.createObjectNode().put("parseError", String.valueOf(e)));
            }
        }

        private JsonNode poll(long seconds) throws InterruptedException {
            return frames.poll(seconds, TimeUnit.SECONDS);
        }
    }

    // ---- test -----------------------------------------------------------------

    @Test
    @DisplayName("WS: kết nối JWT hợp lệ -> nhận frame /topic/telemetry/{deviceId}")
    void nhanTelemetryQuaWebSocket() throws Exception {
        session = ketNoi(jwtService.generateToken(ADMIN, "ROLE_ADMIN"));
        NhanFrame handler = dangKy("/topic/telemetry/" + DEVICE_ID);

        assertThat(telemetryService.onTelemetry(DEVICE_ID,
                "{\"temp\":26.5,\"hum\":61.0,\"relay1\":true,\"relay2\":false}")).isTrue();

        JsonNode body = nhanFrame(handler);
        assertThat(body.get("deviceId").asText()).isEqualTo(DEVICE_ID);
        assertThat(body.get("temp").asDouble()).isEqualTo(26.5);
        assertThat(body.get("hum").asDouble()).isEqualTo(61.0);
        assertThat(body.get("relay1").asBoolean()).isTrue();
        assertThat(body.get("recordedAt").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("WS: nhận /topic/commands/{deviceId} - frame PENDING khi tạo lệnh và DONE khi ack")
    void nhanTrangThaiLenhQuaWebSocket() throws Exception {
        session = ketNoi(jwtService.generateToken(ADMIN, "ROLE_ADMIN"));
        NhanFrame handler = dangKy("/topic/commands/" + DEVICE_ID);

        Command command = commandService.sendCommand(DEVICE_ID, "relay1", "ON", ADMIN);
        JsonNode pending = nhanFrame(handler);
        assertThat(pending.get("cmdId").asText()).isEqualTo(command.getCmdId());
        assertThat(pending.get("status").asText()).isEqualTo(Command.STATUS_PENDING);
        assertThat(pending.get("value").asText()).isEqualTo("ON");

        assertThat(commandService.onAck(DEVICE_ID,
                "{\"cmdId\":\"" + command.getCmdId() + "\",\"result\":\"ok\"}")).isTrue();
        JsonNode done = nhanFrame(handler);
        assertThat(done.get("cmdId").asText()).isEqualTo(command.getCmdId());
        assertThat(done.get("status").asText()).isEqualTo(Command.STATUS_DONE);
        assertThat(done.get("ackedAt").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("WS: nhận /topic/devices khi thiết bị đổi online/offline")
    void nhanThayDoiTrangThaiThietBi() throws Exception {
        session = ketNoi(jwtService.generateToken(ADMIN, "ROLE_ADMIN"));
        NhanFrame handler = dangKy("/topic/devices");

        assertThat(deviceService.onStatus(DEVICE_ID, "offline")).isTrue();

        JsonNode body = nhanFrame(handler);
        assertThat(body.get("deviceId").asText()).isEqualTo(DEVICE_ID);
        assertThat(body.get("online").asBoolean()).isFalse();
        assertThat(body.get("regStatus").asText()).isEqualTo(Device.REG_ACTIVE);
    }

    @Test
    @DisplayName("WS: CONNECT thiếu Authorization -> bị từ chối (không có phiên)")
    void ketNoiThieuTokenBiTuChoi() {
        assertThrows(Exception.class, () -> ketNoi(null));
    }

    @Test
    @DisplayName("WS: CONNECT token sai -> bị từ chối (không có phiên)")
    void ketNoiTokenSaiBiTuChoi() {
        assertThrows(Exception.class, () -> ketNoi("khong-phai-token"));
    }
}
