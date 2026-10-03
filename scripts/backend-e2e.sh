#!/usr/bin/env bash
# =============================================================================
# backend-e2e.sh - Kiểm thử end-to-end backend (Phase 3): T7-T12, T15
#
# Đi qua REST API THẬT (không chèn tay vào DB như Phase 2):
#   login JWT -> POST /api/devices lấy regToken -> giả lập thiết bị register
#   -> telemetry -> POST lệnh (T10: ack -> DONE) -> (T11: không ack -> TIMEOUT)
#   -> (T12: thiết bị offline -> HTTP 422).
# Giả lập thiết bị bằng mosquitto_pub vào broker LOCAL (TLS, user nodeX) ->
# bridge -> broker SERVER -> backend -> PostgreSQL: đúng đường đi thật của ESP32.
#
# Điều kiện (tất cả phải chạy):
#   ./scripts/up.sh            2 broker + bridge
#   ./scripts/db.sh up         PostgreSQL
#   ./scripts/backend.sh run   backend (đã seed admin từ ADMIN_* trong .env)
#
# Dùng:  ./scripts/backend-e2e.sh [deviceId] [matKhau]
#   mặc định: node1  (mật khẩu lấy NODE1_PASSWORD trong .env)
#   Đăng nhập REST bằng ADMIN_USERNAME / ADMIN_PASSWORD trong .env.
# Chạy lại được nhiều lần (script tự xóa thiết bị cũ trước khi tạo mới).
# =============================================================================
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
export ENV_FILE
load_env
need_docker

MOSQ_IMAGE="${MOSQ_IMAGE:-eclipse-mosquitto:2}"
CERTDIR="$ROOT/certs/out"
[ -f "$CERTDIR/ca.crt" ] || die "Chưa có chứng chỉ TLS. Chạy ./certs/gen-certs.sh trước."

DEVICE="${1:-node1}"
DEV_PASS="${2:-}"
if [ -z "$DEV_PASS" ]; then
  case "$DEVICE" in
    node1) DEV_PASS="${NODE1_PASSWORD:-}" ;;
    node2) DEV_PASS="${NODE2_PASSWORD:-}" ;;
    node3) DEV_PASS="${NODE3_PASSWORD:-}" ;;
  esac
fi
[ -n "$DEV_PASS" ] \
  || die "Thiếu mật khẩu cho '$DEVICE'. Chạy: ./scripts/backend-e2e.sh $DEVICE <matKhau>"

# Tài khoản admin cho các lệnh REST (lấy từ .env, KHÔNG hardcode)
: "${ADMIN_USERNAME:?Thiếu ADMIN_USERNAME trong .env (xem .env.example)}"
: "${ADMIN_PASSWORD:?Thiếu ADMIN_PASSWORD trong .env (xem .env.example)}"

DB_CONTAINER="${DB_CONTAINER:-smarthome-postgres}"
DB_NAME="${DB_NAME:-smarthome}"
DB_USER="${DB_USER:-smarthome}"
BACKEND_PORT="${BACKEND_HTTP_PORT:-8080}"
BASE_URL="http://localhost:$BACKEND_PORT"

for c in "$BROKER_LOCAL_CONTAINER" "$BROKER_SERVER_CONTAINER" "$DB_CONTAINER"; do
  docker ps --format '{{.Names}}' | grep -qx "$c" \
    || die "Container '$c' chưa chạy. Hãy chạy ./scripts/up.sh (và ./scripts/db.sh up)."
done
netstat -ano 2>/dev/null | grep -qE ":$BACKEND_PORT[[:space:]].*LISTENING" \
  || die "Backend chưa nghe cổng $BACKEND_PORT. Hãy chạy: ./scripts/backend.sh run"

TMPD="$(mktemp -d)"
trap 'rm -rf "$TMPD"' EXIT

# curl trên Git Bash là binary native (mingw), KHÔNG phải chương trình MSYS. lib.sh đã
# bật MSYS_NO_PATHCONV=1 (cần cho docker: -v /certs:ro ...), nên đường dẫn /tmp/... sẽ
# được truyền NGUYÊN XI cho curl; curl hiểu đó là C:\tmp\... -> "Failed to open file"
# (exit 23) và api() ghi nhận HTTP 000 dù backend đang chạy.
# => Chỉ đổi riêng đường dẫn dùng cho tham số của curl sang dạng Windows; bash (cat,
#    chuyển hướng 2>) vẫn dùng bản unix bình thường.
if command -v cygpath >/dev/null 2>&1; then TMPD_CURL="$(cygpath -m "$TMPD")"; else TMPD_CURL="$TMPD"; fi

