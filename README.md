# ores-ror.infra

In-process GraalVM/TruffleRuby supervisor for the Rails-to-lambdas demo.

The runtime intentionally has **no child processes, no guest IPC, no FFI, and no guest-created threads**. The host process owns fixed executors and Graal Engines. Each warm Ruby isolate shares one cached `Source` and a host HTTP client, while every invocation gets a fresh `Context` and explicit request envelope.

## Isolate lifecycle

- maximum concurrent requests per isolate: **5**
- maximum isolate age: **30 minutes**
- idle retirement: **5 minutes** with zero active requests
- old isolates stop accepting new requests, drain in-flight requests, then close
- replacement isolates are created before traffic is shifted when possible
- a host pool thread is reusable across arbitrarily many requests; request state never lives in `ThreadLocal`/thread identity

## Graal Show compatibility

The guest artifact follows `graal-show/gs-compiler` / `graal-show/gs-lambdas` conventions:

- language: `ruby`
- ABI: `json-string-v1`
- support API: `gs-support-v1`
- `SandboxPolicy.UNTRUSTED`
- spawned isolate Engine
- no host class access, raw I/O, native access, process creation, environment access, or guest thread creation
- capability-scoped HTTP only

The runtime is intentionally in-process instead of the line-oriented stdin/stdout cell transport because this demo's isolation contract forbids runtime IPC/child processes.

## HTTP database path

The Ruby handler never receives a database socket. It calls `gs_http` against the configured HTTP Data API prefix. The supervisor owns one Java `HttpClient` per warm isolate, allowing the JDK client to reuse pooled HTTP/1.1 or HTTP/2 connections.
