# SmartHome IoT – Hướng dẫn cài đặt & chạy đồ án

> Hệ thống giám sát nhiệt độ/độ ẩm và điều khiển relay qua MQTT TLS + WebSocket, kiến trúc 5 tầng.

---

## A. YÊU CẦU PHẦN CỨNG & PHẦN MỀM

### Phần cứng cần có

| Thiết bị | Số lượng | Ghi chú |
|---|---|---|
| ESP32-C3 (DevKit) | 1 | Board chính, WiFi 2.4 GHz |
| Cảm biến DHT11 | 1 | Đo nhiệt độ & độ ẩm |
| Module relay 1 kênh (5V) | 1 | Điều khiển thiết bị 220V |
| LED + điện trở 220Ω | 2 | LED trạng thái (LED1, LED2) |
| Dây cắm breadboard | vài sợi | |
| Dây USB-C | 1 | Nạp firmware, cấp nguồn |
| Laptop/PC (cùng phòng) | 1 | Chạy Docker server |

### Phần mềm cần cài (trên laptop)

| Phần mềm | Link tải | Ghi chú |
|---|---|---|
| **Docker Desktop** | https://www.docker.com/products/docker-desktop | Chọn bản Windows |
| **Git for Windows** | https://git-scm.com/download/win | Bao gồm Git Bash |
| **Arduino IDE 2.x** | https://www.arduino.cc/en/software | Nạp firmware |

> **QUAN TRỌNG:** Sau khi cài, mọi lệnh trong hướng dẫn này đều chạy trong **Git Bash** (không dùng PowerShell hay CMD).

---

## B. SƠ ĐỒ MẠCH ESP32-C3

```
ESP32-C3 DevKit
┌─────────────────────────────────┐
│                                 │
│  GPIO 4  ──────── DATA ─┐       │
│                          │      │
│  3.3V    ──────── VCC  DHT11    │
│  GND     ──────── GND   │      │
│                  (điện trở 10kΩ giữa DATA và VCC)
│                                 │
│  GPIO 5  ── [R 220Ω] ── LED1 ── GND   │
│  GPIO 6  ── [R 220Ω] ── LED2 ── GND   │
│                                 │
│  GPIO 7 (hoặc GPIO 10)          │
│          ── IN ── [Module Relay] │
│          5V  ─── VCC relay      │
│          GND ─── GND relay      │
└─────────────────────────────────┘
```

**Kết nối chi tiết:**

| ESP32-C3 Pin | Kết nối với |
|---|---|
| GPIO 4 | DHT11 chân DATA (+ điện trở 10kΩ kéo lên 3.3V) |
| GPIO 5 | LED1 (+ → R 220Ω → GPIO5, - → GND) |
| GPIO 6 | LED2 (+ → R 220Ω → GPIO6, - → GND) |
| 3.3V | DHT11 VCC |
| GND | DHT11 GND, LED GND, Relay GND |
| 5V (USB) | Relay VCC (nếu module relay cần 5V) |

> ⚠️ Module relay thường là **Active-LOW** (IN=LOW → relay bật). Nếu relay hoạt động ngược, xem mục G bên dưới.

---

## C. CÀI ĐẶT ARDUINO IDE CHO ESP32-C3

### Bước 1 – Thêm Board ESP32 vào Arduino IDE

1. Mở Arduino IDE → **File → Preferences**
2. Trong ô **Additional boards manager URLs**, thêm:
   ```
   https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
   ```
3. Vào **Tools → Board → Boards Manager** → tìm `esp32` → cài **esp32 by Espressif Systems** (phiên bản 3.x)

### Bước 2 – Cài thư viện

Vào **Tools → Manage Libraries**, tìm và cài:

| Thư viện | Tác giả |
|---|---|
| `PubSubClient` | Nick O'Leary |
| `ArduinoJson` | Benoit Blanchon (v7) |
| `DHT sensor library` | Adafruit |

### Bước 3 – Cấu hình Board

Cắm ESP32-C3 vào máy → **Tools → Board → esp32 → ESP32C3 Dev Module**

| Tùy chọn | Giá trị |
|---|---|
| USB CDC On Boot | **Enabled** (bắt buộc để thấy Serial) |
| Upload Speed | 921600 |
| CPU Frequency | 160MHz |

---

## D. CÀI ĐẶT & CẤU HÌNH DỰ ÁN

