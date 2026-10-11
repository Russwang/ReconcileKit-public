#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
export MAVEN_USER_HOME="${MAVEN_USER_HOME:-$root/.tools/maven-home}"
exec "$root/scripts/with-local-java.sh" "$root/backend/mvnw" -B -ntp -Dmaven.repo.local="$root/.tools/m2" -f "$root/backend/pom.xml" "$@"
