# TruffleRuby on AWS Lambda

This directory is owned by `ores-ror.infra`. The image consumes the `ores-ror.rb` application checkout as its primary Docker build context and these runtime adapters as a separate named BuildKit context.

The Lambda target **does not boot Rails**. The application repo generates its route/group handlers from the same `config/routes.rb`, `routes/**`, `app/**`, and shared middleware contract used by normal Rails.

Build locally from the infra checkout:

```sh
APP_ROOT=../ores-ror.rb
docker buildx build \
  --platform linux/amd64 \
  --build-context ores_infra=. \
  -f aws-lambda/Dockerfile \
  -t ores-ror-truffleruby-lambda:test \
  "$APP_ROOT"
```

The app checkout must not contain an `aws-lambda/` directory. `adapter.rb`, `runtime.rb`, `bootstrap`, the Dockerfile, and deployment scripts live here.

## Deploy

Prerequisites: authenticated AWS CLI v2, Docker Buildx, access to ECR/Lambda, and an IAM execution role.

```sh
export AWS_REGION=us-east-1
export LAMBDA_ROLE_ARN=arn:aws:iam::123456789012:role/ores-ror-lambda
./aws-lambda/deploy.sh
./aws-lambda/invoke.sh
```
