# Hướng dẫn kiểm thử bằng MQTTX (Phase 1 - tầng broker)

Tài liệu này để **tự tay kiểm tra tầng broker** (T1-T6, T16) bằng MQTTX, song song với
`./scripts/smoke-test.sh` (script tự động chạy T1-T6, T16, T17).

Điều kiện trước khi test:

```bash
cp .env.example .env      # rồi điền mật khẩu (mọi CHANGE_ME)
./certs/gen-certs.sh      # sinh CA + cert có SAN đúng IP hiện tại
./scripts/setup-users.sh  # tạo tài khoản MQTT + gán quyền file cho user mosquitto
./scripts/up.sh           # chạy 2 broker (LOCAL + SERVER)
```

Các giá trị dưới đây lấy từ `.env` (đọc bằng `cat .env`): `GATEWAY_IP`, `LOCAL_PORT`,
`SERVER_HOST`, `SERVER_PORT`, `LOCAL_MONITOR_USER`, `LOCAL_MONITOR_PASSWORD`,
`BACKEND_USERNAME`, `BACKEND_PASSWORD`, `NODE1_PASSWORD`.

> **Chứng chỉ**: MQTTX cần file `certs/out/ca.crt`. Trong MQTTX, ở mục TLS bật
> *CA signed server certificate* (tuỳ chọn: *Self signed certificates*) và chọn
> file `ca.crt` này. Nếu để *Insecure* (bỏ kiểm tra cert) thì kết nối vẫn được
> nhưng KHÔNG phản ánh đúng cấu hình thật - chỉ dùng khi đang gỡ lỗi.

---

## 1. Ba connection cần tạo

| Tên connection | Host | Port | User | Ghi chú |
|---|---|---|---|---|
| `local` | `GATEWAY_IP` | `LOCAL_PORT` (8883) | `local_monitor` | Tài khoản giám sát, **chỉ đọc** `sh/#` |
| `server-admin` | `SERVER_HOST` | `SERVER_PORT` (8884) | `backend_user` | Chỉ đọc `sh/+/register|telemetry|status|ack`, chỉ ghi `sh/+/cmd` |
| `node1-sim` | `GATEWAY_IP` | `LOCAL_PORT` (8883) | `node1` | Giả lập thiết bị node1 |

Mọi connection đều: giao thức **MQTT 3.1.1**, bật **SSL/TLS**, chọn `ca.crt`,
nhập username/password như bảng trên. Client ID để MQTTX tự sinh cũng được.

Với `node1-sim`, đặt thêm **Last Will** (để test T6):

| Trường | Giá trị |
|---|---|
| Will Topic | `sh/node1/status` |
| Will Payload | `offline` |
| Will QoS | 1 |
| Will Retain | bật (true) |
---

## 2. Các bước test

Ở mỗi bước: mở connection, Subscribe/Publish theo hướng dẫn rồi đối chiếu kết quả.

### T1 - Thiết bị kết nối broker LOCAL bằng TLS + mật khẩu đúng
`node1-sim` -> Connect.
**Mong đợi:** kết nối thành công (MQTTX báo *Connected*).
Nếu lỗi *not authorised*: sai mật khẩu; nếu lỗi chứng chỉ: `ca.crt` sai hoặc SAN không có `GATEWAY_IP`.

### T2 - Sai mật khẩu phải bị từ chối
Sửa `node1-sim` sang mật khẩu sai -> Connect.
**Mong đợi:** MQTTX báo lỗi *Connection Refused: not authorised*.

### T3 - ACL chặn ghi chéo topic
`local` và `server-admin` cùng Subscribe `sh/node2/telemetry`.
Dùng `node1-sim` publish `sh/node2/telemetry` với payload `{"temp":99,"hum":1,"ts":0}`.
**Mong đợi:** **không** connection nào nhận được message (broker LOCAL chặn theo ACL).
Đây là điểm bảo mật quan trọng của đồ án.

### T4 - Dữ liệu chạy qua bridge (LOCAL -> SERVER)
`server-admin` Subscribe `sh/node1/telemetry`.
`node1-sim` publish `sh/node1/telemetry` payload `{"temp":28.5,"hum":65.2,"ts":1}` (QoS 0 hoặc 1).
**Mong đợi:** `server-admin` nhận được đúng message đó (bridge đã chuyển lên).
Nếu không thấy: xem `./scripts/logs.sh local` và tìm dòng *Connecting bridge to_server*.

