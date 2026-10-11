#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
venv="$root/.tools/importer-venv"
if [[ ! -e "$venv" ]]; then
  "$root/scripts/python.sh" -I -m venv "$venv"
fi
[[ -x "$venv/bin/python" ]] || { echo 'The project test venv is incomplete.' >&2; exit 2; }
[[ $("$venv/bin/python" -I -c 'import platform; print(platform.python_version())') == 3.13.16 ]] || { echo 'The project test venv must use Python 3.13.16.' >&2; exit 2; }
"$venv/bin/python" -I -m pip install --disable-pip-version-check --require-hashes --only-binary=:all: -r "$root/importer/requirements-test.lock"
"$venv/bin/python" -I -m pip check
