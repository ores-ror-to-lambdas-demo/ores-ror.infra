# ores-ror.infra

Deployment/runtime infrastructure for `ores-ror-to-lambdas-demo/ores-ror.rb`.

## Repository boundary

The application repository is the only authority for application semantics:

- `config/routes.rb` — route + per-route middleware authority;
- `app/controllers/**/handler.rb` — generated, gitignored controller-adjacent Rails-free sidecars;
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

The compiler reads `ores-ror.infra/graal/bootstrap.rb` and appends it to each generated route/group unit. Controller/model/view/route/middleware semantics still come from the app repo. There is no tracked parallel `routes/**/handler.rb` tree and no `# ores-route` annotation layer; `config/routes.rb` is the sole route authority.

## AWS Lambda

The Dockerfile lives here and uses the app checkout only as a build/codegen input plus this repo as a named BuildKit context. The final runtime image contains generated Lambda artifacts and infra runtime adapters, not the Rails source tree or Rails dependency graph. See `aws-lambda/README.md`.

## Invariant

Moving hosting adapters here must not change routing or middleware behavior. Cross-repo CI checks all 15 application routes as JSON and HTML and preserves the exact Rails-vs-Graal contract diff.


## GraalWorker topology

The Java supervisor uses one process-wide shared Graal `Engine` for code/JIT caching and one long-lived `GraalWorker` per generated route or route-group isolate.

Each `GraalWorker` owns:

- exactly one long-lived TruffleRuby `Context`;
- the cached generated common/unit sources for its route or group;
- a bounded host executor of up to 5 reusable worker threads entering that same Context;
- one fair per-isolate admission bound equal to the configured Context thread-pool size;
- explicit per-request envelopes; thread identity is diagnostic only.

Embedded TruffleRuby normally enables single-threaded mode, so the supervisor explicitly sets `ruby.single-threaded=false`. `CONTEXT_THREAD_POOL_SIZE` is the primary setting and is bounded to 1–5, default 5; older admission/concurrency names remain compatibility aliases. A request occupies one executor worker while it enters guest Ruby, but request identity is not thread identity. Invocation boundaries snapshot and restore Ruby fiber/thread-local state before worker reuse. A hard timeout intentionally replaces the affected Context/isolate, so other requests currently executing in that Context may be cancelled too; graceful max-age/idle retirement creates replacement capacity before draining the old isolate. Graal execution remains Rails-free: no `Rails.application`, Action Controller, or Action View is loaded in the guest runtime.


### Native-access boundary

The portable guest runtime is deliberately pure Ruby: generated Graal units do not depend on `json/ext` or other C extensions. Each `GraalWorker` starts its Context with host/native access denied, `ruby.platform-native=false`, `ruby.cexts=false`, host file/socket IO denied, process/thread creation denied, environment access denied, host-class lookup/loading denied, and cross-language access denied.

That is an in-process capability boundary, not a substitute for tenant OS isolation. Production still layers the worker inside an OS/container sandbox, and the separate Ruby Polyglot Native Isolate target remains fail-closed until the upstream Ruby isolate artifact exists and is independently validated.

Runtime diagnostic headers are disabled by default and may be enabled only with `EXPOSE_GRAAL_DIAGNOSTICS=true` for trusted debugging.
