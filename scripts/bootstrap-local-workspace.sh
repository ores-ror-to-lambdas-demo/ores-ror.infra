#!/usr/bin/env bash
set -euo pipefail

ROOT="${1:-$PWD/ores-ror-local}"
mkdir -p "$ROOT"
cd "$ROOT"

for tool in gh git cargo ruby truffleruby mvn java curl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "missing prerequisite: $tool" >&2; exit 1; }
done

gh auth status >/dev/null 2>&1 || {
  echo 'GitHub CLI must be authenticated because ORESoftware/ores-compose is private.' >&2
  echo 'Run: gh auth login' >&2
  exit 1
}

clone_or_update() {
  local repo="$1" dir="$2"
  if [[ -d "$dir/.git" ]]; then
    git -C "$dir" fetch origin main
    git -C "$dir" checkout main
    git -C "$dir" pull --ff-only origin main
  else
    gh repo clone "$repo" "$dir"
  fi
}

clone_or_update ores-ror-to-lambdas-demo/ores-ror.rb ores-ror.rb
clone_or_update ores-ror-to-lambdas-demo/ores-ror.infra ores-ror.infra
clone_or_update ORESoftware/ores-compose ores-compose

cargo build --release --manifest-path ores-compose/Cargo.toml --bin ores-compose
ORES_COMPOSE="$ROOT/ores-compose/target/release/ores-compose"

cat > .ores-compose.yaml <<'YAML'
schema_version: ores.compose.v1
project: ores-ror-local
allow_lazy_start: false
services:
  data-api:
    runtime: host
    working_dir: ores-ror.infra
    command: ["ruby", "scripts/mock-data-api.rb"]
    healthcheck:
      command: ["curl", "--fail", "--silent", "http://127.0.0.1:8787/healthz"]
      interval_ms: 250
      timeout_ms: 1000
      retries: 20

  rails:
    runtime: host
    working_dir: ores-ror.rb
    depends_on: [data-api]
    build:
      - ["bash", "bin/local-install-mri.sh"]
    command: ["bash", "bin/local-run-mri.sh"]
    healthcheck:
      command: ["curl", "--fail", "--silent", "http://127.0.0.1:3100/healthz"]
      interval_ms: 250
      timeout_ms: 1500
      retries: 20

  graal:
    runtime: host
    working_dir: ores-ror.infra
    depends_on: [data-api]
    build:
      - ["bash", "scripts/local-build-graal.sh"]
    command: ["bash", "scripts/local-run-graal.sh"]
    healthcheck:
      command: ["curl", "--fail", "--silent", "http://127.0.0.1:3200/healthz"]
      interval_ms: 500
      timeout_ms: 2000
      retries: 20
YAML

"$ORES_COMPOSE" check .ores-compose.yaml
"$ORES_COMPOSE" plan .ores-compose.yaml

cat <<'MSG'
Starting the local stack in the foreground:
  data API: http://127.0.0.1:8787
  Rails MRI: http://127.0.0.1:3100
  Graal cluster: http://127.0.0.1:3200

Try after startup:
  curl http://127.0.0.1:3100/healthz
  curl http://127.0.0.1:3200/healthz
  curl http://127.0.0.1:3100/users/demo
  curl http://127.0.0.1:3200/users/demo

Ctrl-C stops the foreground supervisor. From another terminal:
  ./ores-compose/target/release/ores-compose down .ores-compose.yaml
MSG

exec "$ORES_COMPOSE" up --skip-zed-pkg --skip-stack-sync .ores-compose.yaml
