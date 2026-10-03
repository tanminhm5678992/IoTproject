package com.example.smarthome.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.smarthome.entity.Device;
import com.example.smarthome.entity.Telemetry;
import com.example.smarthome.repository.CommandRepo;
import com.example.smarthome.repository.DeviceRepo;
import com.example.smarthome.repository.TelemetryRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Integration test REST API Phase 3 (MockMvc + PostgreSQL Testcontainers):
 *  - đăng nhập JWT (đúng / sai mật khẩu / thiếu token / token giả);
 *  - API thiết bị: tạo (201), trùng (409), sai dữ liệu (400), xóa (204/404);
 *  - API lệnh: hợp lệ (201 PENDING), offline (422), chưa ACTIVE (422),
 *    target sai (400), thiết bị không tồn tại (404), chuẩn hóa ON/OFF;
 *  - API telemetry: phân trang (page/limit) + bản ghi mới nhất;
 *  - CSRF: tắt (JWT ở header, không cookie) + không phát hành cookie phiên;
 *  - CORS: chỉ origin trong app.cors.allowed-origins được phép, origin lạ -> 403.
 * MQTT tắt (mqtt.enabled=false); timeout job tắt để không đổi trạng thái lệnh giữa test.
 */
@SpringBootTest(properties = {
        "mqtt.enabled=false",
        "app.command-timeout.enabled=false",
        "jwt.secret=integration-test-secret-0123456789abcdef0123456789abcdef",
        "app.admin.username=admin-test",
        "app.admin.password=admin-test-pass-123",
        // Danh sách origin mặc định (.env FRONTEND_ORIGIN) - KHÔNG đọc env khi chạy test
        "app.cors.allowed-origins=http://localhost:5173,http://localhost"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RestApiIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ADMIN = "admin-test";
    private static final String ADMIN_PASS = "admin-test-pass-123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DeviceRepo deviceRepo;
    @Autowired
    private TelemetryRepo telemetryRepo;
    @Autowired
    private CommandRepo commandRepo;

    @BeforeEach
    void clean() {
        commandRepo.deleteAll();
        telemetryRepo.deleteAll();
        deviceRepo.deleteAll();
    }

    // ---- tiện ích ------------------------------------------------------------

    private String dangNhap() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + ADMIN + "\",\"password\":\"" + ADMIN_PASS + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("token").asText();
    }

    private Device themThietBi(String regStatus, boolean online) {
        Device device = new Device();
        device.setDeviceId("node9");
        device.setName("Node 9");
        device.setType(Device.TYPE_ACTUATOR);
        device.setRegToken("token-node9");
        device.setRegStatus(regStatus);
        device.setOnline(online);
        return deviceRepo.save(device);
    }

    /** Tạo 1 lệnh hợp lệ qua REST (dùng cho test phân trang). */
    private void taoLenh(String token, String target, String value) throws Exception {
        mockMvc.perform(post("/api/devices/node9/commands")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"" + target + "\",\"value\":\"" + value + "\"}"))
                .andExpect(status().isCreated());
    }

    // ---- auth ----------------------------------------------------------------

    @Test
    @DisplayName("T-LOGIN: đăng nhập đúng -> 200 có token (cấu trúc JWT 3 phần)")
    void dangNhapDung() throws Exception {
        String token = dangNhap();
        org.assertj.core.api.Assertions.assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("T-LOGIN: sai mật khẩu -> 401 JSON thống nhất")
    void dangNhapSaiMatKhau() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + ADMIN + "\",\"password\":\"sai-mat-khau\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("T-AUTH: gọi API không kèm token -> 401")
    void goiApiKhongToken() throws Exception {
        mockMvc.perform(get("/api/devices"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("T-AUTH: token giả -> 401")
    void goiApiTokenGia() throws Exception {
        mockMvc.perform(get("/api/devices")
                        .header("Authorization", "Bearer khong-phai-token"))
                .andExpect(status().isUnauthorized());
    }

    // ---- API thiết bị --------------------------------------------------------

    @Test
    @DisplayName("T-DEV: POST /api/devices hợp lệ -> 201, có regToken (64 ký tự) + mqttTopics")
    void taoThietBi() throws Exception {
        String token = dangNhap();
        MvcResult result = mockMvc.perform(post("/api/devices")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"node9\",\"name\":\"Node 9\",\"type\":\"actuator\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceId").value("node9"))
                .andExpect(jsonPath("$.regToken").isNotEmpty())
                .andExpect(jsonPath("$.mqttTopics.cmd").value("sh/node9/cmd"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(body.get("regToken").asText()).hasSize(64);
    }

    @Test
    @DisplayName("T-DEV: trùng deviceId -> 409")
    void taoTrungDeviceId() throws Exception {
        String token = dangNhap();
        themThietBi(Device.REG_PENDING, false);
        mockMvc.perform(post("/api/devices")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"node9\",\"name\":\"Trung\",\"type\":\"sensor\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("T-DEV: type không hợp lệ -> 400")
    void taoSaiType() throws Exception {
        String token = dangNhap();
        mockMvc.perform(post("/api/devices")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"node9\",\"name\":\"Sai type\",\"type\":\"bongden\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("T-DEV: DELETE -> 204, rồi GET -> 404")
    void xoaThietBi() throws Exception {
        String token = dangNhap();
        themThietBi(Device.REG_ACTIVE, true);
        mockMvc.perform(delete("/api/devices/node9")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/devices/node9")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ---- API lệnh (T10-T12: kiểm tra mã lỗi) ---------------------------------

    @Test
    @DisplayName("T10-REST: ACTIVE + online, target hợp lệ -> 201 PENDING kèm cmdId")
    void lenhHopLe() throws Exception {
        String token = dangNhap();
        themThietBi(Device.REG_ACTIVE, true);
        mockMvc.perform(post("/api/devices/node9/commands")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"relay1\",\"value\":\"ON\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cmdId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdBy").value(ADMIN));
    }

    @Test
    @DisplayName("T12-REST: thiết bị offline -> 422 (đã chốt)")
    void lenhKhiOffline() throws Exception {
        String token = dangNhap();
        themThietBi(Device.REG_ACTIVE, false);
        mockMvc.perform(post("/api/devices/node9/commands")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"relay1\",\"value\":\"ON\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    @DisplayName("T12-REST: thiết bị chưa ACTIVE -> 422 (đã chốt)")
    void lenhChuaActive() throws Exception {
        String token = dangNhap();
        themThietBi(Device.REG_PENDING, true);
        mockMvc.perform(post("/api/devices/node9/commands")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"relay1\",\"value\":\"ON\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Lệnh: target không hợp lệ -> 400")
    void lenhTargetSai() throws Exception {
        String token = dangNhap();
        themThietBi(Device.REG_ACTIVE, true);
        mockMvc.perform(post("/api/devices/node9/commands")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"denden\",\"value\":\"ON\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Lệnh: thiết bị không tồn tại -> 404")
    void lenhThietBiKhongTonTai() throws Exception {
        String token = dangNhap();
        mockMvc.perform(post("/api/devices/khong-ton-tai/commands")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"relay1\",\"value\":\"ON\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Lệnh: GET lịch sử lệnh -> 200 (phân trang)")
    void lichSuLenh() throws Exception {
        String token = dangNhap();
        themThietBi(Device.REG_ACTIVE, true);
        mockMvc.perform(post("/api/devices/node9/commands")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"led1\",\"value\":\"OFF\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/devices/node9/commands")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].target").value("led1"));
    }

    // ---- chuẩn hóa ON/OFF (mục 2 - quy ước, mục 7) -----------------------------

    @Test
    @DisplayName("Lệnh: value 'on' + target ' RELAY1 ' -> 201, trả về relay1/'ON' (chuẩn hóa trước khi publish)")
    void lenhChuanHoaValueChuHoa() throws Exception {
        String token = dangNhap();
        themThietBi(Device.REG_ACTIVE, true);
        mockMvc.perform(post("/api/devices/node9/commands")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\" RELAY1 \",\"value\":\"on\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.target").value("relay1"))
                .andExpect(jsonPath("$.value").value("ON"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        org.assertj.core.api.Assertions.assertThat(commandRepo.findAll())
                .singleElement()
                .satisfies(c -> {
                    org.assertj.core.api.Assertions.assertThat(c.getTarget()).isEqualTo("relay1");
                    org.assertj.core.api.Assertions.assertThat(c.getValue()).isEqualTo("ON");
                });
    }

    @Test
    @DisplayName("Lệnh: value 'bat' (không phải ON/OFF) -> 400, không tạo lệnh")
    void lenhValueKhongPhaiOnOff() throws Exception {
        String token = dangNhap();
        themThietBi(Device.REG_ACTIVE, true);
        mockMvc.perform(post("/api/devices/node9/commands")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"relay1\",\"value\":\"bat\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        org.assertj.core.api.Assertions.assertThat(commandRepo.count()).isZero();
    }

    @Test
    @DisplayName("Lệnh: phân trang page/limit -> PageResponse total/page/size, mỗi trang 1 lệnh")
    void lichSuLenhPhanTrang() throws Exception {
        String token = dangNhap();
        themThietBi(Device.REG_ACTIVE, true);
        taoLenh(token, "led1", "ON");
        taoLenh(token, "relay2", "OFF");

        mockMvc.perform(get("/api/devices/node9/commands")
                        .param("page", "0").param("limit", "1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items.length()").value(1));

        mockMvc.perform(get("/api/devices/node9/commands")
                        .param("page", "1").param("limit", "1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    // ---- API telemetry ---------------------------------------------------------

    @Test
    @DisplayName("Telemetry: phân trang limit=1 (total=2) + /latest trả bản ghi mới nhất")
    void xemTelemetry() throws Exception {
        String token = dangNhap();
        Device device = themThietBi(Device.REG_ACTIVE, true);

        Telemetry cu = new Telemetry();
        cu.setDeviceId(device.getDeviceId());
        cu.setTemp(25.5f);
        cu.setHum(60f);
        cu.setRecordedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(5));
        telemetryRepo.save(cu);

        Telemetry moi = new Telemetry();
        moi.setDeviceId(device.getDeviceId());
        moi.setTemp(30.2f);
        moi.setHum(70f);
        moi.setRecordedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        telemetryRepo.save(moi);

        mockMvc.perform(get("/api/devices/node9/telemetry")
                        .param("limit", "1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].temp").value(30.2));

        // Trang 1 chứa bản ghi cũ hơn (recordedAt DESC) - kiểm chứng page thực sự lùi được
        mockMvc.perform(get("/api/devices/node9/telemetry")
                        .param("page", "1").param("limit", "1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].temp").value(25.5));

        mockMvc.perform(get("/api/devices/node9/telemetry/latest")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temp").value(30.2));
    }

    @Test
    @DisplayName("Telemetry: thiết bị không tồn tại -> 404")
    void telemetryThietBiKhongTonTai() throws Exception {
        String token = dangNhap();
        mockMvc.perform(get("/api/devices/khong-ton-tai/telemetry")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Telemetry: /latest khi thiết bị có thật nhưng chưa có bản ghi -> 404")
    void telemetryLatestChuaCoDuLieu() throws Exception {
        String token = dangNhap();
        themThietBi(Device.REG_ACTIVE, true);
        mockMvc.perform(get("/api/devices/node9/telemetry/latest")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("node9")));
    }

    @Test
    @DisplayName("GET /api/devices -> 200 kèm danh sách, latestTelemetry vắng mặt khi chưa có data")
    void danhSachThietBi() throws Exception {
        String token = dangNhap();
        themThietBi(Device.REG_ACTIVE, true);
        mockMvc.perform(get("/api/devices")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].deviceId").value("node9"))
                .andExpect(jsonPath("$[0].latestTelemetry").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("Swagger /v3/api-docs truy cập được không cần token")
    void openApiDocs() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/auth/login']").exists());
    }

    // ---- CSRF ------------------------------------------------------------------
    // JWT nằm trong header Authorization (không phải cookie) -> CSRF kinh điển không
    // gửi kèm được token, nên Spring Security TẮT CSRF cho /api. Test chứng minh:
    //   (a) endpoint thay đổi dữ liệu không yêu cầu CSRF token,
    //   (b) server không phát hành cookie phiên (không có JSESSIONID để bị lợi dụng).

    @Test
    @DisplayName("CSRF: POST/DELETE không cần CSRF token -> 201/204 (JWT ở header, không cookie)")
    void csrfKhongCanToken() throws Exception {
        String token = dangNhap();
        themThietBi(Device.REG_ACTIVE, true);

        mockMvc.perform(post("/api/devices/node9/commands")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"led1\",\"value\":\"ON\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/devices/node9")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("CSRF: không phát hành cookie phiên -> không có JSESSIONID để site khác lợi dụng")
    void csrfKhongPhatHanhCookiePhien() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + ADMIN + "\",\"password\":\"" + ADMIN_PASS + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"));

        String token = dangNhap();
        mockMvc.perform(get("/api/devices")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    // ---- CORS (app.cors.allowed-origins = FRONTEND_ORIGIN trong .env) -----------

    @Test
    @DisplayName("CORS: preflight từ origin frontend -> 200 + Access-Control-Allow-Origin")
    void corsChoOriginFrontend() throws Exception {
        mockMvc.perform(options("/api/devices")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "http://localhost:5173"));
    }

    @Test
    @DisplayName("CORS: origin http://localhost (danh sách cho phép từ .env) -> preflight 200")
    void corsChoOriginLocalhost() throws Exception {
        // Frontend mở từ http://localhost (cổng 80) gọi backend ở cổng 8080 -> là cross-origin,
        // nên MockMvc phải trỏ đúng URL có cổng thì Spring mới coi là request CORS.
        mockMvc.perform(options(java.net.URI.create("http://localhost:8080/api/devices"))
                        .header(HttpHeaders.ORIGIN, "http://localhost")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "http://localhost"));
    }

    @Test
    @DisplayName("CORS: origin lạ -> 403 và KHÔNG được cấp Access-Control-Allow-Origin (chặn CSRF chéo site)")
    void corsChanOriginLa() throws Exception {
        // Preflight của site lạ
        mockMvc.perform(options("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        org.hamcrest.Matchers.nullValue()));

        // Request thường có Origin của site lạ cũng bị chặn đọc response
        mockMvc.perform(post("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + ADMIN + "\",\"password\":\"" + ADMIN_PASS + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        org.hamcrest.Matchers.nullValue()));
    }
}
