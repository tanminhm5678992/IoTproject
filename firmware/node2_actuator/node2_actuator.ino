/**
 * =============================================================================
 * node2_actuator.ino - Firmware node ĐIỀU KHIỂN & CẢM BIẾN (Phase 4, đặc tả mục 7)
 *
 * Phần cứng:
 *   DHT11  = GPIO4
 *   LED1   = GPIO5
 *   LED2   = GPIO10
 *   RELAY1 = GPIO6
 *   RELAY2 = GPIO7
 *
 * Hành vi chính (theo yêu cầu Phase 4):
 *   1. WiFi chỉ 2.4 GHz, kết nối vào mạng khai báo trong secrets.h, tự nối lại.
 *   2. Backoff 3s -> 6s -> 12s -> 30s cho cả WiFi và MQTT (không delay() dài trong loop()).
 *   3. NTP TRƯỚC TLS: chưa có giờ đúng thì KHÔNG thử TLS (cert sẽ bị từ chối).
 *   4. MQTT TLS tới broker LOCAL (8883) với ROOT_CA trong secrets.h.
 *      LWT: sh/<id>/status = "offline" (QoS1, retained) khai báo ngay trong connect.
 *      Khi kết nối: publish "online" retained + subscribe sh/<id>/cmd + gửi register.
 *   5. register khi vừa kết nối MQTT và lặp lại mỗi 5 phút (backend xử lý trùng an toàn).
 *   6. telemetry mỗi 10 giây; DHT đọc lỗi thì BỎ QUA lần đó (không gửi NaN).
 *   7. Lệnh: parse JSON an toàn, kiểm tra target/value, LUÔN gửi ack (ok / error + lý do).
 *      Hỗ trợ: relay1, relay2, led1, led2.
 *   8. Relay và LED giữ trạng thái trong Preferences; CHỈ ghi flash khi trạng thái đổi.
 *      Khôi phục trạng thái cũ sau khi mất điện và có lại (Test T14).
 *      Khi mất MQTT: LED2 nhấp nháy 500ms để báo lỗi (trạng thái lệnh vẫn được giữ).
 *   9. Watchdog esp_task_wdt (TWDT) 30s: treo quá 30s -> tự reset.
 *  10. Log Serial có tiền tố [SYS] [WIFI] [NTP] [TLS] [MQTT] [DHT] [CMD] [ACK].
 *
 * LƯU Ý QoS: PubSubClient phiên bản 2.8 CHỈ publish được QoS 0 (subscribe vẫn QoS 1
 * theo tham số của broker). Bridge LOCAL -> SERVER khai báo "out 1" nên message được
 * nâng lên QoS 1 khi qua cầu. Đây là giới hạn của thư viện, không phải lỗi firmware.
 *
 * secrets.h KHÔNG commit (chứa mật khẩu WiFi). Sinh file bằng ./scripts/gen-secrets.sh
 * rồi tự điền SSID/mật khẩu WiFi.
 * =============================================================================
 */

#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <DHT.h>
#include <Preferences.h>
#include <time.h>
#include <esp_task_wdt.h>
#if __has_include(<esp_arduino_version.h>)
#include <esp_arduino_version.h>
#endif

#include "secrets.h"

// -----------------------------------------------------------------------------
// Hằng số cấu hình (đổi ở đây, không rải rác trong code)
// -----------------------------------------------------------------------------
static const char FW_VERSION[] = "1.0.0";        // gửi trong payload register
static const char DEVICE_TYPE[] = "actuator";    // node2 = actuator (mục 7)

static const uint32_t TELEMETRY_INTERVAL_MS = 10000UL;   // 10 s -> dashboard cập nhật < 15 s
static const uint32_t REGISTER_INTERVAL_MS = 300000UL;   // register lặp mỗi 5 phút
static const uint32_t WIFI_CONNECT_TIMEOUT_MS = 15000UL; // 1 lần thử 1 mạng WiFi
static const uint32_t WIFI_POLL_MS = 300UL;              // nhịp kiểm tra WiFi khi đang thử
static const uint32_t WIFI_BOOT_DELAY_MS = 300UL;        // chờ Serial sẵn sàng ở setup()
static const uint32_t NTP_RETRY_MS = 5000UL;             // thử lấy giờ lại mỗi 5 s
static const uint32_t DHT_READ_MIN_MS = 2500UL;          // DHT11 cần >= 2.5 s giữa 2 lần đọc
static const uint32_t LED_BLINK_MS = 500UL;              // LED2 nháy khi lỗi MQTT

