#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_ROOT="${APP_ROOT:-$(cd "$ROOT/../ores-ror.rb" && pwd)}"
AWS_REGION="${AWS_REGION:-us-east-1}"
FUNCTION_NAME="${FUNCTION_NAME:-ores-ror-truffleruby}"
ECR_REPOSITORY="${ECR_REPOSITORY:-ores-ror-truffleruby}"
IMAGE_TAG="${IMAGE_TAG:-$(git -C "$APP_ROOT" rev-parse --short=12 HEAD)}"
LAMBDA_ARCH="${LAMBDA_ARCH:-x86_64}"
LAMBDA_MEMORY_MB="${LAMBDA_MEMORY_MB:-1024}"
LAMBDA_TIMEOUT_SECONDS="${LAMBDA_TIMEOUT_SECONDS:-30}"
: "${LAMBDA_ROLE_ARN:?set LAMBDA_ROLE_ARN to an IAM execution role ARN}"

for tool in aws docker git; do
  command -v "$tool" >/dev/null 2>&1 || { echo "missing prerequisite: $tool" >&2; exit 1; }
done

case "$LAMBDA_ARCH" in
  x86_64) DOCKER_PLATFORM=linux/amd64 ;;
  arm64) DOCKER_PLATFORM=linux/arm64 ;;
  *) echo "LAMBDA_ARCH must be x86_64 or arm64" >&2; exit 1 ;;
esac

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
REGISTRY="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
REPOSITORY_URI="${REGISTRY}/${ECR_REPOSITORY}"

if ! aws ecr describe-repositories --region "$AWS_REGION" --repository-names "$ECR_REPOSITORY" >/dev/null 2>&1; then
  aws ecr create-repository \
    --region "$AWS_REGION" \
    --repository-name "$ECR_REPOSITORY" \
    --image-scanning-configuration scanOnPush=true >/dev/null
fi

aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin "$REGISTRY"

docker buildx build \
  --platform "$DOCKER_PLATFORM" \
  --provenance=false \
  --build-context ores_infra="$ROOT" \
  -f "$ROOT/aws-lambda/Dockerfile" \
  -t "${REPOSITORY_URI}:${IMAGE_TAG}" \
  --push \
  "$APP_ROOT"

DIGEST="$(aws ecr describe-images \
  --region "$AWS_REGION" \
  --repository-name "$ECR_REPOSITORY" \
  --image-ids "imageTag=${IMAGE_TAG}" \
  --query 'imageDetails[0].imageDigest' \
  --output text)"

if [[ -z "$DIGEST" || "$DIGEST" == "None" ]]; then
  echo "could not resolve pushed ECR digest" >&2
  exit 1
fi

IMAGE_URI="${REPOSITORY_URI}@${DIGEST}"

if aws lambda get-function --region "$AWS_REGION" --function-name "$FUNCTION_NAME" >/dev/null 2>&1; then
  aws lambda update-function-code \
    --region "$AWS_REGION" \
    --function-name "$FUNCTION_NAME" \
    --image-uri "$IMAGE_URI" \
    --publish >/dev/null
  aws lambda wait function-updated --region "$AWS_REGION" --function-name "$FUNCTION_NAME"
else
  aws lambda create-function \
    --region "$AWS_REGION" \
    --function-name "$FUNCTION_NAME" \
    --package-type Image \
    --code "ImageUri=${IMAGE_URI}" \
    --role "$LAMBDA_ROLE_ARN" \
    --architectures "$LAMBDA_ARCH" \
    --memory-size "$LAMBDA_MEMORY_MB" \
    --timeout "$LAMBDA_TIMEOUT_SECONDS" >/dev/null
  aws lambda wait function-active-v2 --region "$AWS_REGION" --function-name "$FUNCTION_NAME"
fi

printf 'deployed %s\nimage: %s\nregion: %s\n' "$FUNCTION_NAME" "$IMAGE_URI" "$AWS_REGION"
