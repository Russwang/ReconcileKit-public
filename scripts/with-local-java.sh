#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in "$root"/.tools/jdk-*/Contents/Home "$root"/.tools/amazon-corretto-*.jdk/Contents/Home; do
    [[ -x "$candidate/bin/java" ]] && export JAVA_HOME="$candidate" && break
  done
fi
[[ -n "${JAVA_HOME:-}" ]] || { echo 'Set JAVA_HOME to a Java 21 JDK' >&2; exit 1; }
export PATH="$JAVA_HOME/bin:$PATH"
exec "$@"