RESULT=()
pass() { printf '%s[ĐẠT]%s %s - %s\n' "$C_OK" "$C_OFF" "$1" "$3"; RESULT+=("$1|ĐẠT|$3"); }
fail() { printf '%s[KHÔNG ĐẠT]%s %s - %s\n' "$C_ERR" "$C_OFF" "$1" "$3" >&2; RESULT+=("$1|KHÔNG ĐẠT|$3"); }

# ---- Tiện ích REST (curl; đọc JSON bằng sed vì máy không có jq) -------------
API_CODE="" API_BODY=""
api() { # $1=METHOD $2=path $3=[json body] -> API_CODE, API_BODY
  local args=(-sS -o "$TMPD_CURL/body" -w '%{http_code}' -X "$1")
  [ -n "${TOKEN:-}" ] && args+=(-H "Authorization: Bearer $TOKEN")
  if [ -n "${3:-}" ]; then args+=(-H 'Content-Type: application/json' -d "$3"); fi
  API_CODE="$(curl "${args[@]}" "$BASE_URL$2" 2>"$TMPD/err")" || API_CODE=000
  API_BODY="$(cat "$TMPD/body" 2>/dev/null || true)"
}
# jget <json> <key> : lấy giá trị chuỗi "key":"value"
jget() { printf '%s' "$1" | tr -d '\n' | sed -n 's/.*"'"$2"'":"\([^"]*\)".*/\1/p' | head -1; }

# Publish như thiết bị thật: vào broker LOCAL (TLS, ACL theo %u) rồi qua bridge.
pub_device() { # $1=topic $2=payload
  docker run --rm -i -v "$(docker_path "$CERTDIR"):/certs:ro" "$MOSQ_IMAGE" \
    mosquitto_pub -h "$GATEWAY_IP" -p "$LOCAL_PORT" --cafile /certs/ca.crt -q 1 \
    -u "$DEVICE" -P "$DEV_PASS" -t "$1" -m "$2"
}

db() { docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }

# Đợi ĐIỀU KIỆN đúng (poll tối đa ~12s): bridge + backend xử lý <1s, nhưng poll
# thay vì sleep mù để chạy ổn định trên máy chậm.
wait_db() { # $1=SQL trả về 1 giá trị  $2=giá trị mong đợi  $3=số lượt (mặc định 12)
  local i tries="${3:-12}"
  for i in $(seq 1 "$tries"); do
    [ "$(db "$1")" = "$2" ] && return 0
    sleep 1
  done
  return 1
}

backend_alive() { netstat -ano 2>/dev/null | grep -qE ":$BACKEND_PORT[[:space:]].*LISTENING"; }

# ---- Đăng nhập REST + tạo thiết bị qua API (bỏ hẳn bước chèn tay vào DB) ----
log "Đăng nhập REST: POST /api/auth/login (admin=$ADMIN_USERNAME)"
api POST /api/auth/login "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$ADMIN_PASSWORD\"}"
[ "$API_CODE" = "200" ] \
  || die "Đăng nhập thất bại (HTTP $API_CODE): $API_BODY
  Kiểm tra: backend đã chạy chưa, ADMIN_USERNAME/ADMIN_PASSWORD trong .env có đúng không?"
TOKEN="$(jget "$API_BODY" token)"
[ -n "$TOKEN" ] || die "Không đọc được token từ response: $API_BODY"
ok "Đăng nhập OK, nhận JWT (${#TOKEN} ký tự, hết hạn 8 giờ)"

# Dọn thiết bị của lần chạy trước (nếu có) để tạo lại từ đầu
api DELETE "/api/devices/$DEVICE"
if [ "$API_CODE" = "204" ]; then log "Đã xóa thiết bị cũ '$DEVICE' (nếu có)"; fi

log "Tạo thiết bị: POST /api/devices"
api POST /api/devices "{\"deviceId\":\"$DEVICE\",\"name\":\"$DEVICE (E2E)\",\"type\":\"sensor\"}"
[ "$API_CODE" = "201" ] \
  || die "Tạo thiết bị thất bại (HTTP $API_CODE): $API_BODY"
REG_TOKEN="$(jget "$API_BODY" regToken)"
if [ -z "$REG_TOKEN" ] || [ "${#REG_TOKEN}" -lt 32 ]; then
  die "Không lấy được regToken đủ mạnh từ API: $API_BODY"
fi
ok "Đã tạo '$DEVICE' qua REST, regToken=${REG_TOKEN:0:12}... (${#REG_TOKEN} ký tự, PENDING)"

# ============================== T9 ==========================================
log "T9: telemetry khi thiết bị còn PENDING -> không được lưu"
pub_device "sh/$DEVICE/telemetry" '{"temp":99.9,"hum":5.5,"ts":1}'
sleep 3
if [ "$(db "select count(*) from telemetry where device_id='$DEVICE'")" = "0" ]; then
  pass "T9" "" "telemetry bị từ chối khi PENDING (0 dòng, WARN ở log backend)"
