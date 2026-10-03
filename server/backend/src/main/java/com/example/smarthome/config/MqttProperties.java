package com.example.smarthome.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình MQTT (mục 9 - application.yml). Giá trị mặc định trùng với docker-compose
 * (broker-server, /certs/ca.crt); khi chạy trên máy thật thì truyền biến môi trường
 * MQTT_URL / MQTT_CA_CERT / MQTT_USERNAME / MQTT_PASSWORD.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mqtt")
public class MqttProperties {

    /** Cho phép tắt MQTT (test tự động không cần broker thật). */
    private boolean enabled = true;

    /** ssl://broker-server:8883 (trong compose) hoặc ssl://<SERVER_HOST>:<SERVER_PORT>. */
    private String url = "ssl://broker-server:8883";

    /** Tài khoản backend trên broker SERVER (ACL: đọc sh/#, ghi sh/+/cmd). */
    private String username = "backend_user";

    private String password = "";

    /** File ca.crt dùng để xác thực broker server (self-signed CA của đồ án). */
    private String caCert = "/certs/ca.crt";

    /** clientId gốc; adapter tự thêm hậu tố -sub và -pub. */
    private String clientId = "smarthome-backend";

    /** QoS cho cả hai chiều (đặc tả mục 2: bridge Qos 1, lệnh cmd Qos 1). */
    private int qos = 1;

    private int keepAliveSeconds = 30;

    private int connectionTimeoutSeconds = 10;

    /** Thời gian chờ tối đa giữa các lần thử nối lại khi broker chưa sẵn sàng (ms). */
    private long recoveryIntervalMs = 5000;
}
