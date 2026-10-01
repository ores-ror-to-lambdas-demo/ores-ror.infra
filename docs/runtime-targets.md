# TruffleRuby runtime targets

`ores-ror.rb` is one Rails application with one Rack boundary and two TruffleRuby deployment targets.

| Target | TruffleRuby mode | Unit of warmth | Concurrency model |
| --- | --- | --- | --- |
| AWS Lambda | standalone/native | Lambda execution environment | Standard Lambda: one invocation at a time per environment; AWS scales environments |
| Graal worker cluster | JVM/polyglot embedding | supervisor cell | up to 5 host workers per cell, each with its own Rails/TruffleRuby context |

Both targets call `Rails.application`. `lib/ores_runtime/rack_dispatch.rb` owns the common request-to-Rack and Rack-to-response boundary. `aws-lambda/handler.rb` only translates Lambda event envelopes. `graal/bootstrap.rb` only adapts the supervisor JSON envelope.

The current Graal cluster is intentionally described as a **TruffleRuby/GraalVM JVM worker cluster**, not a Polyglot Native Isolate cluster. Ruby still lacks the published `ruby-isolate` artifact required for `Engine.spawnIsolate(true)`. The supervisor keeps the native-isolate capability check fail-closed so the name cannot silently overstate the isolation primitive.