else
  fail "T9" "" "đã lưu telemetry dù thiết bị chưa ACTIVE"
fi

# ============================== T8 ==========================================
log "T8: register với regToken SAI -> bỏ qua, vẫn PENDING"
pub_device "sh/$DEVICE/register" '{"regToken":"sai-token-e2e","fw":"0.0.0"}'
sleep 3
if [ "$(db "select reg_status from devices where device_id='$DEVICE'")" = "PENDING" ]; then
  pass "T8" "" "regToken sai bị bỏ qua, reg_status vẫn PENDING (WARN ở log backend)"
else
  fail "T8" "" "chuyển trạng thái dù regToken sai: $(db "select reg_status from devices where device_id='$DEVICE'")"
fi

# ============================== T7 ==========================================
log "T7: register ĐÚNG regToken (lấy từ POST /api/devices) -> ACTIVE + online=true"
pub_device "sh/$DEVICE/register" "{\"regToken\":\"$REG_TOKEN\",\"fw\":\"1.0.0-e2e\"}"
if wait_db "select reg_status from devices where device_id='$DEVICE'" "ACTIVE" \
   && wait_db "select online from devices where device_id='$DEVICE'" "t"; then
  pass "T7" "" "reg_status=ACTIVE, online=true, fw=$(db "select fw_version from devices where device_id='$DEVICE'")"
else
  fail "T7" "" "không chuyển được sang ACTIVE (reg_status=$(db "select reg_status from devices where device_id='$DEVICE'"))"
fi

# ---- Đường dẫn phụ: sau ACTIVE thì telemetry phải được lưu -------------------
log "Kiểm tra thêm: telemetry SAU KHI ACTIVE -> phải được lưu"
pub_device "sh/$DEVICE/telemetry" '{"temp":30.1,"hum":70.5,"ts":1}'
if wait_db "select count(*) from telemetry where device_id='$DEVICE'" "1"; then
  pass "E2E-1" "" "telemetry sau ACTIVE được lưu (1 dòng) + last_seen cập nhật"
else
  fail "E2E-1" "" "telemetry không được lưu dù thiết bị đã ACTIVE"
fi

# ============================== T15 =========================================
log "T15: register LẶP LẠI (mỗi 5 phút như firmware) -> không lỗi, vẫn ACTIVE"
dev_before="$(db "select count(*) from devices where device_id='$DEVICE'")"
pub_device "sh/$DEVICE/register" "{\"regToken\":\"$REG_TOKEN\",\"fw\":\"1.0.0-e2e\"}"
sleep 3
reg_after="$(db "select reg_status from devices where device_id='$DEVICE'")"
online_after="$(db "select online from devices where device_id='$DEVICE'")"
dev_after="$(db "select count(*) from devices where device_id='$DEVICE'")"
if [ "$reg_after" = "ACTIVE" ] && [ "$online_after" = "t" ] \
   && [ "$dev_after" = "$dev_before" ] && backend_alive; then
  pass "T15" "" "gửi lặp lại an toàn: vẫn ACTIVE+online, không nhân bản bản ghi, backend còn chạy"
else
  fail "T15" "" "reg=$reg_after online=$online_after dev $dev_before->$dev_after backend_alive=$(backend_alive && echo yes || echo no)"
fi

# ============================== T10 =========================================
# Thiết bị SUBSCRIBE sh/{id}/cmd trước (như firmware), sau đó REST POST lệnh:
# backend publish -> broker SERVER -> bridge -> broker LOCAL -> thiết bị nhận
# -> thiết bị trả ack -> backend đổi DONE.
log "T10: POST lệnh khi thiết bị ONLINE -> thiết bị nhận cmd -> ack -> DONE"
docker run --rm -i -v "$(docker_path "$CERTDIR"):/certs:ro" "$MOSQ_IMAGE" sh -c \
  "timeout 30 mosquitto_sub -h '$GATEWAY_IP' -p '$LOCAL_PORT' --cafile /certs/ca.crt \
   -q 1 -u '$DEVICE' -P '$DEV_PASS' -t 'sh/$DEVICE/cmd' -C 1 -v" \
  > "$TMPD/cmd.txt" 2> "$TMPD/sub.err" &
SUB_PID=$!
sleep 2   # chờ subscriber kết nối broker LOCAL trước khi gửi lệnh

api POST "/api/devices/$DEVICE/commands" '{"target":"relay1","value":"ON"}'
if [ "$API_CODE" != "201" ]; then
  fail "T10" "" "POST /commands trả HTTP $API_CODE (kỳ vọng 201): $API_BODY"
