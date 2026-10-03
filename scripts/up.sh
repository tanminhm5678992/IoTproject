#!/usr/bin/env bash
# =============================================================================
# up.sh - Dựng (hoặc cập nhật) broker LOCAL (gateway) và broker SERVER
#
# Chế độ:
#   ./scripts/up.sh          → khởi động CẢ HAI broker (mặc định, dùng khi dev/test)
#   ./scripts/up.sh local    → chỉ broker LOCAL (dùng khi server stack do server-up.sh quản lý)
#
# Lưu ý Phase 6 (Docker full stack):
#   - Broker SERVER nằm trong server/docker-compose.yml (quản lý bởi server-up.sh)
#   - Gọi up.sh sau khi server-up.sh để chỉ khởi động broker LOCAL:
#       ./scripts/up.sh local
# =============================================================================
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_env
need_docker

MODE="${1:-both}"   # "both" | "local"

# ---- 1. Chứng chỉ TLS -------------------------------------------------------
if [ ! -f "$ROOT/certs/out/server.crt" ]; then
  warn "Chưa có chứng chỉ TLS → sinh mới..."
  bash "$ROOT/certs/gen-certs.sh"
else
  log "Đã có chứng chỉ TLS trong certs/out (sinh lại: ./certs/gen-certs.sh)"
fi

# ---- 2. Render cấu hình Mosquitto từ template --------------------------------
bash "$ROOT/scripts/render-config.sh"

# ---- 3. Tài khoản MQTT -------------------------------------------------------
if [ ! -f "$ROOT/broker-local/config/passwd" ] || [ ! -f "$ROOT/server/broker-server/config/passwd" ]; then
  log "Chưa có file passwd → tạo tài khoản từ .env..."
  bash "$ROOT/scripts/setup-users.sh"
else
  log "Đã có passwd (cập nhật mật khẩu: ./scripts/setup-users.sh)"
fi

# ---- 4. Khởi động broker -----------------------------------------------------
if [ "$MODE" = "both" ]; then
  # Kiểm tra broker-server có đang chạy trong compose khác không (tránh conflict)
  if docker ps --format '{{.Names}}' | grep -qx "$BROKER_SERVER_CONTAINER"; then
    log "Container $BROKER_SERVER_CONTAINER đã chạy (có thể do server-up.sh) – bỏ qua khởi động broker SERVER."
    log "Nếu muốn dùng compose riêng: docker stop $BROKER_SERVER_CONTAINER && docker rm $BROKER_SERVER_CONTAINER"
  else
    log "Khởi động broker SERVER..."
    compose "$ROOT/server/broker-server/docker-compose.yml" up -d
  fi
fi

log "Khởi động broker LOCAL (gateway)..."
compose "$ROOT/broker-local/docker-compose.yml" up -d

# ---- 5. Kiểm tra nhanh trạng thái -------------------------------------------
sleep 3
log "Trạng thái container:"
docker ps --filter "name=$BROKER_SERVER_CONTAINER" --filter "name=$BROKER_LOCAL_CONTAINER" \
  --format '  {{.Names}}  {{.Status}}  {{.Ports}}'

for c in "$BROKER_LOCAL_CONTAINER"; do
  if ! docker ps --format '{{.Names}}' | grep -qx "$c"; then
    warn "Container $c KHÔNG chạy. Log cuối:"
    docker logs --tail 20 "$c" 2>&1 || true
    die "Dừng vì $c không khởi động được."
  fi
  if docker logs --tail 50 "$c" 2>&1 | grep -qiE 'error|unable to open|refus'; then
    warn "Log của $c có dòng lỗi (xem: ./scripts/logs.sh local|server):"
    docker logs --tail 10 "$c" 2>&1 | sed 's/^/    /'
  fi
done

cat <<EOF

Broker LOCAL đã chạy:
  - Broker LOCAL (thiết bị kết nối): ${GATEWAY_IP}:${LOCAL_PORT}   (TLS)
  - Broker SERVER (backend kết nối): ${SERVER_HOST}:${SERVER_PORT} (TLS)

Kiểm tra tiếp:
  ./scripts/smoke-test.sh        # tự chạy T1-T6, T16, T17
  ./scripts/logs.sh local        # xem log broker local (bridge nằm ở đây)
  ./scripts/logs.sh server       # xem log broker server
EOF