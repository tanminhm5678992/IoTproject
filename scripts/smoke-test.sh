#!/usr/bin/env bash
# =============================================================================
# smoke-test.sh - Tự kiểm tra tầng broker (Phase 1): T1-T6, T16, T17
# Dùng:  ./scripts/up.sh  rồi  ./scripts/smoke-test.sh
# Ghi chú: mọi client MQTT được chạy trong container eclipse-mosquitto:2 vì
# máy không cài sẵn mosquitto_pub/mosquitto_sub. Mọi kết nối đều đi qua
# GATEWAY_IP / SERVER_HOST + cổng đã publish (giống đường đi thật của ESP32).
# =============================================================================
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
export ENV_FILE
load_env
need_docker

MOSQ_IMAGE="${MOSQ_IMAGE:-eclipse-mosquitto:2}"
CERTDIR="$ROOT/certs/out"
[ -f "$CERTDIR/ca.crt" ] || die "Chưa có chứng chỉ TLS. Chạy ./certs/gen-certs.sh trước."

TMP="$(mktemp -d)"
cleanup() {
  rm -rf "$TMP"
  docker ps --format '{{.Names}}' | grep '^sh-smoke-' | while read -r c; do
    docker rm -f "$c" >/dev/null 2>&1 || true
  done
}
trap cleanup EXIT

for c in "$BROKER_SERVER_CONTAINER" "$BROKER_LOCAL_CONTAINER"; do
  docker ps --format '{{.Names}}' | grep -qx "$c" || die "Container $c chưa chạy. Hãy chạy ./scripts/up.sh trước."
done

RESULT=()
pass() { printf '%s[ĐẠT]%s %s - %s\n' "$C_OK" "$C_OFF" "$1" "$3"; RESULT+=("$1|ĐẠT|$3"); }
fail() { printf '%s[KHÔNG ĐẠT]%s %s - %s\n' "$C_ERR" "$C_OFF" "$1" "$3" >&2; RESULT+=("$1|KHÔNG ĐẠT|$3"); }

# ---- Tiện ích gọi client MQTT ----------------------------------------------
mq() { # $1=host $2=port $3=pub|sub, còn lại là tham số cho mosquitto_*
  local h="$1" p="$2" bin="$3"; shift 3
  docker run --rm -i -v "$(docker_path "$CERTDIR"):/certs:ro" "$MOSQ_IMAGE" \
    "mosquitto_$bin" -h "$h" -p "$p" --cafile /certs/ca.crt "$@"
}
pub_local()  { mq "$GATEWAY_IP"  "$LOCAL_PORT"  pub "$@"; }
pub_server() { mq "$SERVER_HOST" "$SERVER_PORT" pub "$@"; }
sub_local()  { mq "$GATEWAY_IP"  "$LOCAL_PORT"  sub "$@"; }
sub_server() { mq "$SERVER_HOST" "$SERVER_PORT" sub "$@"; }

LAST_PID=""
LAST_OUT=""   # đường dẫn file .out của subscriber nền vừa tạo (đọc kết quả tại đây!)
sub_bg() { # $1=tên ngắn $2=local|server, còn lại = tham số mosquitto_sub
  local name="sh-smoke-$1" where="$2"; shift 2
  local h p
  case "$where" in
    local)  h="$GATEWAY_IP";  p="$LOCAL_PORT"  ;;
    server) h="$SERVER_HOST"; p="$SERVER_PORT" ;;
    *) die "sub_bg: where phải là local|server" ;;
  esac
  docker run --rm --name "$name" -v "$(docker_path "$CERTDIR"):/certs:ro" "$MOSQ_IMAGE" \
    mosquitto_sub -h "$h" -p "$p" --cafile /certs/ca.crt "$@" \
    >"$TMP/$name.out" 2>"$TMP/$name.err" &
  LAST_PID=$!
  LAST_OUT="$TMP/$name.out"   # nhớ đường dẫn để test đọc đúng file
}