else
  CMD_ID="$(jget "$API_BODY" cmdId)"
  CMD_STATUS="$(jget "$API_BODY" status)"
  log "Lệnh 1: cmdId=$CMD_ID status=$CMD_STATUS"
  wait "$SUB_PID" 2>/dev/null || true
  if ! grep -q "\"cmdId\":\"$CMD_ID\"" "$TMPD/cmd.txt" 2>/dev/null; then
    fail "T10" "" "thiết bị KHÔNG nhận được lệnh qua bridge (sub: $(head -c 200 "$TMPD/sub.err" 2>/dev/null))"
  else
    log "Thiết bị đã nhận lệnh, gửi ack result=ok"
    pub_device "sh/$DEVICE/ack" "{\"cmdId\":\"$CMD_ID\",\"result\":\"ok\"}"
    if wait_db "select status from commands where cmd_id='$CMD_ID'" "DONE" 15; then
      pass "T10" "" "lệnh $CMD_ID: PENDING -> thiết bị nhận qua bridge -> ack -> DONE"
    else
      fail "T10" "" "lệnh không chuyển sang DONE (status=$(db "select status from commands where cmd_id='$CMD_ID'"))"
    fi
  fi
fi

# ============================== T11 =========================================
# Không subscribe, không ack -> CommandTimeoutJob (mỗi 5s) đổi PENDING -> TIMEOUT
# khi lệnh quá 10 giây không có ack (mục 9).
log "T11: POST lệnh và KHÔNG ack -> TIMEOUT sau 10 giây (job chạy mỗi 5 giây)"
api POST "/api/devices/$DEVICE/commands" '{"target":"led1","value":"ON"}'
if [ "$API_CODE" != "201" ]; then
  fail "T11" "" "POST /commands trả HTTP $API_CODE (kỳ vọng 201): $API_BODY"
else
  CMD2="$(jget "$API_BODY" cmdId)"
  log "Lệnh 2: cmdId=$CMD2 - chủ động KHÔNG gửi ack, đợi tối đa 25s..."
  if wait_db "select status from commands where cmd_id='$CMD2'" "TIMEOUT" 25; then
    pass "T11" "" "lệnh $CMD2 hết ack -> CommandTimeoutJob chuyển PENDING -> TIMEOUT"
  else
    fail "T11" "" "lệnh $CMD2 không TIMEOUT (status=$(db "select status from commands where cmd_id='$CMD2'"))"
  fi
fi

# ============================== T12 =========================================
# Đã chốt: gửi lệnh yêu cầu thiết bị ACTIVE và online, nếu không thì 422.
log "T12: đưa thiết bị OFFLINE rồi POST lệnh -> phải trả HTTP 422"
pub_device "sh/$DEVICE/status" 'offline'
if wait_db "select online from devices where device_id='$DEVICE'" "f"; then
  api POST "/api/devices/$DEVICE/commands" '{"target":"relay2","value":"ON"}'
  if [ "$API_CODE" = "422" ]; then
    pass "T12" "" "thiết bị offline -> POST /commands trả 422: $(printf '%s' "$API_BODY" | head -c 120)"
  else
    fail "T12" "" "kỳ vọng 422 nhưng nhận HTTP $API_CODE: $API_BODY"
  fi
else
  fail "T12" "" "không chuyển được thiết bị sang offline"
fi

# ---- Đường dẫn phụ: status online lại (LWT/heartbeat) cập nhật cờ online -----
log "Kiểm tra thêm: status online lại -> cập nhật cờ online (E2E-2)"
pub_device "sh/$DEVICE/status" 'online'
if wait_db "select online from devices where device_id='$DEVICE'" "t"; then
  pass "E2E-2" "" "status offline/online đổi đúng cờ online trong DB"
else
  fail "E2E-2" "" "cờ online không quay lại đúng (t)"
fi

# ---- Tổng kết ----------------------------------------------------------------
echo "============================================================="
failed=0
for r in "${RESULT[@]}"; do case "$r" in *"|KHÔNG ĐẠT|"*) failed=$((failed+1));; esac; done
if [ "$failed" -eq 0 ]; then
  ok "Tất cả ${#RESULT[@]} test ĐẠT."
else
  printf '%s%d/%d test KHÔNG ĐẠT.%s\n' "$C_ERR" "$failed" "${#RESULT[@]}" "$C_OFF" >&2
fi
echo "Bằng chứng DB : SELECT * FROM devices  WHERE device_id='$DEVICE';"
echo "                SELECT cmd_id, target, value, status, created_by FROM commands ORDER BY id;"
echo "                SELECT * FROM telemetry WHERE device_id='$DEVICE';"
echo "Log backend   : regToken sai | ACTIVE | nhận lặp lại - idempotent | đã lưu"
echo "                ack: lệnh | timeout: lệnh | Đăng nhập thành công"
echo "Swagger       : http://localhost:$BACKEND_HTTP_PORT/swagger-ui/index.html"
exit "$failed"