/** Backoff dùng chung cho WiFi và MQTT: 3s, 6s, 12s, tối đa 30s (mục 7 Phase 4). */
static const uint32_t BACKOFF_MS[] = {3000UL, 6000UL, 12000UL, 30000UL};
static const uint8_t BACKOFF_STEPS = sizeof(BACKOFF_MS) / sizeof(BACKOFF_MS[0]);

static const uint32_t WDT_TIMEOUT_S = 30;                // watchdog tự reset nếu treo
static const long TZ_OFFSET_SECONDS = 7L * 3600L;        // Việt Nam UTC+7
static const char NTP_SERVER_1[] = "pool.ntp.org";
static const char NTP_SERVER_2[] = "time.google.com";
/** Mốc coi là "đã có giờ thật" (14/11/2023). Nhỏ hơn => NTP chưa xong, chưa thử TLS. */
static const long TS_MIN_VALID = 1700000000L;

static const uint16_t MQTT_BUFFER_SIZE = 512;            // JSON ack + cmdId vẫn dư chỗ
static const uint16_t MQTT_KEEPALIVE_S = 30;
static const uint16_t MQTT_SOCKET_TIMEOUT_S = 15;
static const unsigned long TLS_HANDSHAKE_TIMEOUT_MS = 15000UL;

/** Giá trị REG_TOKEN mẫu trong secrets.h.example -> cảnh báo rõ khi chưa điền. */
static const char REG_TOKEN_PLACEHOLDER[] = "token_lay_tu_backend_khi_dang_ky";

// ---- Chân (khớp bảng đấu dây mục 7 và firmware/README.md) -----------------
#define DHT_PIN 4
#define LED1 5
#define LED2 10
#define RELAY1 6
#define RELAY2 7
#define DHT_TYPE DHT11

/** Relay thường active LOW (LOW = đóng relay/bật thiết bị). */
#define RELAY_ACTIVE_LEVEL LOW
#define RELAY_ON_LEVEL RELAY_ACTIVE_LEVEL
#define RELAY_OFF_LEVEL (RELAY_ACTIVE_LEVEL == HIGH ? LOW : HIGH)

/** LED thường active HIGH; nếu module LED active LOW thì đổi thành LOW. */
static const uint8_t LED_ACTIVE_LEVEL = HIGH;
#define LED_ON_LEVEL LED_ACTIVE_LEVEL
#define LED_OFF_LEVEL (LED_ACTIVE_LEVEL == HIGH ? LOW : HIGH)

// ---- Topic MQTT (DEVICE_ID lấy từ secrets.h) -------------------------------
static char TOPIC_STATUS[] = "sh/" DEVICE_ID "/status";
static char TOPIC_TELEMETRY[] = "sh/" DEVICE_ID "/telemetry";
static char TOPIC_REGISTER[] = "sh/" DEVICE_ID "/register";
static char TOPIC_CMD[] = "sh/" DEVICE_ID "/cmd";
static char TOPIC_ACK[] = "sh/" DEVICE_ID "/ack";

// -----------------------------------------------------------------------------
// Đối tượng toàn cục
// -----------------------------------------------------------------------------
WiFiClientSecure net;
PubSubClient mqtt(net);
DHT dht(DHT_PIN, DHT_TYPE);
Preferences prefs;

