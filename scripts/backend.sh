#!/usr/bin/env bash
# =============================================================================
# backend.sh - Chạy / kiểm thử backend Spring Boot trên host (Phase 2)
#
# Script tự đọc .env rồi truyền cấu hình cho Spring Boot, nên KHÔNG phải sửa
# application.yml khi đổi IP/mạng:
#   DB_URL / DB_USER / DB_PASSWORD -> PostgreSQL của ./scripts/db.sh
#   MQTT_URL / MQTT_USERNAME / MQTT_PASSWORD / MQTT_CA_CERT -> broker SERVER (TLS)
#
# Lưu ý về TLS: cert của đồ án có SAN là các IP trong CERT_IPS (gồm
# GATEWAY_IP và 127.0.0.1) + DNS broker-local/broker-server, KHÔNG có
# "localhost" -> phải nối bằng IP, nếu không Paho sẽ lỗi kiểm tra hostname.
#
# Dùng:  ./scripts/backend.sh [run|test|build|clean]
#   run   (mặc định) chạy backend ở tiền cảnh, Ctrl+C để dừng
#   test  chạy toàn bộ unit test + integration test (Testcontainers)
#   build đóng gói jar (bỏ qua test)
#   clean xoá thư mục target/
#
# Biến môi trường:
#   FORCE_KILL_PORT=1  dọn MỌI tiến trình đang giữ cổng HTTP trước khi chạy
#                      (mặc định chỉ tự dọn tiến trình java còn sót lại)
# =============================================================================
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_env

# lib.sh bật MSYS_NO_PATHCONV=1 (cần cho docker với các path /mosquitto/...) nhưng cờ này
# lại làm HỎNG `mvn` trên Git Bash: script mvn của Maven dựa vào MSYS để đổi
# "/c/Program Files/..." thành "C:\..." khi gọi java - nếu tắt, java báo
# "Could not find or load main class org.codehaus.plexus.classworlds.launcher.Launcher".
# backend.sh không truyền path kiểu unix cho docker nên tắt cờ ở đây là an toàn.
unset MSYS_NO_PATHCONV

# Đường dẫn cho java.exe: trong Git Bash $ROOT có dạng /d/... nên phải đổi thành D:/...
# thì tham số truyền cho Spring Boot (MQTT_CA_CERT) mới đọc được.
host_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

# ---- Dọn backend còn sót trên cổng HTTP ------------------------------------
# Hàm tra cứu nằm ở lib.sh (port_listener_pids / proc_name_of_pid / kill_pid_force).
# Chính sách: chỉ tự giết tiến trình java (backend Spring Boot của đồ án); gặp
# tiến trình lạ thì báo lỗi để không giết nhầm ứng dụng khác của người dùng.
# Đặt FORCE_KILL_PORT=1 nếu muốn giết mọi thứ đang giữ cổng.
is_backend_proc() { # $1 = tên tiến trình
  case "$1" in java|java.exe|*-java.exe) return 0 ;; *) return 1 ;; esac
}

free_http_port() { # $1 = 1: báo LỖI nếu gặp tiến trình lạ; 0 (mặc định): chỉ cảnh báo
  local strict="${1:-0}" pids pid name
  pids="$(port_listener_pids "$BACKEND_HTTP_PORT")"
  [ -n "$pids" ] || return 0
  for pid in $pids; do
    name="$(proc_name_of_pid "$pid")"
    if is_backend_proc "$name" || [ "${FORCE_KILL_PORT:-0}" = "1" ]; then
      warn "Cổng $BACKEND_HTTP_PORT: '$name' (PID $pid) còn sót lại - đang dừng..."
      kill_pid_force "$pid"
    elif [ "$strict" = "1" ]; then
      die "Cổng $BACKEND_HTTP_PORT đang bị '$name' (PID $pid) chiếm, không phải tiến trình java nên script không tự giết.
  Cách xử lý: dừng tiến trình đó rồi chạy lại, ví dụ:
    taskkill /F /PID $pid
  Hoặc để script tự giết mọi thứ đang giữ cổng:
    FORCE_KILL_PORT=1 ./scripts/backend.sh run"
    else
      warn "Cổng $BACKEND_HTTP_PORT: '$name' (PID $pid) vẫn giữ cổng nhưng không phải java - bỏ qua."
    fi
  done
  wait_port_free "$BACKEND_HTTP_PORT" 10
}