# ---- "Cổng chờ" thay cho sleep mù ------------------------------------------
# Lý do: container subscriber cần vài giây mới khởi động xong rồi SUBSCRIBE.
# Chỉ `sleep 2` rồi publish NGAY là không đủ tin cậy (từng gây fail T3/T4/T5/T16).
# KHÔNG thể dùng `mosquitto_sub -d` để chờ dòng "Subscribed": debug của nó bị
# block-buffer khi ghi ra file, chỉ xả ra lúc tiến trình thoát.
# => Dùng log của broker (connection_messages true) làm mốc: log ghi tức thời.

# Đếm số dòng log "client connected" cho một username
conn_count() { docker logs "$1" 2>&1 | grep -c "u'$2'"; }

# Đợi broker ghi log thêm kết nối của user $2 so với mốc $3 (tối đa ~15s)
wait_conn() { # $1=container $2=username $3=số kết nối trước đó
  local i n
  for i in $(seq 1 30); do
    n="$(conn_count "$1" "$2")"
    [ "$n" -gt "$3" ] && return 0
    sleep 0.5
  done
  return 1
}

# Publish LẶP LẠI cho tới khi subscriber nền nhận được message (hoặc hết lượt).
# Với mosquitto_sub -C 1: nhận 1 message là thoát, nên vòng lặp này tự dừng đúng lúc.
pub_until_recv() { # $1=file .out của subscriber $2=local|server $3=số lần thử, còn lại = tham số mosquitto_pub
  local f="$1" where="$2" tries="$3"; shift 3
  local i
  for i in $(seq 1 "$tries"); do
    case "$where" in
      local)  pub_local  "$@" >/dev/null 2>&1 ;;
      server) pub_server "$@" >/dev/null 2>&1 ;;
      *) die "pub_until_recv: where phải là local|server" ;;
    esac
    sleep 1
    [ -s "$f" ] && return 0
  done
  return 1
}

# ============================== T1 ==========================================
log "T1: node1 kết nối broker LOCAL bằng TLS + mật khẩu đúng"
out="$(pub_local -u node1 -P "$NODE1_PASSWORD" -d \
        -t sh/node1/telemetry -m '{"temp":28.5,"hum":65.2,"ts":0}' 2>&1)"; rc=$?
if [ "$rc" -eq 0 ] && printf '%s' "$out" | grep -q 'CONNACK'; then
  pass T1 "node1" "Kết nối TLS + xác thực thành công (có CONNACK), publish được"
else
  fail T1 "node1" "rc=$rc; log: $(printf '%s' "$out" | tail -3 | tr '\n' ' ')"
fi

# ============================== T2 ==========================================
log "T2: node1 kết nối với mật khẩu SAI (phải bị từ chối)"
out="$(pub_local -u node1 -P 'mat_khau_sai_123' \
        -t sh/node1/telemetry -m '{}' 2>&1)"; rc=$?
if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -qiE 'not authori|refused|connection error'; then
  pass T2 "node1" "Bị từ chối khi sai mật khẩu: $(printf '%s' "$out" | grep -iE 'refused|authori' | head -1)"
else
  fail T2 "node1" "Không thấy bị từ chối (rc=$rc): $(printf '%s' "$out" | tail -2 | tr '\n' ' ')"
fi

# ============================== T3 ==========================================
log "T3: node1 publish vào topic của node2 (phải bị ACL chặn, không tới server)"
before3s="$(conn_count "$BROKER_SERVER_CONTAINER" "$BACKEND_USERNAME")"
before3l="$(conn_count "$BROKER_LOCAL_CONTAINER" "$LOCAL_MONITOR_USER")"
sub_bg t3server server -u "$BACKEND_USERNAME" -P "$BACKEND_PASSWORD" \
  -t 'sh/node2/telemetry' -W 20 -F '%t|%p'; p1=$LAST_PID; o1="$LAST_OUT"
sub_bg t3local local -u "$LOCAL_MONITOR_USER" -P "$LOCAL_MONITOR_PASSWORD" \
  -t 'sh/node2/telemetry' -W 20 -F '%t|%p'; p2=$LAST_PID; o2="$LAST_OUT"
