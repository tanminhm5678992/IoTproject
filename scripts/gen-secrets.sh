#!/usr/bin/env bash
# =============================================================================
# gen-secrets.sh - Sinh firmware/<node>/secrets.h từ firmware/common/secrets.h.example
#   - Điền: DEVICE_ID, mật khẩu MQTT, IP broker local theo từng mạng, ROOT_CA
#   - KHÔNG điền SSID/mật khẩu WiFi (bạn tự sửa trong secrets.h, không commit)
# Dùng: ./scripts/gen-secrets.sh
# =============================================================================
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_env

TPL="$ROOT/firmware/common/secrets.h.example"
CA="$ROOT/certs/out/ca.crt"
[ -f "$TPL" ] || die "Không thấy $TPL"
[ -f "$CA" ]  || die "Chưa có $CA. Hãy chạy ./certs/gen-certs.sh trước."

gen_node() { # $1 = deviceId, $2 = mật khẩu MQTT, $3 = thư mục node
  local id="$1" pass="$2" dir="$ROOT/firmware/$3"
  mkdir -p "$dir"
  # awk thay placeholder; @CA_PEM@ thay bằng cả nội dung ca.crt
  awk -v id="$id" -v pass="$pass" \
      -v hh="${GATEWAY_IP_HOME:-}" -v hl="${GATEWAY_IP_LAPTOP:-}" -v hp="${GATEWAY_IP_PHONE:-}" \
      -v ca="$(cat "$CA")" '
    {
      gsub(/@DEVICE_ID@/, id)
      gsub(/@MQTT_PASS@/, pass)
      gsub(/@MQTT_HOST_HOME@/, hh)
      gsub(/@MQTT_HOST_LAPTOP@/, hl)
      gsub(/@MQTT_HOST_PHONE@/, hp)
      if ($0 == "@CA_PEM@") { print ca; next }
      print
    }' "$TPL" > "$dir/secrets.h"
  chmod 600 "$dir/secrets.h" 2>/dev/null || true
  log "Đã sinh $dir/secrets.h (device=$id, mật khẩu MQTT đã điền)"
}

gen_node node1 "${NODE1_PASSWORD:-CHANGE_ME_node1}" node1_sensor
gen_node node2 "${NODE2_PASSWORD:-CHANGE_ME_node2}" node2_actuator

cat <<'EOF'

Việc bạn cần tự làm trong secrets.h (vì chứa mật khẩu WiFi, không nên qua script):
  - Điền SSID_HOME / PASS_HOME / SSID_LAPTOP / PASS_LAPTOP / SSID_PHONE / PASS_PHONE
  - Lưu ý: ESP32-C3 chỉ dùng được WiFi 2.4 GHz (băng tần 5 GHz sẽ không kết nối)
  - REG_TOKEN: lấy từ backend sau khi đăng ký thiết bị (Phase 3)
File secrets.h đã nằm trong .gitignore - KHÔNG commit.
EOF