/** Mỗi hồ sơ = 1 mạng WiFi + IP broker LOCAL tương ứng. */
struct NetProfile {
  const char *ssid;
  const char *pass;
  const char *mqttHost;
};
#ifndef WIFI_NET_COUNT
#define WIFI_NET_COUNT 1
#endif
#if (WIFI_NET_COUNT == 1)
static const NetProfile NET_PROFILES[] = {
    {SSID_HOME, PASS_HOME, MQTT_HOST_HOME},
};
#elif (WIFI_NET_COUNT == 2)
static const NetProfile NET_PROFILES[] = {
    {SSID_HOME, PASS_HOME, MQTT_HOST_HOME},
    {SSID_LAPTOP, PASS_LAPTOP, MQTT_HOST_LAPTOP},
};
#else
static const NetProfile NET_PROFILES[] = {
    {SSID_HOME, PASS_HOME, MQTT_HOST_HOME},
    {SSID_LAPTOP, PASS_LAPTOP, MQTT_HOST_LAPTOP},
    {SSID_PHONE, PASS_PHONE, MQTT_HOST_PHONE},
};
#endif
static const uint8_t NET_PROFILE_COUNT = sizeof(NET_PROFILES) / sizeof(NET_PROFILES[0]);

// -----------------------------------------------------------------------------
// Trạng thái runtime
// -----------------------------------------------------------------------------
enum WifiPhase : uint8_t { WIFI_IDLE, WIFI_CONNECTING, WIFI_UP };

WifiPhase wifiPhase = WIFI_IDLE;
uint8_t netIdx = 0;                // hồ sơ mạng đang dùng / đang thử
uint8_t wifiBackoffIdx = 0;
uint32_t wifiAttemptStartMs = 0;
uint32_t lastWifiPollMs = 0;
uint32_t nextWifiAttemptMs = 0;

bool clockReady = false;           // NTP xong -> mới đủ điều kiện thử TLS
bool ntpStarted = false;           // đã gọi configTime() lần nào chưa
uint32_t nextNtpAttemptMs = 0;

uint8_t mqttHostNetIdx = 0xFF;     // hồ sơ mạng đã nạp vào mqtt.setServer() (0xFF = chưa)
uint8_t mqttBackoffIdx = 0;
uint32_t nextMqttAttemptMs = 0;

bool relay1State = false, relay2State = false;  // trạng thái relay
bool relay1Stored = false, relay2Stored = false;
bool led1State = false, led2State = false;      // trạng thái LED
bool led1Stored = false, led2Stored = false;
bool blinkPhase = false;
uint32_t lastBlinkMs = 0;

uint32_t lastTelemetryMs = 0;
uint32_t lastRegisterMs = 0;
uint32_t lastDhtReadMs = 0;
float lastTemp = NAN, lastHum = NAN;
uint32_t telemetryCount = 0;
uint32_t dhtErrorCount = 0;

// -----------------------------------------------------------------------------
// Tiện ích nhỏ
// -----------------------------------------------------------------------------
static const char *jsonBool(bool v) { return v ? "true" : "false"; }

/** Lấy delay backoff hiện tại (đã bão hoà 30 s) rồi tăng chỉ số cho lần sau. */
static uint32_t backoffDelay(uint8_t &idx) {
  uint32_t delayMs = BACKOFF_MS[idx < BACKOFF_STEPS ? idx : BACKOFF_STEPS - 1];
  if (idx < BACKOFF_STEPS - 1) {
    idx++;
  }
  return delayMs;
}

/** Xuất trạng thái Relay và LED ra chân vật lý. */
static void applyOutputs() {
  digitalWrite(RELAY1, relay1State ? RELAY_ON_LEVEL : RELAY_OFF_LEVEL);
  digitalWrite(RELAY2, relay2State ? RELAY_ON_LEVEL : RELAY_OFF_LEVEL);
  digitalWrite(LED1, led1State ? LED_ON_LEVEL : LED_OFF_LEVEL);
  digitalWrite(LED2, led2State ? LED_ON_LEVEL : LED_OFF_LEVEL);
}

/** Ghi Preferences CHỈ khi trạng thái thật sự đổi (chống mòn flash - mục 7). */
static void persistStateIfChanged() {
  if (relay1Stored != relay1State) {
    prefs.putBool("relay1", relay1State);
    relay1Stored = relay1State;
    Serial.printf("[CMD] Lưu relay1=%s vào Preferences\n", jsonBool(relay1State));
  }
  if (relay2Stored != relay2State) {
    prefs.putBool("relay2", relay2State);
    relay2Stored = relay2State;
    Serial.printf("[CMD] Lưu relay2=%s vào Preferences\n", jsonBool(relay2State));
  }
  if (led1Stored != led1State) {
    prefs.putBool("led1", led1State);
    led1Stored = led1State;
    Serial.printf("[CMD] Lưu led1=%s vào Preferences\n", jsonBool(led1State));
  }
  if (led2Stored != led2State) {
    prefs.putBool("led2", led2State);
    led2Stored = led2State;
    Serial.printf("[CMD] Lưu led2=%s vào Preferences\n", jsonBool(led2State));
  }
}