# Cổng chờ: chỉ publish SAU khi cả 2 subscriber đã thực sự kết nối (thay sleep mù)
wait_conn "$BROKER_SERVER_CONTAINER" "$BACKEND_USERNAME" "$before3s" \
  || warn "T3: không thấy log kết nối của $BACKEND_USERNAME ở server"
wait_conn "$BROKER_LOCAL_CONTAINER" "$LOCAL_MONITOR_USER" "$before3l" \
  || warn "T3: không thấy log kết nối của $LOCAL_MONITOR_USER ở local"
sleep 1   # nhường thời gian xử lý SUBSCRIBE
pub_local -u node1 -P "$NODE1_PASSWORD" -t sh/node2/telemetry \
  -m '{"temp":99,"hum":1,"ts":0}' >/dev/null 2>&1
sleep 3   # cửa sổ quan sát: nếu ACL chặn thì phải KHÔNG có gì
docker rm -f sh-smoke-t3server sh-smoke-t3local >/dev/null 2>&1 || true
wait "$p1" "$p2" 2>/dev/null
t3s="$(cat "$o1" 2>/dev/null)"; t3l="$(cat "$o2" 2>/dev/null)"
if printf '%s%s' "$t3s" "$t3l" | grep -q '"temp":99'; then
  fail T3 "ACL" "CÓ message lọt qua (server:'$t3s' local:'$t3l')"
else
  pass T3 "ACL" "node1 ghi vào sh/node2/telemetry nhưng không ai nhận (ACL chặn đúng)"
fi
# ============================== T4 ==========================================
log "T4: telemetry của node1 đi qua bridge tới broker SERVER"
sub_bg t4server server -u "$BACKEND_USERNAME" -P "$BACKEND_PASSWORD" \
  -t 'sh/node1/telemetry' -C 1 -W 30 -F '%t|%p'; p4=$LAST_PID; o4="$LAST_OUT"
TS="$(date +%s)"
# Publish lặp lại cho tới khi subscriber nhận được (container cần vài giây để subscribe)
pub_until_recv "$o4" local 8 -u node1 -P "$NODE1_PASSWORD" \
  -t sh/node1/telemetry -m "{\"temp\":28.5,\"hum\":65.2,\"ts\":$TS}" || true
docker rm -f sh-smoke-t4server >/dev/null 2>&1 || true
wait "$p4" 2>/dev/null
t4out="$(cat "$o4" 2>/dev/null)"
if printf '%s' "$t4out" | grep -q "^sh/node1/telemetry|.*$TS"; then
  pass T4 "bridge lên" "Message xuất hiện ở broker SERVER qua bridge: $t4out"
else
  fail T4 "bridge lên" "Không nhận được ở server (nhận: '$t4out'). Xem log bridge: ./scripts/logs.sh local"
fi

# ============================== T5 ==========================================
log "T5: lệnh publish ở broker SERVER đi xuống node1 qua bridge"
sub_bg t5local local -u "$LOCAL_MONITOR_USER" -P "$LOCAL_MONITOR_PASSWORD" \
  -t 'sh/node1/cmd' -C 1 -W 30 -F '%t|%p'; p5=$LAST_PID; o5="$LAST_OUT"
pub_until_recv "$o5" server 8 -u "$BACKEND_USERNAME" -P "$BACKEND_PASSWORD" -q 1 \
  -t sh/node1/cmd -m '{"cmdId":"smoke-t5","target":"relay1","value":"ON"}' || true
docker rm -f sh-smoke-t5local >/dev/null 2>&1 || true
wait "$p5" 2>/dev/null
t5out="$(cat "$o5" 2>/dev/null)"
if printf '%s' "$t5out" | grep -q 'smoke-t5'; then
  pass T5 "bridge xuống" "Thiết bị ở broker LOCAL nhận được lệnh: $t5out"
