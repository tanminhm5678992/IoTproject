#!/usr/bin/env bash
# =============================================================================
# down.sh - Dừng 2 broker (GIỮ LẠI dữ liệu trong volume)
# Dùng: ./scripts/down.sh
# =============================================================================
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_env
need_docker

log "Dừng broker LOCAL..."
compose "$ROOT/broker-local/docker-compose.yml" down
log "Dừng broker SERVER..."
compose "$ROOT/server/broker-server/docker-compose.yml" down
ok "Đã dừng. Dữ liệu retained vẫn còn trong Docker volume."
warn "Xóa sạch dữ liệu (retained, session bridge) thì thêm -v: docker compose down -v"