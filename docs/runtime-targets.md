# TruffleRuby runtime targets

`ores-ror.rb` supplies the application/controller/model/view/middleware contract; this repository owns the deployment/runtime adapters.

| Target | Boots Rails? | Unit of warmth | Concurrency model |
| --- | --- | --- | --- |
| Rails/Puma | yes | Rails process | normal Rails server thread pool; app production defaults to 50–300 threads |
| Graal worker cluster | no | configurable `GraalWorker` runtime cell | one process-wide host execution pool shared across Contexts, plus per-worker admission bounds |
| AWS Lambda | no | Lambda execution environment | standard Lambda execution-environment concurrency |

## Graal worker cluster

The supervisor owns one process-wide shared Graal `Engine` and one process-wide host execution pool. Each selected runtime cell is represented by a long-lived `GraalWorker` owning exactly one TruffleRuby `Context` plus a per-isolate admission semaphore. Embedded TruffleRuby is explicitly configured with `ruby.single-threaded=false`. `GRAAL_THREAD_POOL_SIZE` defaults to 32 for total host execution capacity; `ISOLATE_MAX_CONCURRENCY` defaults to 5 for each Context. Host threads are reusable across different workers/Contexts and have no per-worker affinity. Requests carry explicit state and are never identified by host-thread identity. Guest-created `Thread.new` remains disabled.

Generated Ruby source is read from the app's `generated/graal` manifest and evaluated into the Context. The portable guest runtime is pure Ruby and generated units are CI-checked to contain no `require "json"` dependency. The Context denies host file IO, host sockets, native access, process creation, guest-created threads, environment access, host class lookup/loading, and cross-language access; it also starts TruffleRuby with `ruby.platform-native=false` and `ruby.cexts=false`.

The application network capability is the narrow host-provided `ores_gs_http` bridge backed by Java `HttpClient`. It is restricted to the configured http(s) origin/base path and rejects origin/path escapes. Tokens require HTTPS except for loopback development URLs.

A hard request timeout is Context-scoped: replacing/cancelling one isolate can terminate other in-flight requests in that same Context. Graceful age/idle retirement starts replacement capacity before the old Context drains.

Diagnostics such as Context IDs, isolate keys, and worker-thread names are disabled by default and require `EXPOSE_GRAAL_DIAGNOSTICS=true`. Production still layers the process inside an OS/container sandbox; stronger native-memory isolation belongs to the separately gated Graal Native Isolate target.

This is TruffleRuby Polyglot Context isolation. Ruby Polyglot Native Isolates remain fail-closed until the required upstream isolate artifact exists and is separately validated.
