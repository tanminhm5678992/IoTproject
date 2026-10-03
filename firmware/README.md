# Firmware ESP32-C3 - node1_sensor (Phase 4, đặc tả mục 7)

> **Commit được (đã bỏ khỏi `.gitignore` ngày 02/10/2026):** tài liệu này chỉ dùng placeholder
> (ví dụ `<ssid>`, `<IP hotspot>`, `<ADMIN_USERNAME>`, `<token>`) - không có IP/mật khẩu thật - nên
> là một phần hồ sơ Phase 4. Bí mật thật CHỈ nằm ở `firmware/**/secrets.h` (đã ignore, chỉ commit
> bản `.example`).

## 1. Kiến trúc và luồng dữ liệu

```text
ESP32-C3 (WiFi 2.4 GHz)
   |  MQTT over TLS, port 8883, ROOT_CA trong secrets.h
   v
broker LOCAL (mosquitto trên GATEWAY_IP) --bridge (out 1, in 1)--> broker SERVER
                                                                      |  MQTT
                                                                      v
                                                          backend Spring Boot
                                                          |            |
                                                     PostgreSQL    WebSocket STOMP
                                                                      v
                                                              dashboard React
```

Username MQTT của thiết bị = `deviceId` = `node1`. ACL broker LOCAL dùng `%u` nên tài khoản
`node1` CHỈ có quyền trên `sh/node1/...` (xem `broker-local/config/acl`).

### Topic MQTT của node1

| Topic | Chiều | Retained | Payload thực tế |
| --- | --- | --- | --- |
| `sh/node1/register` | thiết bị → server | không | `{"deviceId":"node1","type":"sensor","fw":"1.0.0","regToken":"<token>"}` |
| `sh/node1/telemetry` | thiết bị → server | không | `{"temp":28.5,"hum":60.0,"relay1":false,"relay2":false,"led1":true,"led2":false,"rssi":-55,"ts":1760000000}` |
| `sh/node1/status` | thiết bị → server | **có** | `online` khi vừa kết nối; `offline` do LWT khi mất kết nối |
| `sh/node1/cmd` | server → thiết bị | không | `{"cmdId":"<uuid>","target":"led1","value":"ON"}` |
| `sh/node1/ack` | thiết bị → server | không | `{"cmdId":"<uuid>","result":"ok","target":"led1","value":"ON"}` hoặc `... "result":"error","reason":"..."` |

### Nhịp thời gian (không có `delay()` dài trong `loop()`)

| Việc | Chu kỳ |
| --- | --- |
| telemetry | 10 s → dashboard cập nhật < 15 s |
| register | ngay khi kết nối MQTT + lặp mỗi 5 phút |
| đọc DHT11 | tối thiểu 2.5 s/lần (datasheet DHT11 yêu cầu ≥ 1 s, chọn 2.5 s cho an toàn) |
| backoff WiFi/MQTT | 3 s → 6 s → 12 s → 30 s (bão hoà 30 s) |
| NTP thử lại | 5 s/lần cho tới khi có giờ hợp lệ |
| watchdog TWDT | 30 s (treo → panic → tự reset) |
| MQTT | keepAlive 30 s, socketTimeout 15 s, buffer 512 B |

## 2. Sơ đồ đấu dây node1_sensor

Board tham chiếu: **ESP32-C3-DevKitM-1** (cũng đúng với DevKitC-02 / Super Mini: chỉ cần tìm
nhãn `IO4`/`IO5`/`IO6` trên board). Cột "Vị trí" là số chân header J1/J3 theo user guide của
Espressif - dùng để tra cho nhanh, KHÔNG bắt buộc phải đúng board đó.