### Bước 1 – Lấy source code

```bash
git clone <link-repo>
cd DA
```

### Bước 2 – Cấu hình file .env

```bash
cp .env.example .env
```

Mở `.env` và điền các giá trị sau:

```env
# IP của laptop trong mạng WiFi đang dùng
GATEWAY_IP=192.168.10.193        ← đổi thành IP laptop của bạn
SERVER_HOST=192.168.10.193       ← thường giống GATEWAY_IP

# Mật khẩu đơn giản cho lab (có thể giữ nguyên)
NODE1_PASSWORD=node1pass
NODE2_PASSWORD=node2pass
BRIDGE_PASSWORD=bridgepass
BACKEND_PASSWORD=backendpass
DB_PASSWORD=dbpass123

# Tài khoản đăng nhập web dashboard
ADMIN_USERNAME=admin
ADMIN_PASSWORD=Admin@123
JWT_SECRET=4aad56201e5821a225e7a04df65b3843641e763e5d0903c75c639918ff5965db
```

> **Lấy IP laptop:** Mở Git Bash, gõ `ipconfig` (Windows) → xem địa chỉ IPv4 của WiFi đang kết nối.

### Bước 3 – Sinh chứng chỉ TLS

```bash
./certs/gen-certs.sh
```

> Chứng chỉ được sinh theo IP trong `.env`. Nếu đổi mạng WiFi (IP thay đổi) phải chạy lại lệnh này.

### Bước 4 – Tạo tài khoản MQTT

```bash
./scripts/setup-users.sh
```

### Bước 5 – Render cấu hình Mosquitto

```bash
./scripts/render-config.sh
```

---

## E. CHẠY HỆ THỐNG SERVER

### Khởi động (mỗi lần bật máy)

```bash
# Terminal Git Bash, tại thư mục DA/

# Bước 1: Khởi động 2 broker MQTT
./scripts/up.sh

# Bước 2: Khởi động backend + database + frontend
./scripts/server-up.sh
```

**Đợi ~30 giây**, sau đó kiểm tra:
```bash
docker ps
```

Phải thấy 4 container đều **healthy**:
```
smarthome-broker-local   Up  (healthy)  :8883
smarthome-broker-server  Up  (healthy)  :8884
smarthome-postgres       Up  (healthy)  :5432
smarthome-backend        Up  (healthy)  :8080
smarthome-frontend       Up            :3000
```

### Truy cập web dashboard

Mở trình duyệt: **http://localhost:3000**
- Username: `admin`
- Password: `Admin@123`

---

## F. NẠP FIRMWARE CHO ESP32-C3

### Bước 1 – Điền cấu hình vào secrets.h

Mở file `firmware/node1_sensor/secrets.h`:

```cpp
// WiFi của bạn
#define SSID_HOME   "TEN_WIFI_CUA_BAN"
#define PASS_HOME   "MAT_KHAU_WIFI"
#define SSID_PHONE  "TEN_HOTSPOT_DIEN_THOAI"
#define PASS_PHONE  "MAT_KHAU_HOTSPOT"

// IP laptop (= GATEWAY_IP trong .env)
#define MQTT_HOST_HOME   "192.168.1.x"   ← IP khi kết nối wifi nhà
#define MQTT_HOST_PHONE  "192.168.10.193" ← IP khi dùng hotspot
#define MQTT_PORT 8883

// Mật khẩu MQTT (= NODE1_PASSWORD trong .env)
#define MQTT_PASS "node1pass"
```

### Bước 2 – Dán CA Certificate

1. Mở file `certs/out/ca.crt` bằng Notepad
2. Copy toàn bộ nội dung (từ `-----BEGIN CERTIFICATE-----` đến `-----END CERTIFICATE-----`)
3. Dán vào `secrets.h` tại vị trí `ROOT_CA[]`:
```cpp
static const char ROOT_CA[] PROGMEM = R"EOF(
-----BEGIN CERTIFICATE-----
<DÁN NỘI DUNG FILE ca.crt VÀO ĐÂY>
-----END CERTIFICATE-----
)EOF";
```

### Bước 3 – Đăng ký thiết bị trên Web

1. Vào http://localhost:3000 → đăng nhập
2. Click **"Thêm thiết bị"**
3. Nhập:
   - Device ID: `node1`
   - Loại: `sensor`
4. Copy **Registration Token** hiển thị ra

