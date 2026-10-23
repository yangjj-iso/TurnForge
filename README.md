# TurnForge

[![Verify](https://github.com/yangjj-iso/TurnForge/actions/workflows/ci.yml/badge.svg)](https://github.com/yangjj-iso/TurnForge/actions/workflows/ci.yml)

A Java 17 Agent Runtime with durable sessions, a bounded model/tool harness, external function handoff, and replayable SSE.

Run it as an HTTP service or embed its framework-independent core. Bring an OpenAI-compatible model, application tools, MCP servers, or a CubeSandbox backend. The default demo runs offline.

| Capability | Behavior |
| --- | --- |
| Execution | Asynchronous turns; configurable step, worker, queue and execution-time budgets |
| Tools | Spring `Tool` beans, MCP stdio/HTTP, optional sandbox tools; validation and controlled retry |
| Handoff | Durable `requires_action`, resume by call ID, duplicate result deduplication |
| Context | Prompt budget, complete tool exchanges, pluggable estimator and compressor |
| Recovery | Waiting actions survive restarts; uncertain running effects fail for reconciliation |
| Observation | Persisted items and model/tool lifecycle events; JSON/SSE replay by cursor |

## Start

Requires Java 17+. Maven is downloaded by the wrapper on first use.

```sh
./mvnw verify
java -jar target/turnforge.jar
```

In another terminal (Python 3.9+):

```sh
python3 examples/chat.py --once "Hello"
# Demo response: Hello
python3 examples/chat.py
```

The service binds to `127.0.0.1:8080`, exposes `/healthz`, and saves sessions in `.redy-data/`. Continue an existing session with `--session sess_...`. On Windows, use `mvnw.cmd`.

Optional Docker packaging, after building the JAR:

```sh
docker compose up --build -d
```

The Compose example publishes only to loopback and mounts a named data volume. It runs one service process. Docker image execution is an additional deployment check; the automated acceptance gate below runs the actual JAR directly.

## Use a real model and MCP

```sh
export REDY_MODEL_BASE_URL=https://your-provider.example/v1
export REDY_MODEL_API_KEY=your-key
java -jar target/turnforge.jar
```

Then select a model ID allowed by your provider:

```sh
python3 examples/chat.py --model YOUR_MODEL --once "Explain durable Agent execution."
```

The adapter appends `/chat/completions`. Keep credentials outside the repository. `.env.example` is a shell configuration example; Spring does not read `.env` automatically.

The repository includes a dependency-free MCP echo server. Enable it with a local Spring configuration file:

```yaml
# /absolute/path/application-mcp.yaml
redy:
  mcp:
    servers:
      - name: demo
        transport: stdio
        command: /absolute/path/to/python3
        args: [/absolute/path/to/TurnForge/examples/mcp_echo.py]
```

```sh
java -jar target/turnforge.jar --spring.config.additional-location=file:/absolute/path/application-mcp.yaml
python3 examples/chat.py --model YOUR_MODEL --once "Use mcp__demo__echo to echo hello."
```

See [provider settings](docs/providers.md) for HTTP MCP, TokenHub/Kimi, and CubeSandbox integration. A live provider requires your credentials; automated checks use local protocol fixtures.

## HTTP API

```sh
curl -sS http://127.0.0.1:8080/v1/agents/sessions \
  -H 'Content-Type: application/json' \
  -d '{"agent":{"name":"example","model":"demo"},"input":"Hello"}'
```

Use the returned session ID to inspect execution or follow its events:

```sh
curl -sS http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/turns
curl -sS http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/items
curl -N -H 'Accept: text/event-stream' -H 'Last-Event-ID: 3' \
  http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/events
```

`POST /sessions/{id}/turns` starts a turn; `POST /sessions/{id}/events` accepts user messages, tool results and cancellation. An active-turn message is steering input for the next model boundary. The [OpenAPI contract](api/openapi.yaml) documents request bodies and response envelopes. All paths are under `/v1/agents`.

`idle` describes a session, not the outcome of its last turn. Inspect turn status (`completed`, `failed`, `cancelled`, or a nonterminal state). `GET /turns` and `/items` return all records; pagination is not implemented. Event sequence numbers are per session; `?after=N` takes precedence over `Last-Event-ID`. Consumers should deduplicate by `event_id`. Output-text deltas follow the supported Agents API-style envelope, but the model adapter itself is nonstreaming and emits text after a complete provider response.

## Register application tools

Add a `Tool` bean; its bean name is its model-visible tool name. The default harness merges application, sandbox and MCP tools and rejects duplicate names at startup. Define metadata with `Tool.definition`, validate inputs/results, and explicitly opt into idempotent retry. A custom `Runner` owns its own catalog.

Application-owned functions implement `ExternalFunctionTool`. They pause execution rather than run on a worker. The opt-in `example-tools` profile registers an `approval` function as a working handoff example:

```sh
java -jar target/turnforge.jar --spring.profiles.active=example-tools
```

With a tool-capable remote model, ask for approval before an action. Inspect the session's `required_actions`, perform the application action once, and submit its result:

```sh
curl -sS http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/events \
  -H 'Content-Type: application/json' \
  -d '{"events":[{"type":"agent.session.input.tool_result","turn_id":"TURN_ID","call_id":"CALL_ID","success":true,"output":"approved"}]}'
```

A repeated identical result is accepted; a changed result for the same call returns `409`. Deduplication of result submission does not make an external side effect exactly-once: application tools must use the session/turn/call identity for their own idempotency. See the [architecture and recovery contract](docs/architecture.md).

## Configure execution

The Java package and `redy.*` property prefix remain stable for existing integrations. The executable artifact is now `turnforge.jar`.

| Spring property | Default | Meaning |
| --- | --- | --- |
| `redy.data-dir` / `REDY_DATA_DIR` | `.redy-data` | Single-process session snapshot directory |
| `redy.runtime.max-steps` | `8` | Model decisions per turn, retained across handoff |
| `redy.runtime.max-concurrent-turns` | `16` | Maximum active execution workers |
| `redy.runtime.max-queued-turns` | `64` | Bounded pending work queue; `0` disables queuing |
| `redy.runtime.turn-timeout` | `5m` | Cooperative deadline per active execution segment |
| `redy.runtime.max-subscribers` | `64` | Maximum concurrent event subscribers |
| `redy.sse.heartbeat-seconds` | `15` | SSE heartbeat interval |
| `redy.model.request-timeout` | `60s` | Timeout of each remote model request |

Use `--redy.runtime.turn-timeout=30s` or the environment spelling in `.env.example`. Waiting for an external result consumes no worker and no execution deadline; continuation receives a fresh deadline while retaining the step count. Queue waiting is outside the execution deadline. Cancellation/deadlines interrupt the worker and require adapters to honor interruption; they cannot undo a remote side effect. Capacity exhaustion returns `503`; a rejected turn is durably `failed` without dispatching its model/tools. Worker-capacity errors include `error.session_id` and `error.turn_id`, including creation with initial input. Inspect these resources before resubmitting. A resumed result is already recorded when worker rejection occurs.

Per-session context settings are `context_window_tokens` (default 4096) and `max_output_tokens` (512). Prompt budget is window minus output minus a 128-unit reserve, including instructions and tool schemas. Estimation counts UTF-8 bytes, not exact model tokens. Compaction preserves tool-call/result groups across steering; the default extractive summary is a fallback. Inject a `ContextWindow`, tokenizer and compressor through a custom `LoopRunner` for semantic compression.

## Verify delivery

```sh
./mvnw verify
python3 scripts/smoke.py --output-dir target/acceptance
```

The gate verifies the packaged application, HTTP model adapter, actual MCP stdio transport, tool result persistence, JSON/SSE replay, cancellation, deadlines, external handoff across restart, result deduplication, and crash recovery without replaying uncertain effects. It creates an isolated data directory, starts/stops its own processes and requires no API keys. `target/acceptance/result.json` and `server.log` contain the result and startup logs. Use a fresh output directory on each run. CI runs the same gate on Java 17 and 21 and uploads the JAR plus verification evidence.

[Architecture](docs/architecture.md) · [Provider integrations](docs/providers.md) · [Acceptance record](docs/verification.md) · [Contributing](CONTRIBUTING.md)

## Operating scope

This release targets a single trusted service process. Session transitions share a process lock and snapshots contain the complete history. Use one process per data directory; replicas, distributed leases, authentication, tenant isolation, history retention and pagination require additional implementation. Place access control in front of any nonlocal deployment. Stdio MCP executes on the service host. Live CubeSandbox and real-provider access need deployment-specific validation. The core supports text and one function call per model response; parallel model calls, subagents, semantic memory and provider-native streaming are not implemented.

MIT licensed. Maven wrapper scripts retain their Apache-2.0 license; see [third-party notices](THIRD_PARTY_NOTICES.md).
