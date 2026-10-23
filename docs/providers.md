# Model, MCP and sandbox integrations

## Remote model

Set these environment variables before starting the service to enable the Java Chat Completions adapter. For local development, keep a shell environment file outside the repository, for example `$HOME/.config/redy-agents-api/model.env`:

```sh
REDY_MODEL_BASE_URL=https://tokenhub.tencentmaas.com/plan/v3
REDY_MODEL_API_KEY=replace-with-your-own-key
REDY_MODEL_REQUEST_TIMEOUT=120s
```

Load that private file into the shell that starts Maven:

```sh
chmod 600 "$HOME/.config/redy-agents-api/model.env"
set -a
. "$HOME/.config/redy-agents-api/model.env"
set +a
./mvnw spring-boot:run
```

The URL above is the [Tencent TokenHub enterprise Guangzhou OpenAI-compatible base URL](https://cloud.tencent.com/document/product/1823/130659); the adapter appends `/chat/completions`. The local environment file is an example: Spring does not load it automatically, and it must contain your own key. Supply a model ID allowed by that key as `agent.model`; Tencent's [quickstart](https://cloud.tencent.com/document/product/1823/130660) explains that a key can call only its selected models. Keep credentials out of committed configuration. When `REDY_MODEL_BASE_URL` is absent, the bundled demo model remains the default; a custom Spring `Model` bean can also replace either default.

For a TokenHub plan/v3 key with access to `kimi-k3`, start a session with a larger budget than the demo defaults:

```sh
curl -sS -X POST http://127.0.0.1:8080/v1/agents/sessions \
  -H 'Content-Type: application/json' \
  -d '{"agent":{"name":"k3","model":"kimi-k3","context_window_tokens":65536,"max_output_tokens":16384},"input":"Explain this project architecture."}'
```

For `kimi-k3`, the adapter sends `max_completion_tokens` with the agent's `max_output_tokens`; other model IDs use `max_tokens`. K3 keeps reasoning enabled and its reasoning and final answer share that output allowance. The example reserves 16,384 output tokens and leaves an approximate 49,024-token prompt budget after the harness's 128-token margin. Tune both values for the task, provider limits, latency, and cost. The harness estimates tokens from UTF-8 bytes rather than K3's tokenizer, so these are approximate application budgets, not a claim about the provider's full context limit. See [Tencent's Kimi guide](https://cloud.tencent.com/document/product/1823/132232) for current K3 parameters.

The adapter sends the current instructions, conversation, tool definitions, tool calls, and tool results in one nonstreaming request per loop step. It saves returned `reasoning_content` and, when present, `reasoning_details` alongside each retained assistant message and includes those fields on later steps, including after tool calls. This supports the [multi-turn reasoning continuity described by Tencent](https://cloud.tencent.com/document/product/1823/132232) for the adapter's text/function subset; unknown provider-specific message fields are not replayed. Context compaction can still remove older messages, so continuity is limited to the retained model context. The reasoning fields are stored in local session snapshots; protect the session data directory. The adapter requests a single tool call at a time; a provider response with multiple simultaneous calls fails the turn rather than dropping calls. The HTTP request timeout defaults to 60 seconds; the example raises it to 120 seconds for slower reasoning requests. Because the provider response is nonstreaming, output deltas are emitted after each model response arrives.

## MCP tools

Configure `redy.mcp.servers` to add tools from trusted MCP servers to the default `LoopRunner`. Both local stdio processes and remote Streamable HTTP endpoints use the [official MCP Java SDK](https://java.sdk.modelcontextprotocol.io/latest/client/). For example, add these settings to a deployment's Spring `application.yaml`:

```yaml
redy:
  mcp:
    servers:
      - name: localfiles
        transport: stdio
        command: /absolute/path/to/trusted-mcp-server
        args: ["--read-only"]
        env:
          MCP_WORKSPACE: /srv/agent-readonly
        request-timeout: 30s
      - name: search
        transport: http
        url: https://mcp.example.com
        endpoint: /mcp
        headers:
          Authorization: "Bearer ${MCP_SEARCH_TOKEN}"
        request-timeout: 30s
```

Set `MCP_SEARCH_TOKEN` in the service environment; keep credentials out of committed configuration. Each server needs a unique `name` (1–16 characters, beginning with a letter; then letters, digits, `_`, or `-`). `transport` is `stdio` or `http`. A stdio server needs `command` and can receive `args` and explicitly supplied `env`; an HTTP server needs a base `url` and can receive `endpoint` (default `/mcp`) and `headers`. The default request timeout is 20 seconds. The application makes no MCP connection when the server list is empty.

At startup, the registry connects to every configured server, discovers its tools, and registers each as `mcp__<server>__<tool>`. Tool names outside the supported name alphabet or too long for the agent interface are escaped with a stable hash. If a configured server cannot initialize or list tools, startup fails and already opened connections close. The discovered tool list is a startup snapshot; restart the application after changing a server's tools. A custom Spring `Runner` can inject `McpToolRegistry` and choose how to expose them; the default runner merges MCP and CubeSandbox tools and rejects duplicate names.

Tool execution returns a single MCP text block as text, and encodes multiple blocks or `structuredContent` as JSON for the model. Large multi-block or structured results become a valid JSON object with `truncated: true` and a `preview`; large single text blocks get a truncated text preview. Binary image, audio, and resource data are omitted; their MIME type and other metadata remain visible. An MCP `isError: true` result becomes recoverable tool feedback so the model can correct the next call. A request timeout ends the local wait; the [Java SDK does not yet send MCP cancellation notifications](https://java.sdk.modelcontextprotocol.io/latest/client/#request-timeouts-and-cancellation), so the remote operation may continue. Only tool discovery and calls are exposed through this integration; MCP resources, prompts, sampling, elicitation, and a hosted MCP endpoint are outside its current scope.

The bundled `demo` model never calls tools; the configured Chat Completions adapter can call them during a turn. Stdio servers execute on the Java service host, with its process permissions, outside CubeSandbox. Their child process environment is cleared before launch; they receive only explicitly configured `env` values plus the SDK's default set such as `PATH` and `HOME`. Configure only servers you trust and grant each the minimum host access and credentials it needs. Use HTTPS for remote servers outside a trusted local network.

## CubeSandbox environment

An optional CubeSandbox backend gives each `environment.type: "cube"` session its own remote microVM. A [CubeSandbox cluster](https://docs.cubesandbox.com/guide/quickstart) must run on a Linux KVM host; this Java service can connect to it from macOS or another machine. The chosen template must include envd for command and file operations. Configure the CubeAPI URL and either a CubeProxy address for [path routing](https://docs.cubesandbox.com/guide/https-and-domain#path-based-quick-access-no-dns--cert), or CubeSandbox's virtual-host DNS:

```sh
export REDY_CUBE_ENABLED=true
export CUBE_API_URL=http://cube-api-host:3000
export CUBE_TEMPLATE_ID=your-template-id
export CUBE_PROXY_NODE_IP=cube-proxy-host
export CUBE_PROXY_PORT_HTTP=80
./mvnw spring-boot:run
```

`CUBE_API_KEY` supplies a bearer token when CubeAPI authentication is configured. If `CUBE_PROXY_NODE_IP` is omitted, set `CUBE_SANDBOX_DOMAIN` and route `49983-<sandbox-id>.<domain>` through CubeProxy. `CUBE_PROXY_SCHEME` selects `http` or `https`; `CUBE_REQUEST_TIMEOUT` controls request timeouts. `CUBE_SANDBOX_IDLE_SECONDS` defaults to `300`. New sandboxes request pause on timeout and auto-resume; command and file operations reconnect before use. Internet egress is denied by default using `network.denyOut`; set `CUBE_ALLOW_INTERNET_ACCESS=true` only when the session needs it. The client does not enable CubeSandbox's private inbound traffic mode: CubeAPI returns its traffic token only at creation, and durable token storage is not implemented. Restrict CubeProxy and CubeAPI access at the cluster boundary. Protect this service before exposing it outside a trusted network.

The environment variables map to `redy.cube.*` Spring properties, so deployments and tests can override them through Spring configuration. `CUBE_REQUEST_TIMEOUT` accepts a duration such as `30s`; a bare number is interpreted as seconds.

```sh
curl -sS -X POST http://127.0.0.1:8080/v1/agents/sessions \
  -H 'Content-Type: application/json' \
  -d '{"agent":{"name":"example","model":"demo"},"environment":{"type":"cube"}}'

curl -sS -X POST http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/environment/pause
curl -sS -X POST http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/environment/resume
curl -sS -X POST http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/environment/kill
```

The session response includes `environment.type`, `sandbox_id`, `template_id`, and `status`. The sandbox ID survives a service restart and is reused across turns. Pause, resume, and kill require no active turn; paused or killed environments reject new turns. A failed lifecycle request can leave a durable `pausing`, `resuming`, or `killing` status. Retry that same endpoint to reconcile it. Killing a sandbox ends its compute and ephemeral files while leaving the session history readable.

When enabled, the harness registers `sandbox.shell` (`{"command":"pwd","timeout_ms":30000}`), `sandbox.read_file` (`{"path":"/workspace/note.txt"}`), and `sandbox.write_file` (`{"path":"/workspace/note.txt","content":"hello"}`). These tools route only to the session's CubeSandbox. Shell returns `exit_code`, stdout, and stderr even when the command exits nonzero; the model decides what to do next. File tools handle UTF-8 text up to 1 MiB. The bundled `demo` model never asks for tools; configure a model that emits tool calls to use them during a turn. There is no direct public shell endpoint.
