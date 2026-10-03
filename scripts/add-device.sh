#!/usr/bin/env bash
# =============================================================================
# add-device.sh - Thêm thiết bị mới vào broker LOCAL (không cần khởi động lại)
#
# Vì ACL dùng %u (= username = deviceId), thiết bị mới tự có quyền trên topic
# của chính nó; chỉ cần tạo tài khoản rồi cho Mosquitto nạp lại passwd (SIGHUP).
#
# Dùng:  ./scripts/add-device.sh <deviceId> [matKhau]
#   - deviceId : chữ thường, số, gạch dưới/gạch nối (ví dụ node3)
#   - matKhau  : bỏ trống thì script tự sinh mật khẩu ngẫu nhiên và in ra
# =============================================================================
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_env
need_docker

DEVICE_ID="${1:-}"
PASS="${2:-}"
[ -n "$DEVICE_ID" ] || die "Dùng: ./scripts/add-device.sh <deviceId> [matKhau]"

# Chỉ cho ký tự an toàn để không phá cấu hình topic/ACL
printf '%s' "$DEVICE_ID" | grep -qE '^[a-z0-9][a-z0-9_-]{1,49}$' \
  || die "deviceId chỉ được gồm chữ thường, số, '_' hoặc '-' (2-50 ký tự), ví dụ: node3"

if [ -z "$PASS" ]; then
  PASS="$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-20)"
  log "Mật khẩu sinh tự động cho $DEVICE_ID: $PASS  (lưu lại ngay, sẽ nạp vào secrets.h)"
fi

# Bắt buộc broker LOCAL đang chạy: mosquitto_passwd phải sửa file trong container
docker ps --format '{{.Names}}' | grep -qx "$BROKER_LOCAL_CONTAINER" \
  || die "Container '$BROKER_LOCAL_CONTAINER' chưa chạy. Hãy chạy ./scripts/up.sh trước."

# Thêm (hoặc đổi mật khẩu) tài khoản ngay trong container đang chạy
docker exec "$BROKER_LOCAL_CONTAINER" \
  mosquitto_passwd -b /mosquitto/config/passwd "$DEVICE_ID" "$PASS" \
  || die "Không thêm được tài khoản $DEVICE_ID"

# mosquitto_passwd ghi file với chủ root/quyền 0600, nhưng broker chạy bằng user
# 'mosquitto' (uid 1883) -> phải chown + chmod 0700, nếu không broker sẽ không
# mở được file và thoát. (MSYS_NO_PATHCONV=1 trong lib.sh giữ nguyên /mosquitto/...)
docker exec "$BROKER_LOCAL_CONTAINER" sh -c \
  'chown mosquitto:mosquitto /mosquitto/config/passwd && chmod 0700 /mosquitto/config/passwd' \
  || warn "Không đổi được quyền passwd (kiểm tra ./scripts/logs.sh local)"

# Mosquitto nạp lại password_file/acl_file khi nhận SIGHUP
docker kill --signal=HUP "$BROKER_LOCAL_CONTAINER" >/dev/null
log "Đã gửi SIGHUP cho broker LOCAL (nạp lại passwd, KHÔNG ngắt kết nối thiết bị)"

cat <<EOF

Đã thêm thiết bị '$DEVICE_ID' vào broker LOCAL.
Việc cần làm tiếp:
  1. Nạp vào firmware ($DEVICE_ID):
       DEVICE_ID = "$DEVICE_ID"
       MQTT_PASS = "$PASS"
  2. Tạo thiết bị trong backend (khi làm Phase 3) để lấy regToken:
       POST /api/devices  {"deviceId":"$DEVICE_ID","name":"...","type":"sensor"}
  3. Kiểm tra nhanh bằng MQTTX (user=$DEVICE_ID) hoặc:
       ./scripts/smoke-test.sh
Lưu ý: tài khoản này CHỈ có quyền trên topic sh/$DEVICE_ID/...
EOF