/** Publish payload JSON dựng sẵn trong char[] (tránh String trong loop -> đỡ phân mảnh heap). */
static bool mqttPublish(const char *topic, const char *payload, bool retained) {
  bool ok = mqtt.publish(topic, (const uint8_t *)payload, (unsigned int)strlen(payload), retained);
  Serial.printf("[MQTT] publish %s (len=%u, retained=%s) -> %s\n", topic,
                (unsigned)strlen(payload), retained ? "true" : "false", ok ? "OK" : "LỖI");
  return ok;
}

/** So sánh millis() an toàn với mốc thời gian (không bị tràn sau ~49 ngày). */
static bool timeReached(uint32_t now, uint32_t target) {
  return (int32_t)(now - target) >= 0;
}

// -----------------------------------------------------------------------------
// WiFi: kết nối backoff theo BACKOFF_MS, không dùng delay() dài
// -----------------------------------------------------------------------------
static void wifiSetPhase(WifiPhase phase) { wifiPhase = phase; }

static void startNtp();

/** Bắt đầu thử kết nối hồ sơ mạng thứ idx. */
static void wifiStartAttempt(uint8_t idx) {
  netIdx = idx;
  const NetProfile &p = NET_PROFILES[idx];
  Serial.printf("[WIFI] Thử hồ sơ %u/%u: SSID='%s'\n", (unsigned)(idx + 1),
                (unsigned)NET_PROFILE_COUNT, p.ssid);
  WiFi.disconnect();
  delay(100);
  WiFi.begin(p.ssid, p.pass);
  wifiSetPhase(WIFI_CONNECTING);
  wifiAttemptStartMs = millis();
  lastWifiPollMs = millis();
}

/** Chuyển sang hồ sơ kế tiếp (dò vòng) sau khi hết thời gian chờ. */
static void wifiTryNextProfile() {
  uint8_t next = (uint8_t)((netIdx + 1) % NET_PROFILE_COUNT);
  uint32_t waitMs = backoffDelay(wifiBackoffIdx);
  Serial.printf("[WIFI] Không nối được '%s' sau %lus -> thử hồ sơ kế tiếp sau %lums "
                "(đã dò %u/%u mạng)\n",
                NET_PROFILES[netIdx].ssid, (unsigned long)(WIFI_CONNECT_TIMEOUT_MS / 1000UL),
                (unsigned long)waitMs, (unsigned)(next + 1), (unsigned)NET_PROFILE_COUNT);
  wifiSetPhase(WIFI_IDLE);
  nextWifiAttemptMs = millis() + waitMs;
  netIdx = next;
}

/** Quản lý WiFi (gọi mỗi vòng loop, không chặn). */
static void wifiLoop() {
  uint32_t now = millis();

  if (WiFi.status() == WL_CONNECTED) {
    if (wifiPhase != WIFI_UP) {
      wifiSetPhase(WIFI_UP);
      wifiBackoffIdx = 0;
      Serial.printf("[WIFI] ĐÃ KẾT NỐI mạng '%s' | IP=%s | RSSI=%ld dBm | Kênh=%u\n",
                    WiFi.SSID().c_str(), WiFi.localIP().toString().c_str(),
                    (long)WiFi.RSSI(), (unsigned)WiFi.channel());
      Serial.printf("[NET] Broker LOCAL sẽ dùng: %s:%u\n",
                    NET_PROFILES[netIdx].mqttHost, (unsigned)MQTT_PORT);
      if (!ntpStarted) {
        ntpStarted = true;
        startNtp();
      }
    }
    return;
  }

  // Mất kết nối: ngắt MQTT cũ rồi dò lại mạng
  if (wifiPhase == WIFI_UP) {
    Serial.println("[WIFI] MẤT KẾT NỐI - ngắt MQTT và dò lại các hồ sơ mạng");
    if (mqtt.connected()) {
      mqtt.disconnect();
    }
    wifiSetPhase(WIFI_IDLE);
    wifiBackoffIdx = 0;
    netIdx = 0;
    nextWifiAttemptMs = now + BACKOFF_MS[0];
  }

  if (wifiPhase == WIFI_IDLE) {
    if (timeReached(now, nextWifiAttemptMs)) {
      wifiStartAttempt(netIdx);
    }
    return;
  }

  // WIFI_CONNECTING
  if (now - lastWifiPollMs >= WIFI_POLL_MS) {
    lastWifiPollMs = now;
    Serial.print('.');
  }
  if (now - wifiAttemptStartMs >= WIFI_CONNECT_TIMEOUT_MS) {
    wifiTryNextProfile();
  }
}

