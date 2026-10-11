#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
exec python3 -I "$root/scripts/core-acceptance.py" "$@"