else
  fail T5 "bridge xuống" "Không nhận được ở local (nhận: '$t5out'). Kiểm tra ACL/bridge: ./scripts/logs.sh local"
fi

# ============================== T6 ==========================================
log "T6: mất kết nối đột ngột -> LWT 'offline' retained (kiểm tra cả retain qua bridge)"
# Xoá retained cũ (nếu còn sót từ lần chạy trước) để test không "đạt giả"
pub_local -u node1 -P "$NODE1_PASSWORD" -q 1 -r -n -t sh/node1/status >/dev/null 2>&1
sleep 1
ret_stale="$(sub_server -u "$BACKEND_USERNAME" -P "$BACKEND_PASSWORD" -t sh/node1/status -C 1 -W 4 2>/dev/null)"
[ -z "$ret_stale" ] || warn "T6: vẫn còn retained cũ ở SERVER: '$ret_stale'"
docker rm -f sh-smoke-t6 >/dev/null 2>&1 || true
before6="$(conn_count "$BROKER_LOCAL_CONTAINER" node1)"
docker run -d --name sh-smoke-t6 -v "$(docker_path "$CERTDIR"):/certs:ro" "$MOSQ_IMAGE" \
  mosquitto_sub -h "$GATEWAY_IP" -p "$LOCAL_PORT" --cafile /certs/ca.crt \
    -u node1 -P "$NODE1_PASSWORD" -t 'sh/node1/keepalive' -q 1 -W 120 \
    --will-topic sh/node1/status --will-payload offline --will-qos 1 --will-retain >/dev/null
# Cổng chờ: đợi broker ghi log node1 đã kết nối (thay cho sleep 4 không tin cậy)
if ! wait_conn "$BROKER_LOCAL_CONTAINER" node1 "$before6"; then
  fail T6 "LWT" "Client thử nghiệm không kết nối được: $(docker logs sh-smoke-t6 2>&1 | tail -2 | tr '\n' ' ')"
else
  docker kill --signal=KILL sh-smoke-t6 >/dev/null   # ngắt đột ngột, không gửi DISCONNECT
  sleep 2
  ret_local="$(sub_local  -u "$LOCAL_MONITOR_USER"  -P "$LOCAL_MONITOR_PASSWORD" -t sh/node1/status -C 1 -W 6 2>/dev/null)"
  ret_server="$(sub_server -u "$BACKEND_USERNAME" -P "$BACKEND_PASSWORD" -t sh/node1/status -C 1 -W 6 2>/dev/null)"
  if [ "$ret_local" = "offline" ] && [ "$ret_server" = "offline" ]; then
    pass T6 "LWT + retain" "LWT 'offline' retained thấy ở CẢ LOCAL và SERVER (retain giữ qua bridge)"
  elif [ "$ret_local" = "offline" ]; then
    fail T6 "LWT + retain" "LWT OK ở LOCAL nhưng SERVER không thấy retained (retain không qua bridge): '$ret_server'"
  else
    fail T6 "LWT + retain" "Không thấy LWT retained ở LOCAL (local='$ret_local', server='$ret_server')"
  fi
fi
docker rm -f sh-smoke-t6 >/dev/null 2>&1 || true

# ============================== T16 =========================================
log "T16: add-device.sh node3 -> thiết bị mới kết nối được, không restart broker"
out="$(bash "$ROOT/scripts/add-device.sh" node3 "$NODE3_PASSWORD" 2>&1)"; rc=$?
if [ "$rc" -ne 0 ]; then
  fail T16 "add-device" "add-device.sh lỗi: $(printf '%s' "$out" | tail -2 | tr '\n' ' ')"
