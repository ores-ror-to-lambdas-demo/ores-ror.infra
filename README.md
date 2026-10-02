# ores-ror.infra

Deployment/runtime infrastructure for `ores-ror-to-lambdas-demo/ores-ror.rb`.

## Repository boundary

The application repository is the only authority for application semantics:

- `config/routes.rb` — route + per-route middleware authority;
- `routes/**/handler.rb` — physical route adapters;
- `app/controllers`, `app/models`, `app/views` — MVC source;
- `lib/ores_app/**` — shared dispatcher/middleware/business runtime;
- `lib/ores_build/**` and `bin/build-runtime` — Rails-free compiler;
- `generated/**` — ephemeral route/group artifacts.

This infra repository owns how those generated artifacts are hosted:

- `graal/**` — Graal/TruffleRuby bootstrap and runtime profile;
- `aws-lambda/**` — Lambda Docker/runtime adapter/deployment assets;
- Java supervisor / deployment tooling.

There must be no second route table or middleware list here.

## Graal generation

With sibling checkouts:

```sh
cd ../ores-ror.rb
ORES_INFRA_ROOT=../ores-ror.infra ORES_BUILD_TARGET=graal truffleruby bin/build-runtime
```

The compiler reads `ores-ror.infra/graal/bootstrap.rb` and appends it to each generated route/group unit. All controller/model/view/route/middleware source still comes from the app repo.

## AWS Lambda

The Dockerfile lives here but uses the app checkout as its primary build context and this repo as a named BuildKit context. See `aws-lambda/README.md`.

## Invariant

Moving hosting adapters here must not change routing or middleware behavior. Cross-repo CI checks all 15 application routes as JSON and HTML and preserves the exact Rails-vs-Graal contract diff.
