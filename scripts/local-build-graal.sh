#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
APP_ROOT="$(cd ../ores-ror.rb && pwd)"
command -v mvn >/dev/null 2>&1 || { echo 'mvn is required' >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo 'Java 21+ is required' >&2; exit 1; }
"$APP_ROOT/bin/local-install-truffleruby.sh"
mvn -DskipTests package
