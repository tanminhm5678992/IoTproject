#!/usr/bin/env bash
# =============================================================================
# demo-reset.sh - Dọn sạch dữ liệu test TRƯỚC KHI DEMO
#   1. Xóa hết dữ liệu test: commands, telemetry, devices, users
#   2. Tạo lại tài khoản admin từ .env (ADMIN_USERNAME/ADMIN_PASSWORD, hash BCrypt)
#
# Hiệu lực NGAY cả khi backend đang chạy (không cần restart): backend đọc DB
# cho mỗi request đăng nhập.
#
# Dùng:  ./scripts/demo-reset.sh          (hỏi xác nhận)
#        ./scripts/demo-reset.sh --yes    (không hỏi - dùng cho script tự động)
# =============================================================================
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
export ENV_FILE
load_env
need_docker

: "${ADMIN_USERNAME:?Thiếu ADMIN_USERNAME trong .env (xem .env.example)}"
: "${ADMIN_PASSWORD:?Thiếu ADMIN_PASSWORD trong .env (xem .env.example)}"

DB_CONTAINER="${DB_CONTAINER:-smarthome-postgres}"
DB_NAME="${DB_NAME:-smarthome}"
DB_USER="${DB_USER:-smarthome}"
BACKEND_PORT="${BACKEND_HTTP_PORT:-8080}"

docker ps --format '{{.Names}}' | grep -qx "$DB_CONTAINER" \
  || die "PostgreSQL '$DB_CONTAINER' chưa chạy. Hãy chạy: ./scripts/db.sh up"

if [ "${1:-}" != "--yes" ]; then
  printf 'Sẽ XÓA HẾT: devices, telemetry, commands, users (dữ liệu demo cũ).\n'
  printf 'Sẽ tạo lại admin "%s" từ .env. Tiếp tục? [y/N] ' "$ADMIN_USERNAME"
  read -r answer
  case "$answer" in
    y|Y|yes|YES) ;;
    *) log "Hủy."; exit 0 ;;
  esac
fi

psql_exec() { # truyền SQL qua stdin hoặc -c
  docker exec -i "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

log "Xóa dữ liệu test (commands, telemetry, devices, users)..."
psql_exec <<'SQL' || die "TRUNCATE thất bại"
TRUNCATE TABLE commands, telemetry, devices, users RESTART IDENTITY CASCADE;
SQL

# ---- Sinh hash BCrypt bằng java single-file + spring-security-crypto ----------
# Lý do không hardcode hash: mật khẩu lấy từ .env nên hash phải sinh lúc chạy.
# Classpath cần ĐỦ 2 jar:
#   - spring-security-crypto : BCryptPasswordEncoder
#   - spring-jcl             : BCryptPasswordEncoder cần org.apache.commons.logging.LogFactory
#     (thiếu spring-jcl -> java.lang.NoClassDefFoundError: org/apache/commons/logging/LogFactory)
find_jar() { # $1 = thư mục artifact trong ~/.m2, $2 = tên artifact
  ls "$HOME/.m2/repository/$1"/*/"$2"-*.jar 2>/dev/null | grep -v -- '-sources' | head -1
}
CRYPTO_JAR="$(find_jar org/springframework/security/spring-security-crypto spring-security-crypto)"
JCL_JAR="$(find_jar org/springframework/spring-jcl spring-jcl)"
[ -n "$CRYPTO_JAR" ] && [ -n "$JCL_JAR" ] || die "Không thấy spring-security-crypto / spring-jcl trong ~/.m2.
  Chạy một lần để tải dependency:  ./scripts/backend.sh test"
command -v java >/dev/null 2>&1 || die "Thiếu lệnh java (cần JDK 17..21)."

log "Sinh hash BCrypt cho admin từ .env..."
# LƯU Ý (Git Bash/Windows): tham số đường dẫn của `java` là binary native nên PHẢI
# đổi sang dạng Windows bằng win_path(); nếu không java báo "Could not find or load
# main class .../BcryptHash.java" (vì lib.sh đặt MSYS_NO_PATHCONV=1).
JAR_WIN="$(win_path "$CRYPTO_JAR")"
JCL_WIN="$(win_path "$JCL_JAR")"
SRC_WIN="$(win_path "$ROOT/scripts/tools/BcryptHash.java")"
# Classpath phân tách bằng ';' vì `java` ở đây là bản Windows.
CP_WIN="$JAR_WIN;$JCL_WIN"
HASH_ERR="$(mktemp)"
HASH="$(java -cp "$CP_WIN" "$SRC_WIN" "$ADMIN_PASSWORD" 2>"$HASH_ERR" | tail -1)"
case "$HASH" in
  '$2a$'*|'$2b$'*|'$2y$'*) ;;
  *) die "Sinh hash thất bại (java in: '$HASH').
  java stderr: $(tr '\n' ' ' <"$HASH_ERR" | tail -c 400)
  Kiểm tra JDK 17..21 và jar spring-security-crypto/spring-jcl trong ~/.m2." ;;
esac
rm -f "$HASH_ERR"

# Escape dấu nháy đơn cho SQL (mật khẩu kỳ lạ không làm vỡ câu lệnh)
ADMIN_SQL="${ADMIN_USERNAME//\'/\'\'}"
HASH_SQL="${HASH//\'/\'\'}"

log "Tạo lại admin '$ADMIN_USERNAME'..."
psql_exec -c "INSERT INTO users (username, password_hash, role)
  VALUES ('$ADMIN_SQL', '$HASH_SQL', 'ADMIN')
  ON CONFLICT (username) DO UPDATE SET password_hash = EXCLUDED.password_hash, role = 'ADMIN';" \
  || die "Seed admin thất bại"

ok "DB sạch + admin '$ADMIN_USERNAME' đã sẵn sàng cho demo."
echo "  Đăng nhập : POST http://localhost:$BACKEND_PORT/api/auth/login"
echo "  Swagger   : http://localhost:$BACKEND_PORT/swagger-ui/index.html"
echo "  NHỚ: đổi ADMIN_PASSWORD trong .env trước khi nộp bài!"
