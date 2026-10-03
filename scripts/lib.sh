#!/usr/bin/env bash
# =============================================================================
# lib.sh - Hàm dùng chung cho mọi script trong scripts/
# Không chạy trực tiếp file này; các script khác "source" nó.
# Hỗ trợ cả WSL2 (Ubuntu) và Git Bash trên Windows.
# =============================================================================

# Không dùng `set -e` ở đây để script gọi có thể tự xử lý lỗi.
set -uo pipefail

# Git Bash trên Windows: tắt tự chuyển đổi đường dẫn kiểu MSYS
# (nếu không, "/mosquitto/config" sẽ bị đổi thành "C:/Program Files/.../mosquitto/config")
if [ -n "${MSYSTEM:-}" ]; then export MSYS_NO_PATHCONV=1; fi

# Thư mục gốc dự án (thư mục chứa file .env)
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Cho phép đổi file cấu hình khi cần test: ENV_FILE=.env.p1test ./scripts/...
ENV_FILE="${ENV_FILE:-$ROOT/.env}"

# ---- Log có màu, in ra stderr ----------------------------------------------
if [ -t 1 ]; then
  C_INFO=$'\033[0;36m'; C_WARN=$'\033[0;33m'; C_ERR=$'\033[0;31m'
  C_OK=$'\033[0;32m';   C_OFF=$'\033[0m'
else
  C_INFO=''; C_WARN=''; C_ERR=''; C_OK=''; C_OFF=''
fi
log()  { printf '%s[%s]%s %s\n' "$C_INFO" "$(date +%H:%M:%S)" "$C_OFF" "$*"; }
ok()   { printf '%s[ĐẠT]%s %s\n' "$C_OK" "$C_OFF" "$*"; }
warn() { printf '%s[CẢNH BÁO]%s %s\n' "$C_WARN" "$C_OFF" "$*" >&2; }
die()  { printf '%s[LỖI]%s %s\n' "$C_ERR" "$C_OFF" "$*" >&2; exit 1; }

# ---- Đọc .env --------------------------------------------------------------
load_env() {
  [ -f "$ENV_FILE" ] || die "Không thấy $ENV_FILE. Hãy chạy: cp .env.example .env  rồi điền giá trị."
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
  : "${GATEWAY_IP:?Thiếu GATEWAY_IP trong .env}"
  : "${SERVER_HOST:?Thiếu SERVER_HOST trong .env}"
  : "${SERVER_PORT:?Thiếu SERVER_PORT trong .env}"
  : "${LOCAL_PORT:?Thiếu LOCAL_PORT trong .env}"
  : "${CERT_IPS:?Thiếu CERT_IPS trong .env}"
  : "${BROKER_LOCAL_CONTAINER:?Thiếu BROKER_LOCAL_CONTAINER trong .env}"
  : "${BROKER_SERVER_CONTAINER:?Thiếu BROKER_SERVER_CONTAINER trong .env}"
}

# ---- Sửa 1 biến trong .env (thêm dòng nếu chưa có) -------------------------
set_env_var() { # $1 = tên biến, $2 = giá trị (có thể chứa dấu cách)
  local key="$1" val="$2" tmp
  tmp="$(mktemp)"
  if grep -qE "^${key}=" "$ENV_FILE"; then
    # Thay cả dòng KEY=... (giữ nguyên vị trí); dùng | làm dấu phân cách
    sed -E "s|^${key}=.*|${key}=${val}|" "$ENV_FILE" > "$tmp"
  else
    cat "$ENV_FILE" > "$tmp"
    printf '%s=%s\n' "$key" "$val" >> "$tmp"
  fi
  mv "$tmp" "$ENV_FILE"
  # Nạp lại để biến trong shell hiện tại cũng đổi theo
  set -a; . "$ENV_FILE"; set +a
}

# ---- Kiểm tra môi trường ---------------------------------------------------
validate_ip() { # $1 = địa chỉ IPv4
  case "${1:-}" in
    ''|*[!0-9.]*) die "Địa chỉ IP không hợp lệ: '${1:-}'" ;;
  esac
  # Mỗi octet trong 0..255
  local o
  for o in $(printf '%s' "$1" | tr '.' ' '); do
    [ "$o" -ge 0 ] 2>/dev/null && [ "$o" -le 255 ] || die "Địa chỉ IP không hợp lệ: $1"
  done
  printf '%s' "$1" | grep -qE '^([0-9]{1,3}\.){3}[0-9]{1,3}$' || die "Địa chỉ IP không hợp lệ: $1"
}

