# Core Concepts

This page covers only how the runtime works. For the module inventory, see [modules.md](modules.md). The stage notes in `notes/` are learning material, not a contract.

## Agent

`Agent` is the entry point, deliberately thin: `run(userInput)` runs a full turn; `run(userInput, state)` resumes with an existing `AgentState`. Streaming goes through `stream(userInput, listener)`, emitting `AgentEvent` callbacks as generation proceeds. Multimodal user messages go through `run(ChatMessage)` / `stream(ChatMessage, listener)`.

The default implementation is `SimpleAgent`. The production entry point is `SecureAgentBuilder` (validation sits outside governance: validate → permission → rate-limit → approval → execute). The static blueprint is `AgentConfig`: name, system prompt, `ModelClient`, `ToolRegistry`, `maxSteps`, plus an optional `ContextBuilder` (memory injection; `null` means pass-through). When `run(...)` is called without a `RunContext`, one is created automatically via `RunContext.create()`; side-effect tools without a context are denied `[DENIED]` at the validation layer.

Module: `agent-core`. Examples: `MockAgentExample` / `StreamingAgentExample`.

## Tool

A `Tool` is an action the model can invoke. Tools register into a `ToolRegistry` (default `InMemoryToolRegistry`) and are executed by a `ToolExecutor`. The model returns a `ToolCall`; the loop invokes the tool, writes the result back into the message list, and asks the model again.

A tool can be a local Java class (`CurrentTimeTool`), an SPI plugin, an MCP adapter, or code compiled and executed inside a sandbox. The governance layer (permissions / approvals / sanitizer / audit) wraps the executor and never touches the `Tool` interface.

Modules: interfaces in `agent-core`; plugins in `agent-plugin`; governance in `agent-security`; MCP in `agent-mcp`.

## Loop

`AgentLoop` is the ReAct loop: within `maxSteps`, repeatedly "assemble request → `ModelClient.chat` → execute tool calls if any → otherwise finish". `stream` uses the same loop but calls `ModelClient.stream`, pushing token / tool lifecycle events to an `AgentEvent` sink. The loop is a function, not a thread: it takes an `AgentConfig` plus a mutable `AgentState` and returns the updated state. Testable and resumable.

`maxSteps` is a bound **accumulated across the whole conversation**; `AgentConfig` is the SSOT: each `run(input, state)` syncs `state.maxSteps` to the current config value but does **not** reset `currentStep`. If 8 steps have already run and the config says 10, resuming leaves only 2 steps. To loosen or tighten the bound, change the config, not the state.

The default implementation is `ReActAgentLoop`. `SimpleAgent` delegates `run` / `stream` to it.

Module: `agent-core`.

## Workflow

When a single `Agent.run` cannot handle branching, human approvals, parallelism, or long waits, use the graph engine. A `Workflow` is an immutable graph; `GraphRuntime` interprets it; state lives on the `WorkflowState` blackboard.

v1 ships 7 node types (`ActionNode` / `AgentNode` / `ToolNode` / `RouterNode` / `HumanApprovalNode` / `ParallelNode`, plus the `JoinPolicy` for parallel joins). An `Agent` can be a node on the graph — no parallel framework needed.

Module: `agent-workflow`. Example: `WorkflowSupportFlowExample`.

## Memory

Three memory tiers plus one scope axis:

| Tier | What it is |
|------|------------|
| Working | The conversation inside the current `AgentState` |
| Session | Continuous context within one session |
| Long-term | Retrievable entries in a `MemoryStore` |

`MemoryScope` (agent / user / session / task / channel) decides "who can see what". Shared memory is not a second system — it is just a different scope value. Writes go through a `MemoryExtractor` (`extract.KeywordMemoryExtractor` or `extract.LlmMemoryExtractor`) plus a `MemoryPolicy`; reads go through a `MemoryRetriever` (`recallForContext` takes topN ranked by importance then recency) plus `context.MemoryContextBuilder`. The room engine uses an optional `MemorySource` (`ChatRoom.Builder.source`, not attached by default). `dueAt` is an optional timestamp that `LlmMemoryExtractor` can parse from JSON; queries can filter by range; the framework does not schedule it or interpret its meaning. Extraction instructions and the subject vocabulary are decided by the caller. When over budget, `context.ContextCompressor` compresses.