// -----------------------------------------------------------------------------
// NTP: PHẢI có giờ đúng TRƯỚC khi thử TLS
// -----------------------------------------------------------------------------
static void startNtp() {
  configTime(TZ_OFFSET_SECONDS, 0, NTP_SERVER_1, NTP_SERVER_2);
  Serial.printf("[NTP] Đồng bộ giờ với %s, %s (UTC+7)...\n", NTP_SERVER_1, NTP_SERVER_2);
}

static void ntpLoop() {
  uint32_t now = millis();
  if (clockReady || !ntpStarted) {
    return;
  }
  if (timeReached(now, nextNtpAttemptMs)) {
    nextNtpAttemptMs = now + NTP_RETRY_MS;
    long ts = (long)time(nullptr);
    if (ts >= TS_MIN_VALID) {
      clockReady = true;
      struct tm tmNow;
      localtime_r((time_t *)&ts, &tmNow);
      char buf[32];
      strftime(buf, sizeof(buf), "%Y-%m-%d %H:%M:%S", &tmNow);
      Serial.printf("[NTP] Đã có giờ: %s (epoch=%ld) -> đủ điều kiện bắt tay TLS\n", buf, ts);
    } else {
      Serial.printf("[NTP] Chưa có giờ hợp lệ (epoch=%ld) - thử lại sau %lus\n", ts,
                    (unsigned long)(NTP_RETRY_MS / 1000UL));
    }
  }
}

// -----------------------------------------------------------------------------
// Gửi dữ liệu lên backend (telemetry / register / ack)
// -----------------------------------------------------------------------------
static void sendRegister() {
  char payload[192];
  snprintf(payload, sizeof(payload),
           "{\"deviceId\":\"%s\",\"type\":\"%s\",\"fw\":\"%s\",\"regToken\":\"%s\"}", DEVICE_ID,
           DEVICE_TYPE, FW_VERSION, REG_TOKEN);
  Serial.printf("[MQTT] Gửi register (type=%s, fw=%s)\n", DEVICE_TYPE, FW_VERSION);
  mqttPublish(TOPIC_REGISTER, payload, false);
  if (strcmp(REG_TOKEN, REG_TOKEN_PLACEHOLDER) == 0) {
    Serial.println("[MQTT] CẢNH BÁO: REG_TOKEN trong secrets.h vẫn là giá trị mẫu!");
    Serial.println("[MQTT]   -> tạo thiết bị trên Swagger rồi copy regToken vào secrets.h");
  }
}

/** Telemetry mỗi 10 s: temp, hum, relay1, relay2, led1, led2, rssi, ts. */
static void sendTelemetry() {
  char payload[256];
  snprintf(payload, sizeof(payload),
           "{\"temp\":%.1f,\"hum\":%.1f,\"relay1\":%s,\"relay2\":%s,"
           "\"led1\":%s,\"led2\":%s,\"rssi\":%ld,\"ts\":%ld}",
           lastTemp, lastHum, jsonBool(relay1State), jsonBool(relay2State),
           jsonBool(led1State), jsonBool(led2State), (long)WiFi.RSSI(),
           (long)time(nullptr));
  mqttPublish(TOPIC_TELEMETRY, payload, false);
}