| Linh kiện | Chân linh kiện | Nối tới ESP32 | Vị trí (DevKitM-1) | Ghi chú |
| --- | --- | --- | --- | --- |
| DHT11 | VCC | 3V3 | J1 pin 2 (hoặc 3) | 3.3 V, KHÔNG dùng 5 V |
| DHT11 | DATA | **GPIO4** | J3 pin 11 | cần điện trở kéo lên **10 kΩ** giữa DATA và 3V3 nếu module không có sẵn |
| DHT11 | GND | GND | J1 pin 1/6/8/12 hoặc J3 pin 12 | nối GND trước khi cắm dây tín hiệu |
| LED1 (đỏ) | Anode (+) | **GPIO5** | J3 pin 10 | nối tiếp điện trở 220 Ω → chân GPIO |
| LED1 | Cathode (−) | GND | J1 pin 1 | |
| LED2 (xanh) | Anode (+) | **GPIO6** | J3 pin 9 | nối tiếp điện trở 220 Ω → chân GPIO |
| LED2 | Cathode (−) | GND | J1 pin 1 | LED2 nhấp nháy 500 ms khi MQTT mất kết nối |
| Nguồn | USB Micro-B | - | - | cấp nguồn qua USB (5 V); nguồn ngoài thì dùng 5V ở J1 pin 13/14 |

Thứ tự thao tác an toàn: **rút USB trước khi cắm/rút dây**, cắm GND trước, cắm VCC sau cùng.

Chân **không** dùng và lý do: GPIO2/GPIO8/GPIO9 là chân strapping (dễ hỏng boot), GPIO8 là LED
RGB onboard, GPIO18/GPIO19 là USB D-/D+ (cổng USB native), GPIO20/GPIO21 là UART0 (USB-UART
bridge - dùng để nạp và xem log). Firmware chỉ dùng GPIO4/5/6 nên không đụng gì tới các chân này.

Mức tích cực của LED/relay (đổi trong code nếu module của bạn ngược lại):

| Hằng số | Giá trị trong `node1_sensor.ino` | Khi nào phải đổi |
| --- | --- | --- |
| `LED_ACTIVE_LEVEL` | `HIGH` | module LED **active LOW** (chân LOW mới sáng) → đổi thành `LOW` |
| `RELAY_ACTIVE_LEVEL` | `LOW` (chỉ có ở node2) | module relay **active HIGH** → đổi thành `HIGH` |

node1 là node CẢM BIẾN nên **không có relay**; nếu backend gửi lệnh `target=relay1|relay2`
xuống node1, firmware trả `ack error "node1 khong co relay"` (để lệnh đóng thành ERROR thay vì
TIMEOUT).

## 3. Phần mềm: core, thư viện, board, cách nạp

### 3.1 Phiên bản đã dùng để biên dịch (đã xác nhận `exit=0`, không warning)

