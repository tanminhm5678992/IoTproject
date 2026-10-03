#!/usr/bin/env bash
# =============================================================================
# db.sh - PostgreSQL 16 phục vụ backend khi chạy trên host (Phase 2)
#
# Vì sao cần: Phase 2 chạy backend bằng `mvn spring-boot:run` ngay trên máy
# (chưa đóng gói Docker), nhưng backend cần PostgreSQL để Flyway tạo schema và
# lưu telemetry. Script này chạy MỘT container PostgreSQL 16 với đúng DB/user/
# password trong .env. (Phase 6 sẽ thay bằng service `postgres` trong
# server/docker-compose.yml.)
#
# Dùng:  ./scripts/db.sh [up|down|reset|status|logs|psql]
#   up     (mặc định) chạy container rồi đợi tới khi sẵn sàng
#   down   dừng container (GIỮ dữ liệu trong volume)
#   reset  xoá container + volume dữ liệu rồi dựng lại (schema do Flyway tạo lại)
#   status in trạng thái container và số dòng các bảng
#   logs   xem log PostgreSQL
#   psql   mở psql trong container (gõ \q để thoát)
# =============================================================================
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_env
need_docker

# Giá trị mặc định khớp với server/backend/src/main/resources/application.yml
DB_CONTAINER="${DB_CONTAINER:-smarthome-postgres}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-smarthome}"
DB_USER="${DB_USER:-smarthome}"
DB_PASSWORD="${DB_PASSWORD:-smarthome}"
PG_IMAGE="${PG_IMAGE:-postgres:16}"
DB_VOLUME="${DB_VOLUME:-${DB_CONTAINER}_data}"

db_running() { docker ps    --format '{{.Names}}' | grep -qx "$DB_CONTAINER"; }
db_exists()  { docker ps -a --format '{{.Names}}' | grep -qx "$DB_CONTAINER"; }

# Chạy 1 câu SQL, in kết quả thô (dùng cho kiểm tra nhanh)
db_query() { # $1 = câu SQL
  docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc "$1" 2>/dev/null
}

cmd_up() {
  if db_running; then
    ok "PostgreSQL '$DB_CONTAINER' đã chạy (cổng $DB_PORT)"
  elif db_exists; then
    log "Container '$DB_CONTAINER' đang dừng -> khởi động lại (giữ nguyên dữ liệu)"
    docker start "$DB_CONTAINER" >/dev/null
  else
    log "Tạo container PostgreSQL từ image $PG_IMAGE (volume $DB_VOLUME)..."
    docker run -d --name "$DB_CONTAINER" \
      -e POSTGRES_DB="$DB_NAME" \
      -e POSTGRES_USER="$DB_USER" \
      -e POSTGRES_PASSWORD="$DB_PASSWORD" \
      -p "${DB_PORT}:5432" \
      -v "${DB_VOLUME}:/var/lib/postgresql/data" \
      --health-cmd "pg_isready -U $DB_USER -d $DB_NAME" \
      --health-interval 5s --health-timeout 3s --health-retries 10 \
      "$PG_IMAGE" >/dev/null
  fi

  log "Đợi PostgreSQL sẵn sàng..."
  local i
  for i in $(seq 1 30); do
    if docker exec "$DB_CONTAINER" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1; then
      ok "PostgreSQL sẵn sàng: jdbc:postgresql://localhost:${DB_PORT}/${DB_NAME}  (user=$DB_USER)"
      return 0
    fi
    sleep 1
  done
  warn "Log cuối của $DB_CONTAINER:"
  docker logs --tail 20 "$DB_CONTAINER" 2>&1 | sed 's/^/    /'
  die "PostgreSQL không sẵn sàng sau 30 giây."
}

cmd_down() {
  db_exists || die "Không có container '$DB_CONTAINER'."
  docker stop "$DB_CONTAINER" >/dev/null && ok "Đã dừng '$DB_CONTAINER' (dữ liệu vẫn còn trong volume $DB_VOLUME)"
}

cmd_reset() {
  warn "XOÁ toàn bộ dữ liệu của '$DB_CONTAINER' (container + volume $DB_VOLUME)."
  if db_exists; then docker rm -f "$DB_CONTAINER" >/dev/null; fi
  docker volume rm "$DB_VOLUME" >/dev/null 2>&1 || true
  ok "Đã xoá. Dựng lại từ đầu (Flyway sẽ tạo schema mới khi backend khởi động)..."
  cmd_up
}

cmd_status() {
  db_running || die "Container '$DB_CONTAINER' chưa chạy. Hãy chạy: ./scripts/db.sh up"
  docker ps --filter "name=$DB_CONTAINER" --format '  {{.Names}}  {{.Status}}  {{.Ports}}'
  echo
  log "Bảng trong DB $DB_NAME:"
  db_query "select table_name from information_schema.tables where table_schema='public' order by 1" \
    | sed 's/^/    /'
  echo
  local t n
  for t in users devices telemetry commands; do
    if n="$(db_query "select count(*) from $t")"; then
      printf '    %-10s : %s dòng\n' "$t" "$n"
    else
      printf '    %-10s : chưa có bảng - chạy backend để Flyway migrate\n' "$t"
    fi
  done
}

cmd_logs() { docker logs -f --tail 100 "$DB_CONTAINER"; }

cmd_psql() {
  db_running || die "Container '$DB_CONTAINER' chưa chạy. Hãy chạy: ./scripts/db.sh up"
  log "Mở psql (db=$DB_NAME user=$DB_USER) - gõ \\q để thoát"
  docker exec -it "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME"
}

case "${1:-up}" in
  up)     cmd_up ;;
  down)   cmd_down ;;
  reset)  cmd_reset ;;
  status) cmd_status ;;
  logs)   cmd_logs ;;
  psql)   cmd_psql ;;
  *)      die "Lệnh không hợp lệ: '$1'. Dùng: ./scripts/db.sh [up|down|reset|status|logs|psql]" ;;
esac