### Bước 4 – Điền token vào firmware

Trong `secrets.h`:
```cpp
#define REG_TOKEN "token_vừa_copy_ở_bước_3"
```

### Bước 5 – Upload firmware

1. Mở Arduino IDE → **File → Open** → chọn `firmware/node1_sensor/node1_sensor.ino`
2. Cắm ESP32-C3 vào máy bằng USB-C
3. Chọn đúng cổng COM: **Tools → Port → COMx**
4. Nhấn nút **Upload** (→)
5. Mở **Serial Monitor** (Ctrl+Shift+M), tốc độ **115200 baud**

**Log mong đợi:**
```
[WIFI] Kết nối TEN_WIFI...
[NTP] Đồng bộ thời gian...
[TLS] Kết nối broker 192.168.10.193:8883
[MQTT] Kết nối thành công
[MQTT] Đã đăng ký thiết bị node1
[DHT] temp=28.5 hum=60.0
```

---

## G. KIỂM TRA HỆ THỐNG

Sau khi ESP32 kết nối, trên dashboard tại http://localhost:3000:

1. **LED trạng thái** hiển thị `online` (màu xanh)
2. **Nhiệt độ/độ ẩm** cập nhật mỗi 10 giây
3. **Điều khiển relay:** nhấn nút ON/OFF trên web → relay bật/tắt ngay lập tức
4. Biểu tượng **"Live WebSocket"** màu xanh = kết nối real-time đang hoạt động

---

## H. XỬ LÝ LỖI THƯỜNG GẶP

| Lỗi | Nguyên nhân | Cách sửa |
|---|---|---|
| `TLS Handshake Failed` | Giờ ESP32 sai hoặc IP không khớp cert | Đảm bảo WiFi có Internet (NTP); kiểm tra IP trong `MQTT_HOST_xxx` đúng với IP laptop |
| `Certificate verify failed` | CA cert trong secrets.h không khớp | Copy lại `certs/out/ca.crt` vào `ROOT_CA[]` |
| ESP32 không thấy WiFi 5GHz | ESP32-C3 chỉ hỗ trợ 2.4 GHz | Dùng mạng 2.4 GHz hoặc hotspot điện thoại |
| Relay bật ngược (ON = tắt) | Module relay Active-LOW | Tìm dòng `RELAY_ACTIVE_LEVEL` trong `.ino`, đổi `HIGH` → `LOW` |
| Dashboard không load | Docker chưa khởi động xong | Đợi thêm 30s, kiểm tra `docker ps` |
| `couldn't find env file` | Dùng PowerShell thay vì Git Bash | Mở **Git Bash** và chạy lại |
| Conflict container | Container cũ chưa xóa | `docker stop smarthome-backend && docker rm smarthome-backend` rồi chạy lại |
| Đổi mạng WiFi, mất kết nối | IP thay đổi, cert cũ | Cập nhật `GATEWAY_IP` trong `.env` → chạy lại `./certs/gen-certs.sh` → `./scripts/render-config.sh` → nạp lại firmware |

---

## I. DỪNG HỆ THỐNG

```bash
# Dừng server stack
docker compose -f server/docker-compose.yml --env-file .env down

# Dừng broker LOCAL
docker compose -f broker-local/docker-compose.yml --env-file .env down
```

---

## J. CẤU TRÚC THƯ MỤC

```
DA/
├── .env                    ← Cấu hình toàn hệ thống (PHẢI điền trước khi chạy)
├── .env.example            ← Template .env
├── certs/                  ← Script sinh TLS certificate
├── broker-local/           ← Mosquitto gateway (thiết bị kết nối vào)
├── server/
│   ├── docker-compose.yml  ← Khởi động toàn bộ server bằng 1 lệnh
│   ├── broker-server/      ← Mosquitto server (backend kết nối vào)
│   ├── backend/            ← Spring Boot 3: REST API + MQTT + WebSocket
│   └── frontend/           ← React 18: Dashboard điều khiển
├── firmware/
│   └── node1_sensor/       ← Arduino sketch cho ESP32-C3
│       ├── node1_sensor.ino
│       └── secrets.h       ← Điền WiFi, IP, token, CA cert
└── scripts/                ← Script quản lý hệ thống
    ├── up.sh               ← Khởi động 2 broker
    └── server-up.sh        ← Khởi động full server stack
```
