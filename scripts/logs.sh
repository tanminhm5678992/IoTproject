#!/usr/bin/env bash
# =============================================================================
# logs.sh - Xem log broker (theo dõi bridge ở broker LOCAL)
# Dùng: ./scripts/logs.sh local   |   ./scripts/logs.sh server
# =============================================================================
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_env
need_docker

case "${1:-local}" in
  local)  docker logs -f --tail 100 "$BROKER_LOCAL_CONTAINER" ;;
  server) docker logs -f --tail 100 "$BROKER_SERVER_CONTAINER" ;;
  *) die "Dùng: ./scripts/logs.sh local | server" ;;
esac