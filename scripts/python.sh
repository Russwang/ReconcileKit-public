#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
interpreter=${RK_PYTHON:-"$root/.tools/python-3.13.16/python/bin/python3.13"}
if [[ ! -x "$interpreter" ]]; then
  interpreter=$(command -v python3.13 || true)
fi
[[ -n "$interpreter" && -x "$interpreter" ]] || { echo 'Python 3.13.16 is required; run scripts/bootstrap-python.sh or set RK_PYTHON.' >&2; exit 2; }
[[ $("$interpreter" -I -c 'import platform; print(platform.python_version())') == 3.13.16 ]] || { echo 'Python must be exactly 3.13.16.' >&2; exit 2; }
exec "$interpreter" "$@"
