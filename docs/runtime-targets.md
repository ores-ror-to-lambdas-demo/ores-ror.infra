# TruffleRuby runtime targets

`ores-ror.rb` remains a normal Rails application while deployable Ruby handlers can also run through the Graal supervisor.

| Target | Unit of warmth | Concurrency model |
| --- | --- | --- |
| Rails/Puma | Rails process | bounded reusable Rails/Puma worker threads |
| AWS Lambda | Lambda execution environment | provider-managed invocation concurrency |
| Graal worker cluster | isolate/cell | one long-lived TruffleRuby Context with up to 5 reusable host threads |

## Graal isolate contract

A Graal cell is the isolation and lifecycle unit. It owns one `Engine`, one `Context`, one loaded Ruby invoker, and one bounded executor. The Context is not recreated per invocation and there is not one Context per worker thread.

Request identity is explicit data. Physical thread identity is diagnostic only.

When a cell reaches its age/idle policy or becomes unhealthy, it stops taking new requests. Replacement cells accept new traffic while the old cell drains. After its active and queued work reaches zero, the Context is closed. If a Context must be force-cancelled, cancellation is intentionally cell-wide rather than pretending it is safe to kill only one request inside a shared Context.

The cluster is a TruffleRuby/GraalVM JVM isolation model, not Oracle Polyglot Native Isolates. The existing capability check stays fail-closed until a supported Ruby native-isolate artifact exists.