| Thành phần | Phiên bản | Nguồn |
| --- | --- | --- |
| esp32 core | **3.3.11** (`esp32:esp32`) | Boards Manager / `arduino-cli core install esp32:esp32@3.3.11` |
| ArduinoJson | **7.4.3** | Library Manager |
| DHT sensor library (Adafruit) | **1.4.7** | Library Manager |
| Adafruit Unified Sensor | **1.1.15** | cài tự động cùng DHT sensor library |
| PubSubClient (Nick O'Leary) | **2.8** | Library Manager |
| WiFi, NetworkClientSecure, Preferences, esp_task_wdt | có sẵn trong esp32 core 3.3.11 | KHÔNG cài thêm |
| WiFiManager | 2.0.17 (đang có trong máy) | **firmware không dùng** - không cần cài |

Cài bằng dòng lệnh (thay `arduino-cli` bằng đường dẫn đầy đủ nếu chưa có trong PATH):

```bash
arduino-cli core install esp32:esp32@3.3.11
arduino-cli lib install "PubSubClient@2.8" "ArduinoJson@7.4.3" \
                        "DHT sensor library@1.4.7" "Adafruit Unified Sensor@1.1.15"
```

Cài bằng Arduino IDE 2.x: **Tools → Board → Boards Manager** (`esp32` by Espressif, 3.3.11) và
**Sketch → Include Library → Manage Libraries** (cài đúng 4 thư viện ở bảng trên).

### 3.2 Cấu hình Tools trong Arduino IDE (bắt buộc đúng)

| Mục | Giá trị |
| --- | --- |
| Board | **ESP32C3 Dev Module** (FQBN `esp32:esp32:esp32c3`) |
| USB CDC On Boot | **Enabled** - nếu để `Disabled` thì Serial Monitor im lặng dù code đúng |
| CPU Frequency | 160 MHz |
| Flash Size | 4 MB (32 Mb) |
| Flash Mode | QIO 80 MHz |
| Partition Scheme | Default 4 MB with spiffs (1.2 MB APP / 1.5 MB SPIFFS) |
| Upload Speed | 921600 (lỗi nạp thì hạ xuống 460800) |
| Port | cổng COM của board (Windows: Device Manager → Ports) |

Kích thước sau khi biên dịch (log `firmware/build-node1.log`, FQBN
`esp32:esp32:esp32c3:CDCOnBoot=cdc`): **1115743 byte (85 % flash)** và **38296 byte RAM (11 %)**
- vừa với partition 1.2 MB APP. Nếu để `USB CDC On Boot = Disabled` sẽ là 1120441 byte /
38120 byte RAM, nhưng như mục 3.2 **phải để Enabled**.

### 3.3 Biên dịch và nạp bằng `arduino-cli`

```bash
# biên dịch (không cần cắm board) - giữ đúng CDCOnBoot=cdc như mục 3.2
arduino-cli compile --fqbn esp32:esp32:esp32c3:CDCOnBoot=cdc firmware/node1_sensor

# nạp - thay COM5 bằng cổng thật (giữ nguyên CDCOnBoot=cdc để không biên dịch lại khác cấu hình)
arduino-cli upload -p COM5 --fqbn esp32:esp32:esp32c3:CDCOnBoot=cdc firmware/node1_sensor

# xem log 115200 baud
arduino-cli monitor -p COM5 -c baudrate=115200
```

Nạp bằng Arduino IDE: mở `firmware/node1_sensor/node1_sensor.ino`, chọn đúng board/port, bấm
Upload. Nếu board không vào được chế độ nạp: **giữ nút BOOT → bấm RESET → nhả BOOT**, rồi Upload
lại.

## 4. `secrets.h` - file cấu hình bí mật

`scripts/gen-secrets.sh` sinh `firmware/node1_sensor/secrets.h` từ
`firmware/common/secrets.h.example`, tự điền: `DEVICE_ID`, `MQTT_PASS` (từ `NODE1_PASSWORD`
trong `.env`), `MQTT_HOST_HOME/LAPTOP/PHONE` (từ `GATEWAY_IP_*`), và nội dung `ROOT_CA`
(`certs/out/ca.crt`).

Bạn **phải tự sửa** 2 thứ trong `secrets.h` sau khi sinh:

1. `SSID_*` / `PASS_*` của WiFi thật (script cố tình không điền) - ESP32-C3 **chỉ 2.4 GHz**.

   > **Đây là nguyên nhân số 1 khiến firmware "không kết nối được mạng".** Nếu để nguyên
   > placeholder, log Serial sẽ lặp mãi dạng:
   > `[WIFI] Thử hồ sơ 1/3: SSID='TEN_WIFI_NHA'` → `[WIFI] Không nối được 'TEN_WIFI_NHA' sau 15s`.
   > Ngoài ra vì mặc định `WIFI_NET_COUNT 3`, thiết bị còn mất thêm 15 s cho mỗi hồ sơ không tồn tại.
   > Nếu chỉ dùng **1 mạng**, đặt `#define WIFI_NET_COUNT 1` để vào mạng ngay ở lần thử đầu.
2. `REG_TOKEN` - copy từ Swagger sau khi tạo thiết bị `node1` trên backend (xem bước 8 ở mục 5).

```c
#define DEVICE_ID "node1"
#define MQTT_USER DEVICE_ID          // username MQTT = deviceId (ACL dùng %u)
#define MQTT_PASS "..."              // do gen-secrets.sh điền từ .env
#define REG_TOKEN "..."              // copy từ POST /api/devices
#define WIFI_NET_COUNT 3             // 1..3 hồ sơ mạng, dò lần lượt
static const char ROOT_CA[] PROGMEM = R"EOF(
-----BEGIN CERTIFICATE----- ... -----END CERTIFICATE-----
)EOF";
```

Lưu ý: comment trong file này PHẢI là `//`. Dùng `#` ở đầu dòng sẽ bị preprocessor hiểu là chỉ
thị và báo lỗi `invalid preprocessing directive` (đã gặp thật ở Phase 4, đã sửa trong
`secrets.h.example`).


## 5. Thứ tự cài đặt và nạp (làm đúng thứ tự)

Chạy trong **Git Bash** tại thư mục gốc dự án.

| # | Bước | Lệnh / thao tác | Kết quả phải thấy |
| --- | --- | --- | --- |
| 0 | Có cert + `.env` | `./scripts/set-network.sh <IP-gateway> [home\|laptop\|phone]` | sinh lại cert lá + SAN + `secrets.h` (IP phải là IP máy đang chạy broker LOCAL) |
| 1 | Bật 2 broker | `./scripts/up.sh` | 2 container mosquitto healthy |
| 2 | Tài khoản MQTT (1 lần) | `./scripts/setup-users.sh` | in ra `Tài khoản trên broker LOCAL: node1 node2 local_monitor` |
| 3 | Sinh `secrets.h` | `./scripts/gen-secrets.sh` | `firmware/node1_sensor/secrets.h` (đã có `MQTT_PASS`, `ROOT_CA`, host theo mạng) |
| 4 | Bật PostgreSQL | `./scripts/db.sh up` | container `smarthome-postgres` chạy |
| 5 | Dọn dữ liệu demo cũ | `./scripts/demo-reset.sh` | `DB sạch + admin '<ADMIN_USERNAME>' đã sẵn sàng cho demo` |
| 6 | Chạy backend | `./scripts/backend.sh run` | log `Started SmartHomeApplication`; REST `http://localhost:8080`; Swagger `http://localhost:8080/swagger-ui/index.html` |
| 7 | Đăng nhập Swagger | `POST /api/auth/login` `{"username":"<ADMIN_USERNAME>","password":"<ADMIN_PASSWORD>"}` → **Authorize** dán JWT | HTTP 200 + `token` |
| 8 | Tạo thiết bị node1 | `POST /api/devices` `{"deviceId":"node1","name":"Node 1 - cảm biến","type":"sensor"}` | HTTP 201 + **`regToken`** (copy ngay) |
| 9 | Điền `secrets.h` | dán `regToken` vào `REG_TOKEN`; điền `SSID_*`/`PASS_*` WiFi thật | không còn `TEN_WIFI_NHA` / `TEN_HOTSPOT_*` (WiFi), `CHANGE_ME_node1` (MQTT_PASS) và `token_lay_tu_backend_khi_dang_ky` (REG_TOKEN - thay bằng 64 ký tự hex) |
| 10 | Biên dịch | mục 3.3 | `exit=0`, không warning |
| 11 | Nạp firmware | mục 3.3 (Upload) | log Serial bắt đầu chạy (mục 6) |
| 12 | Kiểm tra end-to-end | mục 7 | telemetry lên dashboard, lệnh LED chuyển `PENDING → DONE` |

> **Thứ tự bước 1 ↔ 2 hoán đổi được:** `setup-users.sh` sửa file `passwd` trên host rồi gửi
> SIGHUP cho broker đang chạy; nếu broker chưa bật thì broker sẽ đọc file lúc khởi động.

> **Quan trọng:** `demo-reset.sh` xoá bảng `devices` → `regToken` cũ mất hiệu lực. Mỗi lần reset
> phải làm lại bước 8 và **nạp lại firmware** (hoặc ít nhất cập nhật `REG_TOKEN` rồi nạp lại);
> nếu không thiết bị vẫn kết nối MQTT nhưng backend từ chối register và giữ `PENDING`.

Nhanh hơn (không cần Swagger) - tạo thiết bị bằng curl:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$ADMIN_PASSWORD\"}" | jq -r .token)
curl -s -X POST http://localhost:8080/api/devices \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"deviceId":"node1","name":"Node 1 - cam bien","type":"sensor"}'
```

## 6. Checklist đọc log Serial (115200 baud) - thứ tự log khi chạy ĐÚNG

```text
[SYS] node1_sensor khởi động - fw 1.0.0, deviceId=node1
[SYS] Chip=ESP32-C3 rev3, 160 MHz, flash 4194304 byte
[SYS] Watchdog TWDT đã bật: timeout 30s
[SYS] Khôi phục trạng thái từ flash: led1=false, led2=false
[DHT] Khởi tạo DHT11 trên GPIO4
[WIFI] Chế độ STA, chỉ hỗ trợ 2.4 GHz
[MQTT] Cấu hình: buffer=512 byte, keepAlive=30s, socketTimeout=15s
[SYS] Setup xong - vào loop()
[WIFI] Thử hồ sơ 1/3: SSID='<tên WiFi nhà>'
.....                                            <- dấu chấm mỗi 300 ms khi đang thử
[WIFI] ĐÃ KẾT NỐI mạng '<ssid>' | IP=192.168.1.57 | RSSI=-58 dBm | Kênh=6
[NET] Broker LOCAL sẽ dùng: 192.168.1.10:8883
[NTP] Đồng bộ giờ với pool.ntp.org, time.google.com (UTC+7)...
[NTP] Đã có giờ: 2026-10-02 09:15:31 (epoch=1759...) -> đủ điều kiện bắt tay TLS
[TLS] Đã nạp ROOT_CA (1204 byte) cho broker 192.168.1.10:8883
[MQTT] Thử kết nối TLS tới 192.168.1.10:8883 (clientId=node1, user=node1)...
[MQTT] ĐÃ KẾT NỐI TLS thành công
[MQTT] Đã subscribe sh/node1/cmd (QoS1)
[MQTT] Gửi register (type=sensor, fw=1.0.0)
[DHT] temp=28.5C hum=61.0% (lần đọc hợp lệ thứ 1)
```

Sau đó cứ ~10 s một dòng telemetry không có log riêng, nhưng cứ ~2.5 s có dòng `[DHT] ...`.
Khi bấm nút trên dashboard (hoặc gửi lệnh bằng Swagger):

```text
[CMD] Nhận message trên sh/node1/cmd (len=52)
[CMD] Áp dụng led1=ON
[ACK] cmdId=6f1c... target=led1 value=ON result=ok
```

Lệnh sai target/value trên node1:

```text
[CMD] target 'relay1' không tồn tại trên node1_sensor
[ACK] cmdId=... target=relay1 value=ON result=error
[CMD] value không hợp lệ: 'BLA' (chỉ nhận ON/OFF)
[ACK] cmdId=... target=led1 value=BLA result=error
```

Thiết bị đang chờ WiFi/MQTT: LED2 nhấp nháy 500 ms và log có
`[MQTT] Thử lại sau 6000ms` (backoff tăng dần 3/6/12/30 s).


## 7. Checklist test trên thiết bị thật (làm lần lượt, ghi lại kết quả)

| # | Thao tác | Kết quả mong đợi | Đạt? |
| --- | --- | --- | --- |
| 1 | Cắm USB, mở Serial Monitor 115200 | Khối log khởi động như mục 6, kết thúc bằng `[SYS] Setup xong - vào loop()` | ☐ |
| 2 | Chờ 5-30 s | `[WIFI] ĐÃ KẾT NỐI ...` với IP trong dải WiFi của bạn | ☐ |
| 3 | Chờ tiếp | `[NTP] Đã có giờ: ...` (giờ UTC+7 đúng với đồng hồ máy) - bước này BẮT BUỘC trước TLS | ☐ |
| 4 | Chờ tiếp | `[TLS] Đã nạp ROOT_CA ...` rồi `[MQTT] ĐÃ KẾT NỐI TLS thành công` + `Đã subscribe sh/node1/cmd (QoS1)` | ☐ |
| 5 | `GET /api/devices/node1` trên Swagger | `status=ACTIVE`, `online=true` (đổi trong ~5 s nhờ `sh/node1/status=online` retained) | ☐ |
| 6 | Xem dòng `[DHT] temp=..., hum=...` | Có dòng hợp lệ trong ≤ 5 s; áp tay vào cảm biến thì `hum` tăng | ☐ |
| 7 | `GET /api/devices/node1/telemetry?page=0&limit=5` | Có bản ghi mới mỗi ~10 s | ☐ |
| 8 | Swagger `POST /api/devices/node1/commands` `{"target":"led1","value":"ON"}` | HTTP 201 + `cmdId`; log `[CMD] Áp dụng led1=ON` + `[ACK] ... result=ok`; **LED1 sáng**; lịch sử lệnh `PENDING → DONE` | ☐ |
| 9 | Gửi `{"target":"led1","value":"OFF"}` rồi RÚT ĐIỆN CẮM LẠI | Boot lại có `[SYS] Khôi phục trạng thái từ flash: led1=false` và LED1 vẫn tắt | ☐ |
| 10 | Gửi `{"target":"led2","value":"ON"}` rồi bấm RESET | Boot lại có `led2=true` | ☐ |
| 11 | Gửi `{"target":"relay1","value":"ON"}` (node1 không có relay) | Lệnh chuyển `ERROR` (không phải TIMEOUT), log `[ACK] ... result=error` | ☐ |
| 12 | Tắt WiFi/AP, chờ > keepAlive 30 s | Log `[WIFI] MẤT KẾT NỐI - ngắt MQTT và dò lại các hồ sơ mạng`; LED2 nhấp nháy 500 ms; `GET /api/devices/node1` báo `online=false` (LWT `offline` retained) | ☐ |
| 13 | Bật WiFi lại | Tự kết nối lại **không cần reset**: `[MQTT] Thử lại sau 3000ms` → `[MQTT] ĐÃ KẾT NỐI TLS thành công`; `online=true` trở lại | ☐ |
| 14 | Để chạy liên tục > 2 phút | Không treo, không reset ngẫu nhiên (nếu watchdog kích hoạt sẽ thấy log boot lại từ đầu) | ☐ |
| 15 | Đổi sang mạng WiFi khác (hotspot điện thoại) | `[WIFI] Không nối được '...' sau 15s -> thử hồ sơ kế tiếp sau ...` rồi kết nối hồ sơ 2; log `[NET] Broker LOCAL sẽ dùng: <IP hotspot>:8883` | ☐ |
| 16 | Gửi lệnh khi thiết bị OFFLINE | HTTP 422 từ backend, không có log gì trên thiết bị | ☐ |
| 17 | Gửi lệnh với `value` sai (`{"target":"led1","value":"BLA"}`) | HTTP 400 từ backend (chặn trước khi publish) | ☐ |


## 8. Lỗi thường gặp và cách xử lý

| Triệu chứng | Nguyên nhân | Cách xử lý |
| --- | --- | --- |
| `[MQTT] Kết nối THẤT BẠI (state=-4)` lặp dù WiFi đã `ĐÃ KẾT NỐI` | **SSID và IP broker bị lệch**: `NET_PROFILES[]` ghép cứng `SSID_HOME` ↔ `MQTT_HOST_HOME`, nên nếu mạng nhà thật có IP khác `192.168.1.10` thì broker đúng không được trỏ tới | chạy `./scripts/set-network.sh <IP-đang-dùng>` → `./scripts/gen-secrets.sh`, hoặc sửa tay `MQTT_HOST_*` trong `secrets.h`; xem giá trị đúng bằng `Get-NetIPAddress -AddressFamily IPv4` và `GATEWAY_IP` trong `.env` |
| `[NTP] Chưa có giờ hợp lệ (epoch=0)` lặp mãi | WiFi chưa ra Internet hoặc router chặn UDP 123 | kiểm tra mạng có Internet; đổi `NTP_SERVER_1` (`time.cloudflare.com`); TLS sẽ **không** được thử tới khi có giờ - đây là hành vi đúng |
| `[MQTT] Kết nối THẤT BẠI (state=-2)` | **giờ sai** (NTP lệch) hoặc **SAN cert không khớp IP** | so giờ trên Serial với đồng hồ máy; chạy `./scripts/set-network.sh <IP-đang-dùng>` để sinh lại cert đúng IP, rồi `./scripts/gen-secrets.sh` |
| `state=-2` lặp dù giờ đúng | `ROOT_CA` là CA cũ, hoặc `MQTT_HOST_*` không phải IP broker đang chạy | chạy lại `gen-secrets.sh`; kiểm tra `MQTT_HOST_*` khớp `GATEWAY_IP` trong `.env` |
| `state=4` / `state=5` (bad credentials) | `MQTT_PASS` không khớp passwd broker LOCAL | `./scripts/setup-users.sh` (đọc `NODE1_PASSWORD` từ `.env`) → `gen-secrets.sh` → nạp lại |
| `state=-4` (timeout) | broker LOCAL chưa chạy / sai port / firewall Windows chặn 8883 | `./scripts/up.sh`, `docker ps`, chạy `./scripts/windows-firewall.ps1` |
| Nạp xong, thiết bị online nhưng Swagger vẫn `PENDING` | `REG_TOKEN` sai/cũ (thường do chạy `demo-reset.sh` sau khi copy token) | tạo lại thiết bị (bước 8 mục 5), cập nhật `REG_TOKEN`, nạp lại |
| Lệnh luôn `TIMEOUT` dù thiết bị đã nhận lệnh | ack/QoS0 bị rơi, hoặc bridge/ACL có vấn đề | xem log backend có nhận `sh/node1/ack`; kiểm tra `bridge_user` + ACL; thử lại khi RSSI tốt |
| `[DHT] Đọc lỗi lần thứ ... (NaN)` liên tục | thiếu điện trở kéo lên 10 kΩ, dây DATA dài/dởm | thêm 10 kΩ giữa DATA và 3V3, dây < 20 cm |
| Relay chạy **ngược** (bật thành tắt) | module relay active HIGH | đổi `RELAY_ACTIVE_LEVEL` thành `HIGH` trong `node2_actuator.ino` |
| LED **ngược** (lệnh ON nhưng LED tắt) | đèn/module LED active LOW | đổi `LED_ACTIVE_LEVEL` thành `LOW` trong `node1_sensor.ino` |
| Serial Monitor im lặng | `USB CDC On Boot = Disabled`, sai baud, hoặc sai cổng COM | bật `USB CDC On Boot = Enabled`, chọn 115200, thử cổng COM còn lại |
| Lỗi biên dịch `invalid preprocessing directive #===` | comment trong `secrets.h` dùng `#` | đổi mọi comment trong `secrets.h` sang `//` |
| Log Serial có ký tự lạ (`Ã©`, `â€¦`) | Serial Monitor chọn sai encoding | không phải lỗi firmware; dùng `arduino-cli monitor` hoặc chọn UTF-8 |
| Board tự reset mỗi ~30 s | `loop()` bị treo → watchdog panic | không `delay()` dài, không `while` chờ mạng; dùng mốc `millis()` + `nextAttemptMs` |
| Nạp firmware xoá mất trạng thái LED | `Preferences` namespace `"state"` bị erase khi nạp | không phải lỗi; gửi lại lệnh từ dashboard |