/** Ack cho mỗi lệnh nhận được. */
static void sendAck(const char *cmdId, const char *target, const char *value, const char *result,
                    const char *reason) {
  char payload[320];
  if (reason != nullptr && reason[0] != '\0') {
    snprintf(payload, sizeof(payload),
             "{\"cmdId\":\"%s\",\"result\":\"%s\",\"target\":\"%s\",\"value\":\"%s\","
             "\"reason\":\"%s\"}",
             cmdId, result, target, value, reason);
  } else {
    snprintf(payload, sizeof(payload),
             "{\"cmdId\":\"%s\",\"result\":\"%s\",\"target\":\"%s\",\"value\":\"%s\"}", cmdId,
             result, target, value);
  }
  Serial.printf("[ACK] cmdId=%s target=%s value=%s result=%s\n", cmdId, target, value, result);
  mqttPublish(TOPIC_ACK, payload, false);
}

// -----------------------------------------------------------------------------
// Callback lệnh: sh/node2/cmd -> {cmdId, target, value}
// Hỗ trợ: relay1, relay2, led1, led2
// -----------------------------------------------------------------------------
static void onMqttMessage(char *topic, byte *payload, unsigned int length) {
  Serial.printf("[CMD] Nhận message trên %s (len=%u)\n", topic, length);

  JsonDocument doc;
  DeserializationError err = deserializeJson(doc, payload, length);
  if (err) {
    Serial.printf("[CMD] JSON không hợp lệ (%s) - bỏ qua\n", err.c_str());
    return;
  }

  const char *cmdId = doc["cmdId"] | "";
  const char *target = doc["target"] | "";
  const char *value = doc["value"] | "";
  if (cmdId[0] == '\0') {
    Serial.println("[CMD] Thiếu cmdId - bỏ qua (không ack được)");
    return;
  }

  bool isOn = (strcmp(value, "ON") == 0) || (strcmp(value, "on") == 0);
  bool isOff = (strcmp(value, "OFF") == 0) || (strcmp(value, "off") == 0);
  if (!isOn && !isOff) {
    Serial.printf("[CMD] value không hợp lệ: '%s' (chỉ nhận ON/OFF)\n", value);
    sendAck(cmdId, target, value, "error", "value chi nhan ON/OFF");
    return;
  }

  if (strcmp(target, "relay1") == 0) {
    relay1State = isOn;
  } else if (strcmp(target, "relay2") == 0) {
    relay2State = isOn;
  } else if (strcmp(target, "led1") == 0) {
    led1State = isOn;
  } else if (strcmp(target, "led2") == 0) {
    led2State = isOn;
  } else {
    Serial.printf("[CMD] target không hợp lệ: '%s' (chỉ hỗ trợ relay1/relay2/led1/led2)\n", target);
    sendAck(cmdId, target, value, "error", "target chi ho tro relay1/relay2/led1/led2");
    return;
  }

  applyOutputs();             // đổi chân output ngay lập tức
  persistStateIfChanged();    // chỉ ghi flash khi thật sự đổi
  Serial.printf("[CMD] Áp dụng %s=%s\n", target, isOn ? "ON" : "OFF");
  sendAck(cmdId, target, value, "ok", nullptr);
}

// -----------------------------------------------------------------------------
// MQTT: TLS + LWT + subscribe + backoff
// -----------------------------------------------------------------------------
static void mqttApplyServer() {
  if (mqttHostNetIdx == netIdx) {
    return;
  }
  const NetProfile &p = NET_PROFILES[netIdx];
  Serial.printf("[TLS] Cài đặt CA (dài %u byte) và trỏ tới broker %s:%u\n",
                (unsigned)strlen(ROOT_CA), p.mqttHost, (unsigned)MQTT_PORT);
  net.setCACert(ROOT_CA);
  net.setHandshakeTimeout(TLS_HANDSHAKE_TIMEOUT_MS);
  mqtt.setServer(p.mqttHost, MQTT_PORT);
  mqttHostNetIdx = netIdx;
}

