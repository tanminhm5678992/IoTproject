#!/usr/bin/env bash
# =============================================================================
# server-up.sh – Khởi động toàn bộ tầng server (broker-server + postgres + backend + frontend)
# Dùng: ./scripts/server-up.sh
# =============================================================================
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_env
need_docker

# Dùng win_path() vì lib.sh bật MSYS_NO_PATHCONV=1:
# docker native trên Windows không tự chuyển /d/... → D:/...
COMPOSE_FILE="$(docker_path "$ROOT/server/docker-compose.yml")"
ENV_FILE_WIN="$(docker_path "$ROOT/.env")"

log "Build và khởi động server stack (broker-server + postgres + backend + frontend)..."
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE_WIN" up -d --build

log "Đợi các service sẵn sàng (healthcheck)..."
sleep 10

log "Trạng thái container:"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE_WIN" ps

cat <<EOF

Server stack đã chạy:
  - Broker SERVER (MQTTS):  localhost:${SERVER_PORT:-8884}
  - PostgreSQL:             localhost:${DB_PORT:-5432}
  - Backend REST API:       http://localhost:${BACKEND_HTTP_PORT:-8080}
  - Swagger UI:             http://localhost:${BACKEND_HTTP_PORT:-8080}/swagger-ui/index.html
  - Frontend Web:           http://localhost:3000

Bước tiếp: Mở http://localhost:3000 và đăng nhập (${ADMIN_USERNAME:-admin} / <ADMIN_PASSWORD từ .env>)

Xem log nếu có lỗi:
  docker logs smarthome-backend    --tail 50
  docker logs smarthome-broker-server --tail 30
EOF
