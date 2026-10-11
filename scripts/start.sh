#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
if [[ $# -gt 2 ]]; then
  echo 'Usage: scripts/start.sh [rk-project-name] [environment-file]' >&2
  exit 2
fi
project=${1:-rk-demo}
env_file=${2:-$root/.env}
if [[ ! "$project" =~ ^rk-[a-z0-9][a-z0-9_-]*$ || ${#project} -gt 63 ]]; then
  echo 'Project name must start with rk- and contain only lowercase letters, digits, underscores or hyphens (maximum 63 characters).' >&2
  exit 2
fi
if [[ ! -f "$env_file" ]]; then
  echo 'Environment file missing. Copy .env.example to your environment file and set a random DB_PASSWORD.' >&2
  exit 2
fi
# Reject the example value without executing or displaying the environment file.
if grep -Eq '^[[:space:]]*DB_PASSWORD[[:space:]]*=.*replace-with-a-long-random-password' "$env_file"; then
  echo 'Replace the example DB_PASSWORD before starting.' >&2
  exit 2
fi
env_dir=$(cd "$(dirname "$env_file")" && pwd)
env_file="$env_dir/$(basename "$env_file")"
exec "$root/scripts/with-docker.sh" docker compose \
  --project-name "$project" --env-file "$env_file" --file "$root/infra/compose.yaml" \
  up --detach --build --wait --wait-timeout 180