static bool mqttTryConnect() {
  Serial.printf("[MQTT] Đang kết nối broker %s:%u (user=%s, clientId=%s, TLS)...\n",
                NET_PROFILES[netIdx].mqttHost, (unsigned)MQTT_PORT, MQTT_USER, DEVICE_ID);

  bool ok = mqtt.connect(DEVICE_ID, MQTT_USER, MQTT_PASS, TOPIC_STATUS, 1, true, "offline");
  if (!ok) {
    Serial.printf("[MQTT] Kết nối THẤT BẠI (state=%d)\n", mqtt.state());
    return false;
  }

  Serial.println("[MQTT] ĐÃ KẾT NỐI!");
  mqttPublish(TOPIC_STATUS, "online", true);

  if (mqtt.subscribe(TOPIC_CMD, 1)) {
    Serial.printf("[MQTT] Đã subscribe %s (QoS 1)\n", TOPIC_CMD);
  } else {
    Serial.printf("[MQTT] LỖI khi subscribe %s\n", TOPIC_CMD);
  }

  sendRegister();
  lastRegisterMs = millis();
  lastTelemetryMs = millis();
  return true;
}

static void mqttLoop() {
  uint32_t now = millis();

  if (wifiPhase != WIFI_UP || !clockReady) {
    return;
  }

  mqttApplyServer();

  if (mqtt.connected()) {
    mqtt.loop();
    mqttBackoffIdx = 0;
    return;
  }

  if (timeReached(now, nextMqttAttemptMs)) {
    bool ok = mqttTryConnect();
    if (ok) {
      mqttBackoffIdx = 0;
      nextMqttAttemptMs = 0;
    } else {
      uint32_t waitMs = backoffDelay(mqttBackoffIdx);
      Serial.printf("[MQTT] Thử lại sau %lums (backoff bước %u/%u)\n",
                    (unsigned long)waitMs, (unsigned)mqttBackoffIdx, (unsigned)BACKOFF_STEPS);
      nextMqttAttemptMs = now + waitMs;
    }
  }
}

// -----------------------------------------------------------------------------
// Đọc DHT11 + LED báo lỗi
// -----------------------------------------------------------------------------
static void dhtReadIfDue(uint32_t now) {
  if (now - lastDhtReadMs < DHT_READ_MIN_MS) {
    return;
  }
  lastDhtReadMs = now;
  float t = dht.readTemperature();
  float h = dht.readHumidity();
  if (isnan(t) || isnan(h)) {
    dhtErrorCount++;
    if (dhtErrorCount % 3 == 1) {
      Serial.printf("[DHT] Đọc lỗi lần thứ %lu (NaN) - bỏ qua\n", (unsigned long)dhtErrorCount);
    }
    return;
  }
  if (t < -40.0f || t > 80.0f || h < 0.0f || h > 100.0f) {
    Serial.printf("[DHT] Giá trị ngoài miền hợp lệ (t=%.1f, h=%.1f) - bỏ qua\n", t, h);
    return;
  }
  lastTemp = t;
  lastHum = h;
  dhtErrorCount = 0;
  Serial.printf("[DHT] temp=%.1fC hum=%.1f%% (lần đọc hợp lệ thứ %lu)\n", t, h,
                (unsigned long)telemetryCount + 1);
}

/** LED2 nhấp nháy 500 ms khi CHƯA kết nối MQTT. */
static void ledStatusLoop(uint32_t now) {
  if (mqtt.connected()) {
    if (blinkPhase) {
      blinkPhase = false;
      applyOutputs();
    }
    return;
  }
  if (now - lastBlinkMs >= LED_BLINK_MS) {
    lastBlinkMs = now;
    blinkPhase = !blinkPhase;
    digitalWrite(LED2, blinkPhase ? LED_ON_LEVEL : LED_OFF_LEVEL);
  }
}

