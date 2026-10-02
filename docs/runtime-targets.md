# TruffleRuby runtime targets

`ores-ror.rb` supplies the application/controller/model/view/middleware contract; this repository owns the deployment/runtime adapters.

| Target | Boots Rails? | Unit of warmth | Concurrency model |
| --- | --- | --- | --- |
| Rails/Puma | yes | Rails process | normal Rails server concurrency |
| Graal worker cluster | no | route or route-group `GraalWorker` | one long-lived TruffleRuby Context with up to 5 reusable host threads |
| AWS Lambda | no | Lambda execution environment | standard Lambda execution-environment concurrency |

## Graal worker cluster

The supervisor owns one process-wide shared Graal `Engine`. Each route or route-group isolate is represented by one long-lived `GraalWorker`, which owns exactly one TruffleRuby `Context` plus a bounded host thread pool. Requests carry explicit state and are never identified by host-thread identity.

Generated Ruby source is read from the app's `generated/graal` manifest and evaluated into the Context. Guest filesystem, raw sockets, native/FFI access, process creation, guest-created threads, environment access, host class loading, and cross-language access remain disabled. The application network capability is the host-provided `ores_gs_http` bridge backed by Java `HttpClient`.

This is TruffleRuby Polyglot Context isolation. Ruby Polyglot Native Isolates remain fail-closed until the required upstream isolate artifact exists and is separately validated.
