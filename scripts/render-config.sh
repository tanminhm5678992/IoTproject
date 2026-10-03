#!/usr/bin/env bash
# =============================================================================
# render-config.sh - Sinh file cấu hình Mosquitto thật từ *.conf.template
#
# Vì sao phải render: Mosquitto KHÔNG đọc biến môi trường trong file cấu hình,
# nên địa chỉ broker server và mật khẩu bridge phải được "đổ" vào file .conf
# trước khi chạy container. File sinh ra nằm trong .gitignore (có mật khẩu).
#
# Dùng: ./scripts/render-config.sh
# =============================================================================
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_env

render_template "$ROOT/broker-local/config/mosquitto.conf.template" \
  "$ROOT/broker-local/config/mosquitto.conf" \
  "SERVER_HOST=$SERVER_HOST" "SERVER_PORT=$SERVER_PORT" \
  "BRIDGE_USERNAME=$BRIDGE_USERNAME" "BRIDGE_PASSWORD=$BRIDGE_PASSWORD" \
  "BRIDGE_CLIENT_ID=$BRIDGE_CLIENT_ID"

render_template "$ROOT/server/broker-server/config/mosquitto.conf.template" \
  "$ROOT/server/broker-server/config/mosquitto.conf" \
  "BACKEND_USERNAME=$BACKEND_USERNAME" "BRIDGE_USERNAME=$BRIDGE_USERNAME"

# Nhắc lại địa chỉ đang cấu hình để dễ phát hiện đổi mạng mà quên render
log "Bridge sẽ trỏ tới: $SERVER_HOST:$SERVER_PORT"

# File .conf vừa sinh lại thuộc chủ root -> trả lại quyền cho user 'mosquitto'
# (nếu Docker đang chạy). Nếu chưa, up.sh sẽ làm bước này trước khi khởi động.
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  harden_config_perms "$ROOT/broker-local/config"
  harden_config_perms "$ROOT/server/broker-server/config"
fi

ok "Xong. Nhớ chạy 'docker compose up -d' (hoặc ./scripts/up.sh) để nạp lại cấu hình."