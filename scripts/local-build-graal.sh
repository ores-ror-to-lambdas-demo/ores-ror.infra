#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_ROOT="${APP_ROOT:-$(cd "$ROOT/../ores-ror.rb" && pwd)}"

for tool in ruby mvn java; do
  command -v "$tool" >/dev/null 2>&1 || { echo "$tool is required" >&2; exit 1; }
done

(
  cd "$APP_ROOT"
  ORES_BUILD_TARGET=graal \
  ORES_INFRA_ROOT="$ROOT" \
    ORES_BUILD_OUTPUT=.ores-generated \
    ruby bin/build-runtime
)

test -f "$APP_ROOT/.ores-generated/graal/common.rb"
test -f "$APP_ROOT/.ores-generated/graal/manifest.json"
test -f "$APP_ROOT/.ores-generated/graal/groups/orders/handler.rb"
test -f "$APP_ROOT/.ores-generated/graal/routes/users/[id]/_get/handler.rb"
! grep -R -E 'config/environment|Rails\.application|ActionController|ActionView' "$APP_ROOT/.ores-generated/graal"
! grep -R -F 'require "json"' "$APP_ROOT/.ores-generated/graal"
mvn -f "$ROOT/pom.xml" -DskipTests package