need_docker() {
  command -v docker >/dev/null 2>&1 || die "Chưa có lệnh docker. Cài Docker Desktop rồi mở lại terminal."
  docker info >/dev/null 2>&1 || die "Docker chưa chạy. Hãy mở Docker Desktop và chờ tới khi báo 'Engine running'."
}

need_openssl() {
  command -v openssl >/dev/null 2>&1 || die "Chưa có openssl. Trong WSL: sudo apt install -y openssl"
}

# ---- Cổng TCP: ai đang giữ cổng và cách dọn tiến trình còn sót -------------
# Vì sao cần: trên Git Bash, Ctrl+C chỉ dừng tiến trình Maven; JVM con mà
# `spring-boot:run` fork ra vẫn sống -> vẫn giữ cổng HTTP (lần chạy sau chết
# với "Port 8080 was already in use") và vẫn giữ phiên MQTT cùng clientId
# (broker đá phiên liên tục -> telemetry/lệnh bị rớt). backend.sh dùng các hàm
# dưới đây để dọn trước khi chạy và sau khi dừng.
is_windows_shell() {
  case "$(uname -s 2>/dev/null)" in MINGW*|MSYS*|CYGWIN*) return 0 ;; *) return 1 ;; esac
}

# In ra PID đang LISTEN trên cổng $1 (mỗi dòng 1 PID; có thể nhiều PID vì
# Docker Desktop proxy cổng). Không có gì thì in ra rỗng. Luôn trả về 0 để
# không làm script gọi (set -e) thoát vì một lệnh tra cứu thất bại.
port_listener_pids() { # $1 = số cổng
  local port="$1"
  if is_windows_shell; then
    # netstat -ano của Windows: TCP <local> <foreign> <state> <pid>
    # (MSYS_NO_PATHCONV=1 để "-ano" không bị MSYS đổi thành đường dẫn)
    MSYS_NO_PATHCONV=1 netstat -ano 2>/dev/null \
      | awk -v p=":$port" '$1=="TCP" && $2 ~ p"$" && $4=="LISTENING" {print $5}' \
      | sort -u || true
  elif command -v ss >/dev/null 2>&1; then
    ss -lptnH "sport = :$port" 2>/dev/null | sed -n 's/.*pid=\([0-9]*\).*/\1/p' | sort -u || true
  fi
  return 0
}

# Tên tiến trình của PID $1 (vd "java.exe"); rỗng nếu không xác định được.
# Dùng để chỉ tự giết tiến trình java của đồ án, tránh giết nhầm ứng dụng khác.
proc_name_of_pid() { # $1 = PID
  local pid="$1" out=""
  if is_windows_shell; then
    # tasklist cũng cần MSYS_NO_PATHCONV=1 (nếu không: "Invalid argument - 'C:/Program Files/Git/FI'")
    out="$(MSYS_NO_PATHCONV=1 tasklist /FI "PID eq $pid" /NH /FO CSV 2>/dev/null | head -1 | cut -d, -f1 | tr -d '"' || true)"
  elif [ -r "/proc/$pid/comm" ]; then
    out="$(head -1 "/proc/$pid/comm" 2>/dev/null || true)"
  fi
  # Khi PID đã biến mất, tasklist in dòng "INFO: No tasks are running..." ->
  # coi như không xác định (không phải tên tiến trình).
  case "$out" in *[!A-Za-z0-9._-]*) out="" ;; esac
  printf '%s' "$out"
  return 0
}

# Giết tiến trình theo PID (Windows: taskkill; Linux/WSL: kill -9).
kill_pid_force() { # $1 = PID
  local pid="$1"
  if is_windows_shell; then
    # taskkill đòi "/F /PID"; nếu MSYS đổi "/F" thành "F:/" thì taskkill báo
    # "ERROR: Invalid argument/option - 'F:/'" -> phải bật MSYS_NO_PATHCONV cho
    # riêng lệnh này (đã kiểm chứng thực tế bằng probe).
    MSYS_NO_PATHCONV=1 taskkill /F /PID "$pid" >/dev/null 2>&1 || true
  else
    kill -9 "$pid" >/dev/null 2>&1 || true
  fi
  return 0
}

