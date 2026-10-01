#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export APP_ROOT="${APP_ROOT:-$(cd ../ores-ror.rb && pwd)}"
export PORT="${PORT:-3200}"
export MIN_ISOLATES="${MIN_ISOLATES:-2}"
export MAX_ISOLATES="${MAX_ISOLATES:-4}"
export ISOLATE_MAX_CONCURRENCY="${ISOLATE_MAX_CONCURRENCY:-5}"
export ISOLATE_MAX_AGE_SECONDS="${ISOLATE_MAX_AGE_SECONDS:-1800}"
export ISOLATE_IDLE_SECONDS="${ISOLATE_IDLE_SECONDS:-300}"
export DATA_API_URL="${DATA_API_URL:-http://127.0.0.1:8787/v1}"
exec java -jar target/ores-ror-infra-0.3.0.jar
