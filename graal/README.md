# Graal runtime profile

This directory is owned by `ores-ror.infra`. The application repository supplies the route/controller/model/view/middleware contract and generates the guest artifacts; infra supplies the Graal runtime adapter and deployment profile.

The runtime is Rails-free and does not initialize `Rails.application`.

- one host OS process may own one shared Graal engine;
- every concrete route gets exactly one long-lived Ruby context/isolate;
- every route group gets exactly one long-lived Ruby context/isolate;
- each context multiplexes requests over at most 5 host threads;
- a worker is a host execution thread entering a context, not another OS process or another context;
- mutable Ruby state is context-local while compiled/source material may be shared read-only by the engine;
- guest-created threads, filesystem access, child processes, raw sockets, and native FFI remain disabled;
- data access uses the host HTTP capability.

Application authority remains in `ores-ror.rb`:

- `config/routes.rb` — Rails route and per-route middleware authority;
- `routes/**/handler.rb` — committed execution-adapter authority;
- `app/**` — controllers, models, and views;
- `lib/ores_build/static_routes.rb` — Rails-free compiler.

From a sibling checkout:

```sh
cd ../ores-ror.rb
ORES_INFRA_ROOT=../ores-ror.infra ORES_BUILD_TARGET=graal ruby bin/build-runtime
ORES_BUILD_TARGET=lambda ruby bin/build-runtime
```

Generated artifacts stay in the application checkout under `generated/`; this repo only owns hosting/runtime assets.
