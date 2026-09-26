# Examples

Runnable `main` classes live in `io.github.qwzhang01.agent.examples`. SPI plugin classes in the same tree are not entry points.

**Start with `MockAgentExample`.** No API key, no external processes. It shows a scripted model, one tool, and `SecureAgentBuilder`.

## How to run

From the repository root. `-am` builds the modules the example needs, so you do not have to `install` first.

```bash
./mvnw -pl examples -am compile exec:java \
  -Dexec.mainClass=io.github.qwzhang01.agent.examples.MockAgentExample
```

Replace `MockAgentExample` with any class in the tables below. You can also run the `main` method from an IDE after importing the reactor.

## Examples that need no API key

These use `MockModelClient` or purely local components and do not access the network by default.

| Class | What to look at |
|-------|-----------------|
| **`MockAgentExample`** | **Minimal agent: register a tool + scripted model + `run`** |
| `StreamingAgentExample` | `stream`: printing `ContentDelta` as it arrives |
| `DecoratedModelClientExample` | Stacking Retry / Timeout / Fallback / StructuredOutput decorators |
| `PluginExample` | SPI discovery, load, unload, reload |
| `PluginSelfModificationExample` | The model manages plugins in-conversation (inspect / load / unload) |
| `SandboxExample` | ClassLoader / process sandboxes |
| `SandboxAgentExample` | The full chain of the model triggering `sandbox_execute` |
| `WorkflowSupportFlowExample` | A three-way support graph: lookup / refund approval / escalate to human |
| `CheckpointExample` | Saving and restoring graph execution at a breakpoint |
| `SchedulerExample` | Timer-based resume, event resume, task queue |
| `MemoryExample` | Multi-turn memory writes and re-injection |
| `CompressionExample` | Compressing context when over budget |
| `ChannelMemoryExample` | Channel-shared memory + approvals / overrides |
| `SecurityExample` | Three permission levels + approvals + audit |
| `InjectionDefenseExample` | Tool-output injection: SANITIZE / TRUNCATE / BLOCK |
| `McpExample` | In-process mock MCP: tool discovery + governed execution |
| `MultiAgentExample` | Supervisor dispatching internal workers in parallel + in-process A2A |
| `HttpA2AExample` | A2A over real HTTP: card discovery / `message/send` / status polling / inbound rejection / supervisor routing |
| `ChannelAgentExample` | Channel identity, shared sessions, task handoff |
| `AmbientExample` | Ambient proactive push + noise gate |
| `TrajectoryExample` | Trajectory recording → reward → sampling → JSONL → replay |
| `PreferenceAnnotationExample` | Double-rollout annotation writing DPO preferences |
| `EnterpriseAssistantExample` | Tenants / RAG / approval breakpoints / budget rejection |
| `TavernGameExample` | Characters / world / turn engine / replay |
| `ChatRoomExample` | Room chat: 1:1 streaming + two-person `@` mentions (zero LLM) |
| `CodingAgentExample` | Workspace, patches, command allowlist, bounded fix loop |
| `ObservabilityExample` | Metrics, five-dimension budgets, routing, evaluation, version triplets |
| `DeclarativeAgentExample` | YAML-defined agent + templates / prompt versions / DAG |
| `WebhookExample` | HMAC + idempotent webhooks driving an agent |

## Requires extra environment / real services

Check each class's javadoc for prerequisites (Node / `npx`, model endpoints, multimodal services, etc.) before running.

| Class | Extra dependency |
|-------|------------------|
| `LlmDrivenSchedulerExample` | Wait-events / delays driven by model output (not parameters hard-coded on the graph) |
| `MultimodalExample` | Clients and governance defaults for image reading / generation / video generation |
| `McpRealServerExample` | The official MCP filesystem server (`npx -y @modelcontextprotocol/server-filesystem`) |
| `ManagedMcpExample` | Same real stdio server, demonstrating budget-bound restarts after crashes |

Typical preparation for `McpRealServerExample` / `ManagedMcpExample`:

```bash
mkdir -p /tmp/mcp-demo && echo "hello" > /tmp/mcp-demo/hello.txt
```

## Suggested order

1. `MockAgentExample` — confirm the loop and tools work  
2. `DecoratedModelClientExample` — then swap in a real `OpenAiModelClient` (constructor in that class's javadoc / tests)  
3. `SecurityExample` — tools pass governance by default  
4. `WorkflowSupportFlowExample` + `CheckpointExample` — graphs and breakpoints  
5. By scenario: enterprise / roleplay / coding / observability / trajectories  

For concepts see [../docs/concepts.md](../docs/concepts.md); for what v1 cannot do, read [../docs/limitations.md](../docs/limitations.md) first.
