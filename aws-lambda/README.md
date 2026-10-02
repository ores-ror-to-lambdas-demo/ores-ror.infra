# TruffleRuby on AWS Lambda

This directory is owned by `ores-ror.infra`. The Docker build consumes the `ores-ror.rb` checkout as a code-generation input and these runtime adapters as a separate named BuildKit context. The final runtime stage copies only generated Lambda artifacts plus the infra runtime files and a minimal Rails-free Gemfile.

The Lambda target **does not boot Rails**. The application repo derives route/group artifacts from `config/routes.rb`, normal Rails controller/model/view locations, and shared portable middleware. Controller-adjacent `handler.rb` files are generated and gitignored; there is no tracked parallel route-handler tree.

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

The app checkout must not contain an `aws-lambda/` directory. `adapter.rb`, `runtime.rb`, `bootstrap`, the Dockerfile, the minimal runtime Gemfile, and deployment scripts live here. CI verifies the final image contains no Rails, Action Pack, or Action View gems and no Rails application source tree.

## Deploy

Prerequisites: authenticated AWS CLI v2, Docker Buildx, access to ECR/Lambda, and an IAM execution role.

```sh
export AWS_REGION=us-east-1
export LAMBDA_ROLE_ARN=arn:aws:iam::123456789012:role/ores-ror-lambda
./aws-lambda/deploy.sh
./aws-lambda/invoke.sh
```
