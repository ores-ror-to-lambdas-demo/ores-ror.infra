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


## GraalWorker topology

The Java supervisor uses one process-wide shared Graal `Engine` for code/JIT caching and one long-lived `GraalWorker` per generated route or route-group isolate.

Each `GraalWorker` owns:

- exactly one long-lived TruffleRuby `Context`;
- the cached generated common/unit sources for its route or group;
- one dedicated guest-owner thread that creates, invokes, probes, and closes the Context;
- one fair per-isolate admission limit capped at 5 in-flight requests;
- explicit per-request envelopes; thread identity is diagnostic only.

Many HTTP requests may arrive concurrently, but each GraalWorker pins its TruffleRuby Context to one dedicated owner thread for its entire lifetime. The configured value up to 5 is the per-isolate admission limit, not the number of threads entering Ruby. This avoids Context thread migration and concurrent entry while retaining bounded backpressure at the isolate boundary. A request timeout hard-replaces only the affected isolate; max-age replacement creates replacement capacity before the old worker drains. Graal execution remains Rails-free: no `Rails.application`, Action Controller, or Action View is loaded in the guest runtime.


### Native-access boundary

TruffleRuby 34 currently requires Polyglot native access for core POSIX-backed startup and Ruby's standard `require` path. Therefore `GraalWorker` uses `allowNativeAccess(true)`. Polyglot host-file/socket access, host-class lookup/loading, guest-created Polyglot threads, environment access, and cross-language access remain restricted, but Ruby-native filesystem/process syscalls are **not** claimed as an in-process tenant boundary.

Production tenant isolation must pair the Graal restrictions with an OS/container filesystem/process sandbox. The separate Ruby Polyglot Native Isolate target remains fail-closed until the upstream Ruby isolate artifact exists and is independently validated.


Runtime diagnostic headers are disabled by default and may be enabled only with `EXPOSE_GRAAL_DIAGNOSTICS=true` for trusted debugging.
