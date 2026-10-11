#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
exec "$root/scripts/python.sh" -I "$root/importer/run.py" "$@"
