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
- one fair per-isolate admission semaphore;
- explicit per-request envelopes; thread identity is diagnostic only.

The `Cluster` owns one bounded process-wide host execution pool shared by every `GraalWorker`. `GRAAL_THREAD_POOL_SIZE` defaults to 32 and controls total host execution threads for the process. `ISOLATE_MAX_CONCURRENCY` defaults to 5 and independently caps admitted work per worker/context; older Context concurrency names remain compatibility aliases. Embedded TruffleRuby explicitly sets `ruby.single-threaded=false`, allowing different shared pool threads to enter a Context while guest-created Ruby threads remain disabled. A host thread has no permanent worker affinity and may execute in public/private/admin or dedicated workers sequentially. Invocation boundaries snapshot and restore Ruby fiber/thread-local state before worker reuse. A hard timeout intentionally replaces the affected Context/isolate, so other requests currently executing in that Context may be cancelled too; graceful max-age/idle retirement creates replacement capacity before draining the old isolate. Graal execution remains Rails-free: no `Rails.application`, Action Controller, or Action View is loaded in the guest runtime.


### Native-access boundary

The portable guest runtime is deliberately pure Ruby: generated Graal units do not depend on `json/ext` or other C extensions. Each `GraalWorker` starts its Context with host/native access denied, `ruby.platform-native=false`, `ruby.cexts=false`, host file/socket IO denied, process/thread creation denied, environment access denied, host-class lookup/loading denied, and cross-language access denied.

That is an in-process capability boundary, not a substitute for tenant OS isolation. Production still layers the worker inside an OS/container sandbox, and the separate Ruby Polyglot Native Isolate target remains fail-closed until the upstream Ruby isolate artifact exists and is independently validated.

Runtime diagnostic headers are disabled by default and may be enabled only with `EXPOSE_GRAAL_DIAGNOSTICS=true` for trusted debugging.


## Configurable GraalWorker placement

`GraalWorker` is a runtime/isolation cell, not inherently a 1:1 synonym for a Lambda handler. The safe default remains one worker per generated route unit.

End users can override placement with `GRAAL_WORKER_PLACEMENT_FILE`. The file uses the `ores-graal-worker-placement/v1` contract documented by `graal/worker-placement.schema.json`.

Example:

```json
{
  "schema": "ores-graal-worker-placement/v1",
  "default_strategy": "route",
  "startup": "lazy",
  "route_identity_assignments": {
    "GET /orders/:id": "group:orders",
    "GET /orders/:id/receipt": "group:orders",
    "POST /orders/:id/cancel": "group:orders"
  }
}
```

This means:

- unmatched routes keep a dedicated route worker;
- the selected order routes share the already-generated `group:orders` worker/context;
- `lazy` creates a worker on first use; `eager` prewarms every distinct selected unit;
- configuration fails closed if a route selects a generated unit that does not actually contain that route.

The placement contract intentionally references generated unit keys rather than hard-coding route/group behavior into `GraalWorker`. That leaves room for future generated units such as `domain:public`, `domain:private`, and `domain:admin` without changing the worker lifecycle API.