# Múi giờ cho MỌI JVM mà script khởi động (JVM của mvn, spring-boot:run, surefire).
# Vì sao: JDK trên Windows (máy VN) mặc định "Asia/Saigon", nhưng image postgres:16
# chính thức KHÔNG có zoneinfo Asia/Saigon (chỉ có tên canonical Asia/Ho_Chi_Minh).
# PGJDBC gửi TimeZone của JVM trong startup packet nên PostgreSQL từ chối kết nối:
#   FATAL: invalid value for parameter "TimeZone": "Asia/Saigon"
# Đổi sang tên canonical vẫn là UTC+7 -> dữ liệu/hiển thị không đổi.
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.timezone=${APP_TIMEZONE:-Asia/Ho_Chi_Minh}"

BACKEND_DIR="$ROOT/server/backend"
ACTION="${1:-run}"
MVN_OFFLINE="${MVN_OFFLINE:-1}"   # 1 = chạy mvn -o (không ra mạng); đặt 0 khi cần tải dependency

command -v mvn >/dev/null 2>&1 || die "Chưa có lệnh mvn (Maven). Cài Maven hoặc dùng mvnw nếu có."

# Kiểm tra JDK mà Maven sẽ dùng (ưu tiên JAVA_HOME, không thì `java` trong PATH).
# Lý do: Java quá mới (vd 25 của IDE) làm Mockito/byte-buddy không mock được ->
# lỗi khó hiểu "Mockito cannot mock this class". Spring Boot 3.3 chỉ hỗ trợ 17..21.
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then JBIN="$JAVA_HOME/bin/java"; else JBIN="java"; fi
if command -v "$JBIN" >/dev/null 2>&1 || [ -x "$JBIN" ]; then
  ver_full="$("$JBIN" -version 2>&1 | sed -n 's/.*version "\([0-9._-]*\)".*/\1/p' | head -1)"
  major="${ver_full%%.*}"
  [ "$major" = "1" ] && major="$(printf '%s' "$ver_full" | cut -d. -f2)"   # Java 8: 1.8.0_x
  case "$major" in
    ''|*[!0-9]*) ;;   # không đọc được số -> bỏ qua kiểm tra
    *)
      if { [ "$major" -lt 17 ] || [ "$major" -gt 21 ]; } && [ "${SKIP_JDK_CHECK:-0}" != "1" ]; then
        die "JDK $major không hợp lệ (cần 17..21 cho Spring Boot 3.3). Đặt JAVA_HOME, ví dụ:
  export JAVA_HOME=\"\$HOME/.jdks/jbr-17.0.14\"
(set SKIP_JDK_CHECK=1 nếu bạn chắc chắn muốn chạy với JDK khác)"
      fi
      ;;
  esac
fi

mvn_cmd() { # "$@" = tham số của Maven
  local args=(-B)
  [ "$MVN_OFFLINE" = "1" ] && args+=(-o)
  ( cd "$BACKEND_DIR" && mvn "${args[@]}" "$@" )
}

echo
if command -v java >/dev/null 2>&1; then
  log "JDK: $(java -version 2>&1 | head -1)"
else
  warn "Không thấy lệnh java trong PATH (Maven cần JAVA_HOME trỏ tới JDK 17..21)."
fi

