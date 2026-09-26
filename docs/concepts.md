# Core concepts

This page is how the runtime behaves. The module list is in [modules.md](modules.md). Files under `notes/` are design notes, not a contract.

## Agent

`Agent` is a thin entry point.

- `run(userInput)` completes one turn.
- `run(userInput, state)` continues from an existing `AgentState`.
- `stream(userInput, listener)` emits `AgentEvent` values as tokens arrive.
- Multimodal input uses `run(ChatMessage)` and `stream(ChatMessage, listener)`.

`SimpleAgent` is the default implementation. `SecureAgentBuilder` is the production entry point. It orders the gates as: validate, permission, rate limit, approval, execute. Validation sits outside the governance stack.

`AgentConfig` is the static blueprint: name, system prompt, `ModelClient`, `ToolRegistry`, `maxSteps`, and an optional `ContextBuilder`. A null context builder passes messages through unchanged. `run` without a `RunContext` creates one. A side-effect tool that still has no context is denied at validation.

Module: `agent-core`. Examples: `MockAgentExample`, `StreamingAgentExample`.

## Tool

A `Tool` is an action the model can call. Tools live in a `ToolRegistry` (default `InMemoryToolRegistry`) and run through a `ToolExecutor`. The model returns a `ToolCall`. The loop runs the tool, appends the result to the message list, and calls the model again.

A tool can be a local Java class (`CurrentTimeTool`), an SPI plugin, an MCP adapter, or code compiled inside a sandbox. Permissions, approvals, the sanitizer, and audit wrap the executor. They do not change the `Tool` interface.

Interfaces live in `agent-core`. Plugins are `agent-plugin`. Governance is `agent-security`. MCP is `agent-mcp`.

## Loop

`AgentLoop` is the ReAct loop. Until `maxSteps`, it builds a request, calls `ModelClient.chat`, executes any tool calls, and otherwise finishes. `stream` uses the same loop with `ModelClient.stream`, and pushes token and tool events to an `AgentEvent` sink.

The loop is a function, not a thread. It takes an `AgentConfig` and a mutable `AgentState`, and returns the updated state. That is what makes it testable and resumable.

`maxSteps` is a budget for the whole conversation, not for a single turn. `AgentConfig` owns the number. Each `run(input, state)` copies the current config value onto the state and does not reset `currentStep`. If 8 steps have already run and the config says 10, a resume has 2 steps left. Change the config to raise or lower the cap. Do not edit the state to do it.

The default implementation is `ReActAgentLoop`. `SimpleAgent` delegates `run` and `stream` to it.

Module: `agent-core`.

## Workflow

Use the graph when one `Agent.run` is not enough: branches, human approval, parallel work, or a wait that should outlive the process.

A `Workflow` is an immutable graph. `GraphRuntime` interprets it. State sits on a `WorkflowState` blackboard.

This release has six node types: `ActionNode`, `AgentNode`, `ToolNode`, `RouterNode`, `HumanApprovalNode`, and `ParallelNode`. `JoinPolicy` decides how parallel branches join. An `Agent` can be a node. You do not need a second framework for that.

Module: `agent-workflow`. Example: `WorkflowSupportFlowExample`.

## Memory

Three tiers, plus a scope that decides who can read an entry.

| Tier | What it holds |
|------|----------------|
| Working | The conversation inside the current `AgentState` |
| Session | Context that lasts for one session |
| Long-term | Entries in a `MemoryStore` that can be retrieved later |

`MemoryScope` is one of agent, user, session, task, or channel. Shared memory is a different scope value, not a second store.

Writes go through a `MemoryExtractor` (`KeywordMemoryExtractor` or `LlmMemoryExtractor`) and a `MemoryPolicy`. Reads go through a `MemoryRetriever`. `recallForContext` returns the top N entries, ranked by importance and then recency. `MemoryContextBuilder` turns them into prompt text. `ContextCompressor` shrinks context when it is over budget.

`dueAt` is an optional timestamp. `LlmMemoryExtractor` can parse it from JSON, and queries can filter on a range. The framework does not schedule it and does not decide what it means. What to extract, and the vocabulary of subjects, is the caller's choice.

