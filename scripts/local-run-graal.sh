#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_ROOT="${APP_ROOT:-$(cd "$ROOT/../ores-ror.rb" && pwd)}"

export APP_ROOT
export GRAAL_MANIFEST_PATH="${GRAAL_MANIFEST_PATH:-$APP_ROOT/.ores-generated/graal/manifest.json}"
export GRAAL_WORKER_PLACEMENT="${GRAAL_WORKER_PLACEMENT:-${ISOLATION_GRANULARITY:-route}}"
export GRAAL_WORKER_STARTUP="${GRAAL_WORKER_STARTUP:-lazy}"
export PORT="${PORT:-3200}"
export GRAAL_THREAD_POOL_SIZE="${GRAAL_THREAD_POOL_SIZE:-32}"
export ISOLATE_MAX_CONCURRENCY="${ISOLATE_MAX_CONCURRENCY:-${CONTEXT_THREAD_POOL_SIZE:-${CONTEXT_ADMISSION_LIMIT:-${CONTEXT_MAX_CONCURRENCY:-5}}}}"
export CONTEXT_MAX_AGE_SECONDS="${CONTEXT_MAX_AGE_SECONDS:-1800}"
export CONTEXT_IDLE_SECONDS="${CONTEXT_IDLE_SECONDS:-300}"
export CONTEXT_DRAIN_SECONDS="${CONTEXT_DRAIN_SECONDS:-30}"
export REQUEST_TIMEOUT_SECONDS="${REQUEST_TIMEOUT_SECONDS:-15}"
export DATA_API_URL="${DATA_API_URL:-http://127.0.0.1:8787/v1}"

test -f "$GRAAL_MANIFEST_PATH" || {
  echo "missing $GRAAL_MANIFEST_PATH; run scripts/local-build-graal.sh first" >&2
  exit 1
}

exec java -jar "$ROOT/target/ores-ror-infra-0.3.0.jar"