else
  sub_bg t16server server -u "$BACKEND_USERNAME" -P "$BACKEND_PASSWORD" \
    -t 'sh/node3/telemetry' -C 1 -W 30 -F '%t|%p'; p16=$LAST_PID; o16="$LAST_OUT"
  # Publish lặp lại tới khi nhận được (node3 vừa được thêm, container cần thời gian subscribe)
  pub_until_recv "$o16" local 8 -u node3 -P "$NODE3_PASSWORD" \
    -t sh/node3/telemetry -m '{"temp":26.4,"hum":58.5,"ts":0}'; rc3=$?
  docker rm -f sh-smoke-t16server >/dev/null 2>&1 || true
  wait "$p16" 2>/dev/null
  t16out="$(cat "$o16" 2>/dev/null)"
  if [ "$rc3" -eq 0 ] && printf '%s' "$t16out" | grep -q 'sh/node3/telemetry'; then
    pass T16 "add-device" "node3 kết nối ngay (không restart broker) và dữ liệu qua bridge tới server"
  else
    fail T16 "add-device" "node3 publish rc=$rc3, server nhận: '$t16out'"
  fi
fi

# ============================== T17 =========================================
log "T17: kiểm tra cấu hình phục vụ đổi mạng (cert SAN, bridge address, secrets.h)"
t17_notes=""
if command -v openssl >/dev/null 2>&1; then
  CERTW="$(docker_path "$CERTDIR")"
  san_local="$(openssl x509 -in "$CERTW/local.crt" -noout -ext subjectAltName 2>/dev/null | tail -n +2 | tr -d ' ')"
  san_server="$(openssl x509 -in "$CERTW/server.crt" -noout -ext subjectAltName 2>/dev/null | tail -n +2 | tr -d ' ')"
  # SAN của openssl 3.x in ra \"IPAddress:x.x.x.x\" (1.1 in \"IP Address:...\")
  # nên chỉ cần kiểm tra có mặt địa chỉ IP là đủ.
  printf '%s' "$san_local"  | grep -q "$GATEWAY_IP"  || t17_notes="$t17_notes cert-local thiếu IP $GATEWAY_IP;"
  printf '%s' "$san_server" | grep -q "$SERVER_HOST" || t17_notes="$t17_notes cert-server thiếu IP $SERVER_HOST;"
else
  t17_notes="$t17_notes không có openssl nên bỏ qua kiểm tra SAN;"
fi
grep -q "address $SERVER_HOST:$SERVER_PORT" "$ROOT/broker-local/config/mosquitto.conf" 2>/dev/null \
  || t17_notes="$t17_notes mosquitto.conf bridge sai địa chỉ;"
grep -q "$GATEWAY_IP" "$ROOT/firmware/node1_sensor/secrets.h" 2>/dev/null \
  || t17_notes="$t17_notes secrets.h thiếu IP gateway;"
grep -q 'BEGIN CERTIFICATE' "$ROOT/firmware/node2_actuator/secrets.h" 2>/dev/null \
  || t17_notes="$t17_notes secrets.h thiếu ROOT_CA;"
if [ -z "$t17_notes" ]; then
  pass T17 "đổi mạng" "cert có IP hiện tại, bridge trỏ $SERVER_HOST:$SERVER_PORT, secrets.h đã sinh đúng"
else
  fail T17 "đổi mạng" "Vấn đề:$t17_notes (chạy: ./scripts/set-network.sh $GATEWAY_IP ${ACTIVE_SLOT:-phone})"
fi

# ============================== TỔNG KẾT ====================================
echo
echo "================ KẾT QUẢ SMOKE TEST (Phase 1) ================"
printf '%-5s %-11s %s\n' "Test" "Kết quả" "Ghi chú"
for r in "${RESULT[@]}"; do
  IFS='|' read -r t k n <<<"$r"
  printf '%-5s %-11s %s\n' "$t" "$k" "$n"
done
failed=0
for r in "${RESULT[@]}"; do case "$r" in *"|KHÔNG ĐẠT|"*) failed=$((failed+1));; esac; done
echo "============================================================="
if [ "$failed" -eq 0 ]; then
  ok "Tất cả ${#RESULT[@]} test ĐẠT."
else
  printf '%s%d/%d test KHÔNG ĐẠT.%s\n' "$C_ERR" "$failed" "${#RESULT[@]}" "$C_OFF" >&2
fi
exit "$failed"