#!/usr/bin/env bash
# =============================================================================
# gen-certs.sh - Tạo CA + chứng chỉ TLS cho broker LOCAL và broker SERVER
#
# Dùng:            ./certs/gen-certs.sh
# Tạo CA mới:      REGEN_CA=1 ./certs/gen-certs.sh
# Biến lấy từ .env: CERT_IPS (danh sách IP trong SAN), CERT_DNS, CERT_DAYS
#
# Vì sao cần SAN: ESP32 (WiFiClientSecure) và Paho/mosquitto sẽ từ chối
# chứng chỉ nếu địa chỉ đang kết nối không nằm trong SAN. Đổi mạng thì
# chạy ./scripts/set-network.sh <ip> để sinh lại cert có IP mới.
# =============================================================================
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../scripts/lib.sh"
load_env
need_openssl

OPENSSL="${OPENSSL:-openssl}"
DAYS="${CERT_DAYS:-825}"
OUT="$ROOT/certs/out"
mkdir -p "$OUT"
# openssl trên Git Bash (Windows) là binary native mingw64: nó KHÔNG hiểu đường dẫn
# POSIX /d/... => phải truyền dạng Windows qua cygpath (docker_path).
# Ta vẫn giữ MSYS_NO_PATHCONV=1 để -subj "/CN=..." không bị MSYS đổi thành đường dẫn.
OUTW="$(docker_path "$OUT")"

# SAN dùng chung cho cả 2 cert: tất cả IP đã biết + 2 DNS name
san_string() {
  local san="" ip d
  for ip in $CERT_IPS; do san="${san:+$san,}IP:$ip"; done
  for d in ${CERT_DNS:-broker-local broker-server}; do san="${san:+$san,}DNS:$d"; done
  printf '%s' "$san"
}

# ---- 1. CA (dùng lại nếu đã có, trừ khi REGEN_CA=1) ------------------------
if [ ! -f "$OUT/ca.key" ] || [ "${REGEN_CA:-0}" = "1" ]; then
  log "Tạo CA mới (CN=SmartHome-CA, hạn $DAYS ngày)..."
  "$OPENSSL" genrsa -out "$OUTW/ca.key" 2048
  "$OPENSSL" req -x509 -new -nodes -key "$OUTW/ca.key" -sha256 -days "$DAYS" \
    -subj "/CN=SmartHome-CA/O=SmartHome Lab/C=VN" -out "$OUTW/ca.crt"
else
  log "Dùng lại CA đã có: $OUT/ca.key (tạo CA mới: REGEN_CA=1 ./certs/gen-certs.sh)"
fi

# ---- 2. Sinh cert cho từng broker -----------------------------------------
gen_cert() { # $1 = tên file (local|server), $2 = CN/DNS
  local name="$1" cn="$2"
  "$OPENSSL" genrsa -out "$OUTW/$name.key" 2048
  "$OPENSSL" req -new -key "$OUTW/$name.key" -subj "/CN=$cn/O=SmartHome Lab/C=VN" \
    -out "$OUTW/$name.csr"
  # File phần mở rộng: SAN + mục đích sử dụng khóa (serverAuth)
  printf 'subjectAltName=%s\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' \
    "$(san_string)" > "$OUT/$name.ext"
  "$OPENSSL" x509 -req -in "$OUTW/$name.csr" -CA "$OUTW/ca.crt" -CAkey "$OUTW/ca.key" \
    -CAcreateserial -out "$OUTW/$name.crt" -days "$DAYS" -sha256 \
    -extfile "$OUTW/$name.ext"
  log "Đã ký cert: $OUT/$name.crt (CN=$cn)"
}

gen_cert local  broker-local
gen_cert server broker-server

# ---- 3. Chép vào thư mục từng broker (thư mục này không commit) -----------
mkdir -p "$ROOT/broker-local/certs" "$ROOT/server/broker-server/certs"
cp -f "$OUT/ca.crt" "$OUT/local.crt"  "$OUT/local.key"  "$ROOT/broker-local/certs/"
cp -f "$OUT/ca.crt" "$OUT/server.crt" "$OUT/server.key" "$ROOT/server/broker-server/certs/"
chmod 600 "$OUT"/*.key 2>/dev/null || true
chmod 644 "$OUT"/*.crt 2>/dev/null || true

# ---- 4. In SAN để tự kiểm tra ---------------------------------------------
log "SAN của cert local : $("$OPENSSL" x509 -in "$OUTW/local.crt"  -noout -ext subjectAltName | tail -n +2 | tr -d ' ')"
log "SAN của cert server: $("$OPENSSL" x509 -in "$OUTW/server.crt" -noout -ext subjectAltName | tail -n +2 | tr -d ' ')"
log "Hạn cert local: $("$OPENSSL" x509 -in "$OUTW/local.crt" -noout -enddate | cut -d= -f2)"
ok "Xong. Chứng chỉ dùng chung CA tại $OUT"
warn "Không commit certs/out (đã có trong .gitignore)."