### T5 - Lệnh điều khiển chạy qua bridge (SERVER -> LOCAL)
`node1-sim` Subscribe `sh/node1/cmd`.
`server-admin` publish `sh/node1/cmd` payload
`{"cmdId":"m1","target":"relay1","value":"ON"}` với **QoS 1**.
**Mong đợi:** `node1-sim` nhận được lệnh (bridge chuyển xuống đúng topic).

### T6 - Mất kết nối đột ngột -> LWT `offline` retained
1. `node1-sim` Connect (đã cấu hình Will ở mục 1).
2. `local` Subscribe `sh/node1/status`.
3. Ngắt kết nối **đột ngột**: đóng tiến trình MQTTX/ tắt máy, KHÔNG bấm *Disconnect*
   (nếu bấm Disconnect, broker nhận DISCONNECT nên sẽ KHÔNG phát LWT).
**Mong đợi:** `local` nhận `sh/node1/status` = `offline`, và message này là **retained**
(Subscribe lại vẫn thấy ngay).
Kiểm tra thêm phía server: `server-admin` Subscribe `sh/node1/status` cũng thấy `offline`.

> Trước khi test T6 nên xoá retained cũ để không "đạt giả":
> `node1-sim` publish `sh/node1/status` với payload rỗng và bật *Retain* (xoá retained).

### T16 - Thêm thiết bị mới KHÔNG cần khởi động lại broker
```bash
./scripts/add-device.sh node3 <mat_khau_moi>
```
Tạo trong MQTTX connection thứ 4 `node3-sim` (user `node3`, cùng host/port/ca như `node1-sim`).
`server-admin` Subscribe `sh/node3/telemetry`; `node3-sim` publish `sh/node3/telemetry`.
**Mong đợi:** `node3` kết nối được ngay và dữ liệu qua bridge tới server, **không** phải
restart broker (add-device.sh chỉ gửi SIGHUP để nạp lại `passwd`).

---

## 3. Bảng topic và payload (tham chiếu nhanh)

| Topic | Ai ghi | Ai đọc | Payload |
|---|---|---|---|
| `sh/<deviceId>/register` | thiết bị | backend (server) | `{"regToken":"...","fw":"1.0.0"}` |
| `sh/<deviceId>/telemetry` | thiết bị | backend, MQTTX | `{"temp":28.5,"hum":65.2,"ts":1}` |
| `sh/<deviceId>/status` | thiết bị (LWT/retained) | backend, MQTTX | `online` / `offline` |
| `sh/<deviceId>/cmd` | backend (server) | thiết bị | `{"cmdId":"...","target":"relay1","value":"ON"}` |
| `sh/<deviceId>/ack` | thiết bị | backend | `{"cmdId":"...","result":"DONE"}` |

Quyền tương ứng nằm ở `broker-local/config/acl` và `server/broker-server/config/acl`.

---

## 4. Lỗi thường gặp

| Hiện tượng | Nguyên nhân thường gặp |
|---|---|
| *Connection Refused: not authorised* | Sai user/mật khẩu, hoặc `passwd` chưa được tạo (`./scripts/setup-users.sh`) |
| Lỗi TLS/cert | Chưa chọn `ca.crt`, hoặc cert sinh khi IP khác -> chạy `./scripts/set-network.sh <ip> <home\|laptop\|phone>` rồi `./scripts/up.sh` |
| Bridge không chuyển message | Xem `./scripts/logs.sh local`, tìm dòng *Connecting bridge*; kiểm tra `address` trong `broker-local/config/mosquitto.conf` khớp `SERVER_HOST:SERVER_PORT` |
| Broker tự thoát ngay sau khi chạy | File `passwd`/`acl` không thuộc user `mosquitto` -> chạy lại `./scripts/setup-users.sh` (hàm `harden_config_perms` trong `scripts/lib.sh` lo phần này) |
| Chỉ nhận retained cũ | Retained của lần test trước; publish payload rỗng + bật *Retain* để xoá |

