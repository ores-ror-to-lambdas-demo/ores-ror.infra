# AWS Lambda deployment target

The Lambda target packages the exact `ores-ror.rb` Rails tree into an AWS container image. It does not contain a second application implementation.

## Runtime split

- **AWS Lambda:** TruffleRuby standalone/native container. Rails boots once per Lambda execution environment; the custom runtime polls Lambda's Runtime API and dispatches Function URL / API Gateway events through the shared Rack adapter.
- **Self-hosted Graal cluster:** TruffleRuby embedded on GraalVM/JVM. A supervisor owns warm cells and a bounded worker pool per cell, dispatching the same Rails Rack app.

The transport adapter differs; the Rails route/controller/service code does not.

## Deploy

Prerequisites: authenticated AWS CLI v2, Docker Buildx, access to ECR/Lambda, and an IAM execution role for the function.

```sh
export AWS_REGION=us-east-1
export LAMBDA_ROLE_ARN=arn:aws:iam::123456789012:role/ores-ror-lambda
./aws-lambda/deploy.sh
./aws-lambda/invoke.sh
```

Optional variables include `FUNCTION_NAME`, `ECR_REPOSITORY`, `IMAGE_TAG`, `LAMBDA_ARCH` (`x86_64` or `arm64`), `LAMBDA_MEMORY_MB`, and `LAMBDA_TIMEOUT_SECONDS`.

Deployment pushes a tag to ECR, resolves that tag to an immutable digest, and configures Lambda with the digest URI. No AWS credentials or role secrets are stored in Git.