## 9. Giới hạn đã biết (ghi rõ để không bị coi là lỗi)

1. **PubSubClient 2.8 CHỈ publish được QoS 0** - firmware không cố ép QoS 1 cho
   telemetry/register/ack. Yêu cầu QoS 1 của Phase 4 được thoả ở 2 chỗ: (a) **LWT** - tham số
   QoS 1 nằm trong gói CONNECT `mqtt.connect(DEVICE_ID, MQTT_USER, MQTT_PASS, TOPIC_STATUS, 1,
   true, "offline")`; (b) **bridge LOCAL → SERVER** khai báo `out 1` nên message được nâng lên
   QoS 1 khi qua cầu. Hệ quả: nếu thiết bị mất mạng đúng lúc publish, message đó có thể mất -
   chấp nhận được vì telemetry gửi lại mỗi 10 s và backend xử lý trùng an toàn.
2. `mqtt.subscribe(TOPIC_CMD, 1)` là QoS 1 của phía **nhận**: broker giữ và gửi lại lệnh khi
   thiết bị reconnect, nên lệnh không bị mất.
3. **register lặp mỗi 5 phút** dù backend xử lý trùng idempotent (nghiệm thu `T15`) - chủ ý, để
   thiết bị tự đăng ký lại sau khi admin reset DB.
