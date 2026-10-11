#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
if ! command -v docker >/dev/null 2>&1; then
 for dir in "$root/.tools/docker-cli/docker" "$root/.tools/docker"; do
  [[ -x "$dir/docker" ]] && export PATH="$dir:$PATH" && break
 done
fi
socket="$root/.tools/lima-state/reconcilekit/sock/docker.sock"
if [[ -z "${DOCKER_HOST:-}" && -S "$socket" ]]; then
 export DOCKER_HOST="unix://$socket"
 export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
 export TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1
fi
if [[ -x "$root/.tools/docker-config/cli-plugins/docker-compose" ]]; then
 export DOCKER_CONFIG="$root/.tools/docker-config"
fi
exec "$@"
