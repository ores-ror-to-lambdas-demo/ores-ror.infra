#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_ROOT="${APP_ROOT:-$(cd "$ROOT/../ores-ror.rb" && pwd)}"
AWS_REGION="${AWS_REGION:-us-east-1}"
FUNCTION_NAME="${FUNCTION_NAME:-ores-ror-truffleruby}"
EVENT_FILE="${1:-$APP_ROOT/aws-lambda/event-v2.json}"
OUTPUT_FILE="${OUTPUT_FILE:-/tmp/ores-ror-lambda-response.json}"

aws lambda invoke \
  --region "$AWS_REGION" \
  --function-name "$FUNCTION_NAME" \
  --cli-binary-format raw-in-base64-out \
  --payload "fileb://${EVENT_FILE}" \
  "$OUTPUT_FILE" >/dev/null

cat "$OUTPUT_FILE"
printf '\n'