Kiểm tra nhanh toàn bộ bằng một lệnh: `./scripts/smoke-test.sh` (đạt = exit code 0, in `Tất cả 8 test ĐẠT`).

---

## 5. Liên hệ với script tự động

`./scripts/smoke-test.sh` kiểm tra **cùng bộ test** này nhưng không cần thao tác tay
(dùng `mosquitto_pub`/`mosquitto_sub` trong container `eclipse-mosquitto:2`):

| Test | Cách script làm |
|---|---|
| T1, T2 | `mosquitto_pub -d` xem có `CONNACK` / có bị *not authorised* |
| T3 | Hai subscriber nền (server + local) + khẳng định **không** nhận gì |
| T4, T5 | Subscriber nền + publish lặp tới khi nhận được (đợi container kịp subscribe) |
| T6 | Client có `--will-*`, `docker kill` để mô phỏng mất kết nối đột ngột |
| T16 | `add-device.sh node3` rồi kiểm tra kết nối + bridge |
| T17 | Kiểm tra cert có IP hiện tại, `address` bridge, và `firmware/*/secrets.h` đã sinh |

Nếu MQTTX đạt mà script fail (hoặc ngược lại), ưu tiên xem log broker:
`./scripts/logs.sh local` / `./scripts/logs.sh server`.

---

## 6. Phase 2 – Kiểm thử backend bằng MQTTX (T7, T8, T9, T15)

Điều kiện: `./scripts/up.sh` (2 broker + bridge), `./scripts/db.sh up`,
`./scripts/backend.sh run` (log `Started SmartHomeApplication`, nghe cổng 8080).

Phase 2 **chưa có REST API tạo thiết bị**, nên tự chèn thiết bị vào DB trước
(ví dụ `node1` với token `demo-token-123`):

```bash
docker exec smarthome-postgres psql -U smarthome -d smarthome -c \
  "INSERT INTO devices (device_id,name,type,reg_token) VALUES ('node1','Node 1','sensor','demo-token-123')
   ON CONFLICT (device_id) DO UPDATE SET reg_token='demo-token-123', reg_status='PENDING', online=false;"
```

Publish bằng connection **`node1-sim`** (mục 1) — đúng đường đi thật của thiết bị;
theo dõi bằng **`server-admin`** (Subscribe `sh/+/register` và `sh/+/telemetry`):

| # | Bước (MQTTX, `node1-sim`) | Kết quả mong đợi |
|---|---|---|
| T9 | Publish `sh/node1/telemetry` payload `{"temp":28.5,"hum":65.2,"ts":1}` khi còn `PENDING` | `SELECT count(*) FROM telemetry WHERE device_id='node1';` = **0**; backend WARN `telemetry: bỏ qua ... đang ở trạng thái PENDING` |
| T8 | Publish `sh/node1/register` payload `{"regToken":"sai-token"}` | Vẫn `PENDING`; backend WARN `register: regToken sai cho thiết bị 'node1'` |
| T7 | Publish `sh/node1/register` payload `{"regToken":"demo-token-123","fw":"1.0.0"}` | `reg_status=ACTIVE`, `online=true`; backend INFO `register: thiết bị 'node1' ACTIVE, online=true` |
| T15 | Publish lại đúng payload của T7 (mô phỏng lặp mỗi 5 phút) | Không lỗi, vẫn `ACTIVE`, không nhân bản bản ghi; backend INFO `... (nhận lặp lại - idempotent)` |

Kiểm tra thêm: publish `sh/node1/status` với `offline` → `online=false`, đổi lại `online` → `online=true`.
Sau đó telemetry sẽ được lưu (đường dẫn xác nhận: publish lại payload T9 → có 1 dòng trong DB).

**Tự động hoá cùng bộ test** (không cần thao tác tay):

```bash
./scripts/backend-e2e.sh            # mặc định device node1, tự chèn PENDING rồi chạy T9→T8→T7→T15 + status
./scripts/backend-e2e.sh node2      # test với thiết bị khác (NODE2_PASSWORD phải có trong .env)
```

Script in `[ĐẠT]`/`[KHÔNG ĐẠT]` từng bước, exit code = số test fail (0 = toàn bộ đạt).