// -----------------------------------------------------------------------------
// setup() - chạy 1 lần
// -----------------------------------------------------------------------------
void setup() {
  Serial.begin(115200);
  delay(WIFI_BOOT_DELAY_MS);
  Serial.println();
  Serial.println("=====================================================");
  Serial.printf("[SYS] node2_actuator khởi động - fw %s, deviceId=%s\n", FW_VERSION, DEVICE_ID);
  Serial.printf("[SYS] Chip=%s rev%d, %u MHz, flash %u byte\n", ESP.getChipModel(),
                ESP.getChipRevision(), (unsigned)getCpuFrequencyMhz(), (unsigned)ESP.getFlashChipSize());
  Serial.println("[SYS] Thứ tự log khi chạy đúng: [WIFI] -> [NTP] -> [TLS] -> [MQTT] -> [DHT]");
  Serial.println("=====================================================");

  // Watchdog
#if defined(ESP_ARDUINO_VERSION_MAJOR) && ESP_ARDUINO_VERSION_MAJOR >= 3
  esp_task_wdt_config_t wdtConfig = {
      .timeout_ms = WDT_TIMEOUT_S * 1000U,
      .idle_core_mask = 0,
      .trigger_panic = true,
  };
  esp_err_t wdtErr = esp_task_wdt_reconfigure(&wdtConfig);
  if (wdtErr != ESP_OK) {
    Serial.printf("[SYS] esp_task_wdt_reconfigure lỗi 0x%X - thử init lại\n", (unsigned)wdtErr);
    esp_task_wdt_init(&wdtConfig);
  }
#else
  esp_task_wdt_init(WDT_TIMEOUT_S, true);
#endif
  esp_task_wdt_add(NULL);
  Serial.printf("[SYS] Watchdog TWDT đã bật: timeout %lus\n", (unsigned long)WDT_TIMEOUT_S);

  // Relay + LED + Preferences
  pinMode(RELAY1, OUTPUT);
  pinMode(RELAY2, OUTPUT);
  pinMode(LED1, OUTPUT);
  pinMode(LED2, OUTPUT);

  prefs.begin("state", false);
  relay1State = prefs.getBool("relay1", false);
  relay2State = prefs.getBool("relay2", false);
  led1State   = prefs.getBool("led1", false);
  led2State   = prefs.getBool("led2", false);
  relay1Stored = relay1State;
  relay2Stored = relay2State;
  led1Stored   = led1State;
  led2Stored   = led2State;
  applyOutputs();
  Serial.printf("[SYS] Khôi phục trạng thái từ flash: relay1=%s, relay2=%s, led1=%s, led2=%s\n",
                jsonBool(relay1State), jsonBool(relay2State), jsonBool(led1State), jsonBool(led2State));

  // DHT11
  dht.begin();
  Serial.printf("[DHT] Khởi tạo DHT11 trên GPIO%d\n", DHT_PIN);

  // WiFi
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.persistent(false);
  WiFi.onEvent([](WiFiEvent_t event, WiFiEventInfo_t info) {
    if (event == ARDUINO_EVENT_WIFI_STA_DISCONNECTED) {
      Serial.printf("\n[WIFI-EVENT] Mất kết nối, mã lý do: %d\n", info.wifi_sta_disconnected.reason);
    }
  });
  Serial.println("[WIFI] Chế độ STA, chỉ hỗ trợ 2.4 GHz");
  wifiStartAttempt(0);

  // MQTT
  mqtt.setCallback(onMqttMessage);
  mqtt.setBufferSize(MQTT_BUFFER_SIZE);
  mqtt.setKeepAlive(MQTT_KEEPALIVE_S);
  mqtt.setSocketTimeout(MQTT_SOCKET_TIMEOUT_S);
}

// -----------------------------------------------------------------------------
// loop() - KHÔNG có delay() dài; mọi thứ theo millis()
// -----------------------------------------------------------------------------
void loop() {
  uint32_t now = millis();
  esp_task_wdt_reset();

  wifiLoop();
  ntpLoop();
  mqttLoop();
  ledStatusLoop(now);
  dhtReadIfDue(now);

  if (mqtt.connected()) {
    if (now - lastTelemetryMs >= TELEMETRY_INTERVAL_MS) {
      lastTelemetryMs = now;
      if (dhtErrorCount == 0 && !isnan(lastTemp)) {
        telemetryCount++;
        sendTelemetry();
      } else {
        Serial.println("[DHT] Không gửi telemetry vì chưa có giá trị đo hợp lệ");
      }
    }

    if (now - lastRegisterMs >= REGISTER_INTERVAL_MS) {
      lastRegisterMs = now;
      sendRegister();
    }
  }
}