Packages are split along the pipeline, still a single Maven module: the root package is the wiring surface (Store / Entry / Query / Scope / Extractor / Retriever / Policy / Admin); `extract/` writes, `store/` persists, `context/` reads and compresses, `session/` is the session layer, `tools/` exposes model-managed memory.

Module: `agent-memory`. Examples: `MemoryExample` / `CompressionExample` / `ChannelMemoryExample`.

## ChatRoom

The room conversation engine (`agent-chat`) differs from a single `Agent.run`: multiple personas, speaker selection, context assembly, streaming, business listeners.

| Concept | Description |
|---------|-------------|
| `ChatRoom` / `ChatEngine` | One `say(userLine)`: select speaker → assemble messages → `ModelClient.stream` → notify listeners |
| `SpeakerPolicy` | Who replies: `SoloSpeaker` / `MentionSpeaker` / `RoundRobinSpeaker` / `DirectorSpeaker` (composable) |
| `ContextSource` | Assembles prompt fragments: `PersonaSource` / `HistorySource` / `ExtraTextSource` / optional `MemorySource` / `LoreSource` / `RelationSource` |
| `ChatListener` | Business callbacks: persistence, extraction, relations; the engine has no built-in memory write-back. Optional `onConsistencyWarning` |
| `ConsistencyGuard` | Optional post-turn drift guard: persona anchor + reply → OK/warning. Default no-op; never rewrites history |
| `RoomIdentity` | Room identity: an opaque memory-scope string. The engine parses no prefixes and depends on no channel |
| `RelationSnapshot` | Relation snapshot: free-form stage plus slots. `RelationSource` only injects it; it does not score |

The default `ContextAssembler.defaults()` = Persona + History(20). `MemorySource` / `LoreSource` / `RelationSource` are **not attached by default**; when you customize `.source(...)` you must bring Persona + History yourself. `RoomIdentity` attaches user/session/pair scope strings to the room; `MemorySource` inherits them when no explicit list is given. `LoreSource` only scans the current user line (keywords/regex); the vocabulary lives in the product. `RelationSource` injects the `RelationSnapshot` (stage + slots) without scoring; pre-rendered relation text can still go through `ExtraTextSource`. `ConsistencyGuard` is no-op by default; warnings never rewrite replies. Extraction, subject vocabulary, and proactive reminders (`dueAt` scans) all live on the business side (Moonlit; the T12 prompt already includes daily follow-ups; the T17 Job is wired up) — see `notes/architecture-agent-chat.md` §9. Moonlit's 1:1 chat already runs on `ChatRoom.stream` (T15).

Module: `agent-chat` (its compile dependency on `agent-memory` exists only for the optional `MemorySource`). Example: `ChatRoomExample`. Character-oriented eval: `CharacterEvalTest` (mock-based, no LLM-as-judge).

## Governance

Tools are untrusted by default. The 5-minute quick start uses `SecureAgentBuilder`: validation sits outside governance (validate → permission → rate-limit → approval → execute); read-only tools run AUTO, everything else is REQUIRES_APPROVAL, and zero configuration means `autoReject`. A pending approval parks the agent in `WAITING_APPROVAL` (`DurableToolApprovalService` writes an `ApprovalStore` without blocking threads); `Agent.resume` retries the same tool call after a decision lands. The ungoverned path requires naming `UnsafeAgentBuilder` explicitly.

Four governance pieces hang on the `GovernedToolExecutor`:

1. **Permission** — `AUTO` / `REQUIRES_APPROVAL` / `DENY`
2. **Approval** — manual, automatic, or durable approvals (`PENDING` → pause)
3. **Sanitizer** — injection defense on tool outputs (replace / truncate / block)
4. **Audit** — allow, deny, execute, failure, and sanitize decisions are all recorded

MCP tools registered through the adapter go through the same stack — no duplicate wiring.

Module: `agent-security`. Examples: `SecurityExample` / `InjectionDefenseExample`.

## Checkpoint

Long-running flows stop: waiting for a person, an event, or a timer. On pause, the graph state and `AgentState` are persisted; on resume, execution continues from the breakpoint instead of rerunning.

`Checkpoint` / `CheckpointStore` (in-memory or file) belong to the workflow runtime. The scheduler (`agent-scheduler`) triggers `resume` after a timer or event arrives. Enterprise task approvals and channel handoffs reuse the same "stop → persist → resume" mechanism.

Modules: `agent-workflow` (storage) + `agent-scheduler` (wake-up). Examples: `CheckpointExample` / `SchedulerExample`.
