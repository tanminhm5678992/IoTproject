#!/usr/bin/env bash
# =============================================================================
# setup-users.sh - Tạo tài khoản MQTT cho cả 2 broker
#   - Broker LOCAL : node1, node2 (username = deviceId), local_monitor (chỉ đọc)
#   - Broker SERVER: bridge_user, backend_user
# Mật khẩu đọc từ .env. Chạy lại nhiều lần được (cập nhật mật khẩu, KHÔNG xóa
# các thiết bị đã thêm sau này bằng add-device.sh).
#
# Dùng: ./scripts/setup-users.sh
# =============================================================================
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_env
need_docker

MOSQ_IMAGE="${MOSQ_IMAGE:-eclipse-mosquitto:2}"

# Thêm/đổi mật khẩu cho 1 user trong 1 file passwd trên host
# Lưu ý: mosquitto_passwd chỉ tạo file mới khi có cờ -c. Vì vậy user đầu tiên
# của mỗi broker dùng -c, các user sau dùng -b (không ghi đè thiết bị đã thêm).
add_user() { # $1 = thư mục config (host), $2 = username, $3 = password
  local cfg="$1" user="$2" pass="$3" create=""
  [ -f "$cfg/passwd" ] || create="-c"
  docker run --rm -i \
    -v "$(docker_path "$cfg"):/mosquitto/config" \
    "$MOSQ_IMAGE" mosquitto_passwd $create -b /mosquitto/config/passwd "$user" "$pass" \
    || die "Không tạo được tài khoản $user"
  # File passwd phải thuộc user 'mosquitto' + quyền 0700 (xem harden_config_perms)
  log "Đã đặt mật khẩu cho tài khoản: $user (${create:-cập nhật})"
}

list_users() { # $1 = thư mục config (host)
  docker run --rm -i \
    -v "$(docker_path "$1"):/mosquitto/config" \
    "$MOSQ_IMAGE" awk -F: '{print $1}' /mosquitto/config/passwd | tr '\n' ' '
}

LOCAL_CFG="$ROOT/broker-local/config"
SERVER_CFG="$ROOT/server/broker-server/config"

log "== Broker LOCAL: $LOCAL_CFG/passwd =="
add_user "$LOCAL_CFG" node1         "$NODE1_PASSWORD"
add_user "$LOCAL_CFG" node2         "$NODE2_PASSWORD"
add_user "$LOCAL_CFG" "$LOCAL_MONITOR_USER" "$LOCAL_MONITOR_PASSWORD"
harden_config_perms "$LOCAL_CFG"

log "== Broker SERVER: $SERVER_CFG/passwd =="
add_user "$SERVER_CFG" "$BRIDGE_USERNAME"  "$BRIDGE_PASSWORD"
add_user "$SERVER_CFG" "$BACKEND_USERNAME" "$BACKEND_PASSWORD"
harden_config_perms "$SERVER_CFG"

log "Tài khoản trên broker LOCAL : $(list_users "$LOCAL_CFG")"
log "Tài khoản trên broker SERVER: $(list_users "$SERVER_CFG")"

# Nếu broker đang chạy thì nạp lại danh sách tài khoản (không cần khởi động lại)
if docker ps --format '{{.Names}}' | grep -qx "$BROKER_LOCAL_CONTAINER"; then
  docker kill --signal=HUP "$BROKER_LOCAL_CONTAINER" >/dev/null && log "Đã gửi SIGHUP cho broker LOCAL để nạp lại passwd"
fi
if docker ps --format '{{.Names}}' | grep -qx "$BROKER_SERVER_CONTAINER"; then
  docker kill --signal=HUP "$BROKER_SERVER_CONTAINER" >/dev/null && log "Đã gửi SIGHUP cho broker SERVER để nạp lại passwd"
fi

ok "Xong. Tiếp theo: ./scripts/render-config.sh  rồi  ./scripts/up.sh"