#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
python="$root/.tools/importer-venv/bin/python"
[[ -x "$python" ]] || { echo 'Run scripts/setup-cli-tests.sh first.' >&2; exit 2; }
[[ $("$python" -I -c 'import platform; print(platform.python_version())') == 3.13.16 ]] || { echo 'Test Python must be exactly 3.13.16.' >&2; exit 2; }
cd "$root"
exec "$python" -I -m pytest -c "$root/importer/pyproject.toml" "$root/importer/tests" "$@"
