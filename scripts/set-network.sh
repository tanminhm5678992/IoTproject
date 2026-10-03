#!/usr/bin/env bash
# =============================================================================
# set-network.sh - ĐỔI MẠNG TRONG 1 LỆNH (~5 phút, phần script chỉ vài giây)
#
# Việc script làm:
#   1. Cập nhật .env (GATEWAY_IP, slot mạng, SERVER_HOST, danh sách SAN)
#   2. Sinh lại chứng chỉ TLS có IP mới
#   3. Render lại mosquitto.conf (địa chỉ bridge)
#   4. Sinh lại firmware/*/secrets.h (MQTT_HOST theo từng mạng)
#   5. Khởi động lại 2 broker nếu đang chạy
#
# Dùng: ./scripts/set-network.sh <ip-gateway> [home|laptop|phone]
#   ví dụ: ./scripts/set-network.sh 192.168.1.10 home
#          ./scripts/set-network.sh 192.168.10.193 phone
# =============================================================================
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

IP="${1:-}"; SLOT="${2:-}"
[ -n "$IP" ] || die "Dùng: ./scripts/set-network.sh <ip-gateway> [home|laptop|phone]"
validate_ip "$IP"

load_env
SLOT="${SLOT:-${ACTIVE_SLOT:-phone}}"
case "$SLOT" in
  home|laptop|phone) ;;
  *) die "slot phải là: home | laptop | phone" ;;
esac
UPPER="$(printf '%s' "$SLOT" | tr '[:lower:]' '[:upper:]')"

log "Đổi mạng: gateway = $IP, slot = $SLOT"

# ---- 1. Cập nhật .env ------------------------------------------------------
set_env_var "ACTIVE_SLOT"      "$SLOT"
set_env_var "GATEWAY_IP"       "$IP"
set_env_var "GATEWAY_IP_$UPPER" "$IP"
set_env_var "SERVER_HOST"      "$IP"   # 2 broker cùng một laptop

# SAN = hợp của mọi IP đã biết + IP hiện tại + 127.0.0.1 (bỏ trùng lặp)
san=""
for v in "$IP" "127.0.0.1" "${GATEWAY_IP_HOME:-}" "${GATEWAY_IP_LAPTOP:-}" "${GATEWAY_IP_PHONE:-}"; do
  [ -n "$v" ] || continue
  case " $san " in *" $v "*) ;; *) san="${san:+$san }$v" ;; esac
done
set_env_var "CERT_IPS" "\"$san\""
log "Danh sách IP trong cert (SAN): $san"

# ---- 2,3,4. Sinh lại chứng chỉ, cấu hình broker, secrets.h ---------------
bash "$ROOT/certs/gen-certs.sh"
bash "$ROOT/scripts/render-config.sh"
bash "$ROOT/scripts/gen-secrets.sh"

# ---- 5. Khởi động lại broker để nạp cấu hình mới --------------------------
if docker ps --format '{{.Names}}' | grep -qx "$BROKER_LOCAL_CONTAINER"; then
  log "Khởi động lại 2 broker để nạp cert/cấu hình mới..."
  docker restart "$BROKER_SERVER_CONTAINER" "$BROKER_LOCAL_CONTAINER" >/dev/null
fi

cat <<EOF

Xong. Nhắc lại 3 việc KHÔNG được quên khi đổi mạng:
  1. Đặt IP tĩnh cho laptop = $IP (Windows: Settings > Network > WiFi > IP assignment > Manual)
  2. Mở firewall cổng ${LOCAL_PORT}, ${SERVER_PORT} (PowerShell quyền Admin):
       powershell -ExecutionPolicy Bypass -File .\scripts\windows-firewall.ps1
     và kiểm tra mạng WiFi đang ở chế độ "Private".
  3. Nạp lại firmware nếu thiết bị chưa có IP mới của mạng này (secrets.h đã tự cập nhật).
Kiểm tra: ./scripts/smoke-test.sh
EOF