# Chờ tối đa $2 giây (mặc định 10) cho tới khi cổng $1 không còn ai LISTEN.
# Trả về 1 nếu hết thời gian mà cổng vẫn bị chiếm.
wait_port_free() { # $1 = số cổng, $2 = số giây
  local port="$1" limit="${2:-10}" i=0
  while [ "$i" -lt "$limit" ]; do
    [ -z "$(port_listener_pids "$port")" ] && return 0
    sleep 1
    i=$((i + 1))
  done
  [ -z "$(port_listener_pids "$port")" ]
}

# ---- Đường dẫn cho lệnh NATIVE (java, curl, docker) ------------------------
# Trên Git Bash (Windows), biến $ROOT có dạng /d/Thu_Muc/... nên phải đổi sang
# dạng Windows "D:/Thu_Muc/..." thì lệnh native (không phải MSYS) mới hiểu.
# BẮT BUỘC dùng hàm này cho tham số đường dẫn của java/curl/docker vì lib.sh đã
# bật MSYS_NO_PATHCONV=1 (xem đầu file) -> MSYS KHÔNG tự đổi đường dẫn nữa.
# Ví dụ: java -cp "$JAR" "$ROOT/scripts/tools/BcryptHash.java"  -> java báo
# "Could not find or load main class .../BcryptHash.java" nếu quên.
win_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

# ---- Đường dẫn cho Docker -------------------------------------------------
docker_path() {
  win_path "$1"
}

# ---- Docker image của Mosquitto --------------------------------------------
MOSQ_IMAGE="${MOSQ_IMAGE:-eclipse-mosquitto:2}"

# ---- Docker Compose với .env ở thư mục gốc ---------------------------------
compose() { # $1 = đường dẫn file compose, còn lại là tham số của compose
  local file; file="$(docker_path "$1")"; shift
  docker compose --env-file "$(docker_path "$ENV_FILE")" -f "$file" "$@"
}

# ---- Quyền file cấu hình cho user 'mosquitto' trong container --------------
# Mosquitto 2.x tự hạ quyền xuống user 'mosquitto' (uid 1883) khi chạy bằng root.
# File passwd/acl do script tạo (chủ root, quyền 0644/0777) sẽ gây:
#   - "password-file: Error: Unable to open pwfile" (broker KHÔNG chạy được)
#   - "Warning: File ... owner is not mosquitto" / "has world readable permissions"
# => chown về mosquitto + chmod 0700 ngay bên trong container (chủ sở hữu mới đọc).
harden_config_perms() { # $1 = thư mục config trên host
  local cfg="$1"
  docker run --rm -i --entrypoint sh \
    -v "$(docker_path "$cfg"):/mosquitto/config" \
    "$MOSQ_IMAGE" -c '
      for f in passwd acl; do
        p="/mosquitto/config/$f"
        if [ -f "$p" ]; then
          chown mosquitto:mosquitto "$p" && chmod 0700 "$p"
        fi
      done
      c="/mosquitto/config/mosquitto.conf"
      [ -f "$c" ] && chown mosquitto:mosquitto "$c"
      exit 0
    ' >/dev/null 2>&1 \
    || warn "Không đổi được quyền file trong $cfg (xem ./scripts/logs.sh để kiểm tra)"
}

# ---- Sinh file từ template (thay @TÊN_BIẾN@ bằng giá trị trong .env) -------
render_template() { # $1 = template, $2 = file đích, còn lại: cặp NAME=VALUE
  local tpl="$1" out="$2"; shift 2
  local sed_args=() pair
  for pair in "$@"; do
    sed_args+=(-e "s|@${pair%%=*}@|${pair#*=}|g")
  done
  sed "${sed_args[@]}" "$tpl" > "$out"
  # Cấu hình có chứa mật khẩu (bridge) nên hạn chế quyền đọc
  chmod 600 "$out" 2>/dev/null || true
  log "Đã sinh $out"
}