# TruffleRuby runtime targets

`ores-ror.rb` supplies the application/controller/model/view/middleware contract; this repository owns the deployment/runtime adapters.

| Target | Boots Rails? | Unit of warmth | Concurrency model |
| --- | --- | --- | --- |
| Rails/Puma | yes | Rails process | normal Rails server concurrency |
| Graal worker cluster | no | route or route-group `GraalWorker` | one long-lived TruffleRuby Context pinned to one owner thread; up to 5 admitted in-flight requests |
| AWS Lambda | no | Lambda execution environment | standard Lambda execution-environment concurrency |

## Graal worker cluster

The supervisor owns one process-wide shared Graal `Engine`. Each route or route-group isolate is represented by one long-lived `GraalWorker`, which owns exactly one TruffleRuby `Context` and exactly one dedicated guest-owner thread. Requests carry explicit state and are never identified by host-thread identity. The configured value up to 5 is an admission/backpressure limit for in-flight requests; it is not a pool of threads entering the same Context.

Generated Ruby source is read from the app's `generated/graal` manifest and evaluated into the Context. TruffleRuby 34 requires native access for core POSIX-backed startup and the standard Ruby `require` path, so the Context uses `allowNativeAccess(true)`. Polyglot host-file IO, raw host sockets, guest-created Polyglot threads, environment access, host class lookup/loading, and cross-language access remain restricted. Ruby-native filesystem/process syscalls are not treated as an in-process tenant boundary; production must enforce those with the surrounding OS/container sandbox. The application network capability is the narrow host-provided `ores_gs_http` bridge backed by Java `HttpClient`; that bridge is restricted to the configured http(s) origin/base path and rejects origin/path escapes. Tokens require HTTPS except for loopback development URLs.

Diagnostics such as Context IDs, isolate keys, and owner-thread names are disabled by default and require `EXPOSE_GRAAL_DIAGNOSTICS=true`. Production still layers the process inside an OS/container sandbox; stronger native-memory isolation belongs to the separately gated Graal Native Isolate target.

This is TruffleRuby Polyglot Context isolation. Ruby Polyglot Native Isolates remain fail-closed until the required upstream isolate artifact exists and is separately validated.
