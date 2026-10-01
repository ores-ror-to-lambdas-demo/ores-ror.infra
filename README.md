# ores-ror.infra

In-process GraalVM/TruffleRuby supervisor for the Rails-to-lambdas demo.

The runtime intentionally has **no child processes, no guest IPC, no FFI, and no guest-created threads**. The host owns all concurrency. Each warm isolate/cell owns exactly one long-lived Graal `Engine`, one long-lived TruffleRuby `Context`, and one bounded host executor. The default maximum is five concurrent host threads entering that same Ruby context.

Requests carry explicit request envelopes. A request is never identified by `Thread.current`, a Java thread ID, or worker identity.

## Concurrency model

```text
cell / isolate
├── one Graal Engine
├── one TruffleRuby Context
├── one loaded Rails/Rack invoker
└── host-owned executor
    ├── worker 1
    ├── worker 2
    ├── worker 3
    ├── worker 4
    └── worker 5
```

The five threads are reusable. Request A may run on worker 2 and a later request B may run on worker 2; request identity and request data remain in the explicit invocation envelope rather than host-thread identity.

Guest-created threads remain disabled. TruffleRuby 34.x supports concurrent host-thread access to an embedded Ruby context, so parallelism is supplied by the supervisor rather than by allowing arbitrary guest thread creation.

## Isolate lifecycle

- maximum concurrent requests per isolate: **5**
- maximum isolate age: **30 minutes**
- idle retirement: **5 minutes** with zero active requests
- a retiring isolate immediately stops accepting new work
- the cluster creates/selects replacement isolates for new requests
- queued and active work on the retiring isolate drains before its shared Context is closed
- a hard Context cancellation is isolate-scoped; callers should treat all in-flight work on that isolate as affected and retry according to request policy
- ordinary request deadlines/cancellation should therefore remain request-scoped and cooperative until an isolate-wide kill is required

## Thread-local compatibility

Thread-local state is not used by the supervisor for request identity. Ruby code and third-party gems may still use `Thread.current` exactly as they do under a normal reusable server thread pool, but such state must obey request lifecycle cleanup if it is request-specific.

The test suite deliberately sends hundreds of unique request IDs through one Context and a five-thread executor, verifies every response retains its own request ID, verifies the Context identity remains stable, and verifies multiple reusable host threads entered that one Context.

## Graal Show compatibility

The guest artifact follows `graal-show/gs-compiler` / `graal-show/gs-lambdas` conventions:

- language: `ruby`
- ABI: `json-string-v1`
- support API: `gs-support-v1`
- `SandboxPolicy.UNTRUSTED`
- one long-lived Context per isolate/cell
- no host class access, raw I/O, native access, process creation, environment access, or guest thread creation
- capability-scoped HTTP only

The runtime is intentionally in-process instead of the line-oriented stdin/stdout cell transport because this demo's isolation contract forbids runtime IPC/child processes.

## HTTP database path

The Ruby handler never receives a database socket. It calls `gs_http` against the configured HTTP Data API prefix. The supervisor owns one Java `HttpClient`, allowing the JDK client to reuse pooled HTTP/1.1 or HTTP/2 connections across isolates and invocations.
