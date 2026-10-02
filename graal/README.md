# Graal runtime profile

This directory is owned by `ores-ror.infra`. The application repository supplies the route/controller/model/view/middleware contract and generates the guest artifacts; infra supplies the Graal runtime adapter and deployment profile.

The runtime is Rails-free and does not initialize `Rails.application`.

- one host OS process may own one shared Graal engine;
- every concrete route gets exactly one long-lived Ruby context/isolate;
- every route group gets exactly one long-lived Ruby context/isolate;
- all contexts share one bounded process-wide host execution pool;
- `GRAAL_THREAD_POOL_SIZE` controls total host threads independently of worker count;
- each worker/context separately enforces `ISOLATE_MAX_CONCURRENCY`;
- embedded TruffleRuby explicitly uses `ruby.single-threaded=false`, while guest-created threads remain disabled;
- a `GraalWorker` is a runtime/isolation cell, not a host thread; shared host threads have no permanent worker affinity;
- mutable Ruby state is context-local while compiled/source material may be shared read-only by the engine;
- guest-created threads, filesystem access, child processes, raw sockets, and native FFI remain disabled;
- data access uses the host HTTP capability.

Application authority remains in `ores-ror.rb`; `config/routes.rb` is the only route authority and there is no `# ores-route` annotation layer:

- `config/routes.rb` — Rails route and per-route middleware authority;
- `app/controllers/**/handler.rb` — generated, gitignored controller-adjacent sidecars derived from Rails routes/controllers;
- `app/**` — controllers, models, and views;
- `lib/ores_build/static_routes.rb` — Rails-free compiler.

From a sibling checkout:

```sh
cd ../ores-ror.rb
ORES_INFRA_ROOT=../ores-ror.infra ORES_BUILD_TARGET=graal truffleruby bin/build-runtime
ORES_BUILD_TARGET=lambda ruby bin/build-runtime
```

Generated artifacts stay in the application checkout under `generated/`; this repo only owns hosting/runtime assets.


## Worker placement policy

Worker creation is configurable independently from handler generation.

By default:

```text
GRAAL_WORKER_PLACEMENT=route
GRAAL_WORKER_STARTUP=lazy
```

so each generated route unit gets its own `GraalWorker` only when traffic first reaches it.

For explicit mixed placement, set:

```sh
GRAAL_WORKER_PLACEMENT_FILE=/path/to/worker-placement.json
```

The file may select any compatible generated isolate-unit key for a route. The supervisor verifies that the selected unit exists and declares that route in its `route_ids` list before creating a worker. This prevents an accidental configuration from silently moving a route into an unrelated context.

Today the compiler emits route and group units. A future compiler may additionally emit trust-domain units such as `domain:public`, `domain:private`, and `domain:admin`; the same placement API is designed to accept those once they exist.