4. Telemetry của node1 luôn có `relay1=false, relay2=false` (node1 không có relay); dashboard coi
   `relay*` của node1 là hằng số.
5. JSON được dựng bằng `char[]` + `snprintf` (không dùng `String`) để tránh phân mảnh heap;
   buffer MQTT 512 B dư cho payload dài nhất (~320 B của ack có `reason`).
6. `REG_TOKEN` nhúng thẳng trong firmware: nạp lại firmware khi token đổi; muốn thu hồi token phải
   tạo lại thiết bị trên backend.
7. `esp_task_wdt` ở esp32 core 3.x đã được init sẵn nên firmware gọi
   `esp_task_wdt_reconfigure()` (timeout 30 s, `trigger_panic = true`) và `esp_task_wdt_add(NULL)`
   cho task `loop()`; core 2.x dùng nhánh `esp_task_wdt_init()` (code hỗ trợ cả hai).

## 10. node2_actuator (Phase 4 bước 2 - CHƯA làm)

Bước 1 của Phase 4 chỉ giao `node1_sensor`. Khi làm `node2_actuator` sẽ dùng lại đúng khuôn này
với các khác biệt sau:

| Mục | node1_sensor (đã xong) | node2_actuator (bước 2) |
| --- | --- | --- |
| `DEVICE_TYPE` | `sensor` | `actuator` |
| Chân | DHT11=GPIO4, LED1=GPIO5, LED2=GPIO6 | DHT11=GPIO4, LED1=GPIO5, LED2=GPIO10, RELAY1=GPIO6, RELAY2=GPIO7 |
| Lệnh hỗ trợ | `led1`, `led2` | `led1`, `led2`, `relay1`, `relay2` |
| Preferences | `led1`, `led2` | thêm `relay1`, `relay2` (chỉ ghi flash khi trạng thái đổi) |
| Relay | không có | `RELAY_ACTIVE_LEVEL LOW`, khôi phục trạng thái từ Preferences khi khởi động |
| Tài khoản MQTT | `node1` (đã có trong passwd broker LOCAL) | `node2` (đã có trong passwd broker LOCAL) |
| `secrets.h` | đã sinh ở `firmware/node1_sensor/secrets.h` | đã sinh sẵn ở `firmware/node2_actuator/secrets.h` |

Xong bước 2 thì bổ sung vào tài liệu này: bảng đấu dây relay (VCC/GND/IN + nguồn rời cho tải
220 V) và mục checklist test riêng cho relay.