Packages follow the pipeline and stay in one Maven module. The root package is the wiring surface: store, entry, query, scope, extractor, retriever, policy, admin. `extract/` writes. `store/` persists. `context/` reads and compresses. `session/` is the session layer. `tools/` exposes memory the model can manage itself.

Module: `agent-memory`. Examples: `MemoryExample`, `CompressionExample`, `ChannelMemoryExample`.

## Chat room

`agent-chat` is a room, not a single `Agent.run`. Several personas share a turn. Someone has to choose the speaker, assemble the prompt, stream the reply, and notify your application.

| Type | Role |
|------|------|
| `ChatRoom`, `ChatEngine` | One `say(userLine)`: pick a speaker, build messages, call `ModelClient.stream`, notify listeners |
| `SpeakerPolicy` | Who replies. `SoloSpeaker`, `MentionSpeaker`, `RoundRobinSpeaker`, `DirectorSpeaker`. They compose |
| `ContextSource` | Prompt fragments: `PersonaSource`, `HistorySource`, `ExtraTextSource`, and the optional `MemorySource`, `LoreSource`, `RelationSource` |
| `ChatListener` | Your callbacks: persist, extract, update relations. The engine does not write memory for you |
| `ConsistencyGuard` | Optional check after a turn: persona anchor plus reply, then OK or warning. The default does nothing. It never rewrites history |
| `RoomIdentity` | An opaque string for the memory scope. The engine does not parse prefixes |
| `RelationSnapshot` | A free-form stage plus slots. `RelationSource` injects it. It does not score the relationship |

`ContextAssembler.defaults()` is persona plus the last 20 history messages. `MemorySource`, `LoreSource`, and `RelationSource` are not attached unless you add them. If you replace `.source(...)`, you must include persona and history yourself.

`RoomIdentity` puts user, session, and pair scope strings on the room. `MemorySource` inherits them when you do not pass an explicit list. `LoreSource` scans only the current user line, by keyword or regex. The word list lives in your application. `RelationSource` injects the snapshot and does not score it. Pre-rendered relationship text can still go through `ExtraTextSource`.

`ConsistencyGuard` is a no-op unless you set one. A warning never rewrites the reply. Extraction, the subject vocabulary, and reminders (including scans of `dueAt`) belong in the host application, usually a listener and a scheduled job.

`agent-chat` depends on `agent-memory` only so the optional `MemorySource` can compile. Example: `ChatRoomExample`. Character-style checks live in `CharacterEvalTest`, which uses a mock model, not an LLM judge.

## Governance

Tools are untrusted unless you say otherwise. `SecureAgentBuilder` is the default for new code.

The order is fixed: validate, then permission, then rate limit, then approval, then execute. Read-only tools are `AUTO`. Everything else is `REQUIRES_APPROVAL`. With no approval policy, the call is rejected. It is not executed.

A pending approval parks the agent in `WAITING_APPROVAL`. `DurableToolApprovalService` writes an `ApprovalStore` and does not block a thread. `Agent.resume` retries the same tool call after a decision.

Four pieces sit on `GovernedToolExecutor`:

1. **Permission** — `AUTO`, `REQUIRES_APPROVAL`, or `DENY`.
2. **Approval** — manual, automatic, or durable. `PENDING` pauses the run.
3. **Sanitizer** — injection defense on tool output: replace, truncate, or block.
4. **Audit** — allow, deny, execute, failure, and sanitize decisions are recorded.

MCP tools registered through the adapter use the same stack. There is no second policy path.

The ungoverned path is `UnsafeAgentBuilder`. The name is intentional.

Module: `agent-security`. Examples: `SecurityExample`, `InjectionDefenseExample`.

## Checkpoint

Long runs stop. They wait for a person, an event, or a timer. On pause, graph state and `AgentState` are stored. On resume, execution continues from that breakpoint.

`Checkpoint` and `CheckpointStore` (in-memory, file, or JDBC) belong to the workflow runtime. `agent-scheduler` calls `resume` when a timer or event arrives. Enterprise approvals and channel handoffs use the same stop, persist, resume sequence.

Modules: `agent-workflow` for storage, `agent-scheduler` for wake-up. Examples: `CheckpointExample`, `SchedulerExample`.