# ---- run: cần PostgreSQL (và nên có broker SERVER) --------------------------
if [ "$ACTION" = "run" ]; then
  need_docker
  docker ps --format '{{.Names}}' | grep -qx "${DB_CONTAINER:-smarthome-postgres}" \
    || die "PostgreSQL chưa chạy. Hãy chạy: ./scripts/db.sh up"
  if docker ps --format '{{.Names}}' | grep -qx "$BROKER_SERVER_CONTAINER"; then
    log "Broker SERVER '$BROKER_SERVER_CONTAINER' đang chạy"
  else
    warn "Broker SERVER '$BROKER_SERVER_CONTAINER' chưa chạy - backend vẫn khởi động và tự nối lại (./scripts/up.sh)"
  fi

  MQTT_CA_CERT="${MQTT_CA_CERT:-$ROOT/certs/out/ca.crt}"
  [ -r "$MQTT_CA_CERT" ] \
    || die "Không đọc được CA của MQTT: $MQTT_CA_CERT (sinh lại: ./certs/gen-certs.sh)"
  MQTT_CA_CERT="$(host_path "$MQTT_CA_CERT")"

  export DB_URL="jdbc:postgresql://localhost:${DB_PORT:-5432}/${DB_NAME:-smarthome}"
  export DB_USER="${DB_USER:-smarthome}"
  export DB_PASSWORD="${DB_PASSWORD:-smarthome}"
  export MQTT_URL="ssl://${SERVER_HOST}:${SERVER_PORT}"
  export MQTT_USERNAME="${BACKEND_USERNAME:-backend_user}"
  export MQTT_PASSWORD="${BACKEND_PASSWORD:-}"
  export MQTT_CA_CERT
  export MQTT_CLIENT_ID="${MQTT_CLIENT_ID:-smarthome-backend}"
  export MQTT_ENABLED=true

  # SERVER_HOST/SERVER_PORT trong .env là CỦA BROKER MQTT. load_env đã export chúng
  # (set -a) và Spring Boot đọc biến SERVER_PORT qua relaxed binding thành
  # `server.port` -> backend tranh chiếm đúng cổng broker đang nghe rồi chết với:
  #   Web server failed to start. Port 8884 was already in use.
  # MQTT_URL đã dựng ở trên nên giờ bỏ 2 biến này đi; cổng HTTP cho REST API (Phase 3)
  # dùng tên riêng BACKEND_HTTP_PORT để không bao giờ phụ thuộc SERVER_PORT.
  unset SERVER_HOST SERVER_PORT
  export BACKEND_HTTP_PORT="${BACKEND_HTTP_PORT:-8080}"

  # Cổng HTTP phải trống trước khi chạy (xem chính sách ở free_http_port ở trên):
  # tránh "Port 8080 was already in use" và tránh 2 backend tranh phiên MQTT.
  free_http_port 1 \
    || die "Cổng $BACKEND_HTTP_PORT vẫn bị chiếm sau khi dọn. Kiểm tra bằng: netstat -ano | grep :$BACKEND_HTTP_PORT"
  # Sau khi thoát (kể cả Ctrl+C): Maven dừng nhưng JVM con do spring-boot:run
  # fork ra có thể vẫn sống -> dọn ngay, nếu không lần chạy sau sẽ bị chiếm cổng.
  trap 'free_http_port 0 || true' EXIT INT TERM

  [ -n "$MQTT_PASSWORD" ] || die "Thiếu BACKEND_PASSWORD trong $ENV_FILE (xem .env.example)."

  cat <<EOF
Backend sẽ chạy với:
  PostgreSQL : $DB_URL  (user=$DB_USER)
  Múi giờ JVM: ${APP_TIMEZONE:-Asia/Ho_Chi_Minh} (postgres:16 không nhận "Asia/Saigon")
  HTTP (API) : http://localhost:$BACKEND_HTTP_PORT  (REST API Phase 3)
  Swagger UI : http://localhost:$BACKEND_HTTP_PORT/swagger-ui/index.html
  MQTT       : $MQTT_URL  (user=$MQTT_USERNAME, ca=$MQTT_CA_CERT)
Cần dừng: Ctrl+C
EOF
  echo
  mvn_cmd spring-boot:run
  exit 0
fi

# ---- test / build / clean: không cần Docker --------------------------------
case "$ACTION" in
  test)  log "Chạy toàn bộ test (unit + Testcontainers PostgreSQL)..."; mvn_cmd test ;;
  build) log "Đóng gói jar (bỏ qua test)...";                         mvn_cmd -DskipTests package ;;
  clean) log "Xoá target/...";                                        mvn_cmd clean ;;
  *)     die "Lệnh không hợp lệ: '$ACTION'. Dùng: ./scripts/backend.sh [run|test|build|clean]" ;;
esac
