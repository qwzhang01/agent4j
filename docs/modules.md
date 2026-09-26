# Module Overview

Parent project: `io.github.qwzhang01:seven-agent:0.1.5` (latest on Central, `packaging=pom`).
Depend on library modules as needed — **do not** pull the whole parent POM into your app as a jar.

Use `seven-agent-bom` (`type=pom` / `scope=import`) to align versions (latest on Central: `0.1.5`), then declare specific `artifactId`s.

`examples` is for demonstration only and is **not published** (`excludeArtifacts` in the `release` profile).

| artifactId | Responsibility | Typical dependents |
|------------|----------------|--------------------|
| `agent-core` | Interfaces and data: `ChatMessage`, `ModelClient`, `Tool`, `Agent`, `AgentLoop`. Expression layer (Stage 9): `ReflectiveAgent` bounded reflection (PASS/REVISE/GIVE_UP protocol-word adjudication; critiques never enter user-visible output), `ParallelToolExecutor` declaration-order parallel tool execution + `ReActAgentLoop.withParallelTools` assembly, five new `AgentEvent` events (ModelCall/ToolValidationRejected/Reflection); governance rejection events (gap-closure): `[DENIED]`→approval, `[RATE_LIMITED]`→permission wiring, resume events left open (the three sources of `[DENIED]` are indistinguishable in the string and uniformly attributed to approval) | Nearly all modules |
| `agent-model` | `MockModelClient`; OpenAI-compatible / Anthropic clients; Retry / Timeout / Fallback / StructuredOutput decorators | Modules and examples needing real or mock models |
| `agent-plugin` | SPI plugin load / unload / reload + external JAR loading (checksum gate, one classloader per jar, manifest permission declarations, registration rollback, namespace isolation); **no** multi-version coexistence / module-layer confinement | Self-evolving tools, `PluginExample` |
| `agent-sandbox` | ClassLoader sandbox + process sandbox (**no** Docker / WASM) | `agent-coding`, sandbox examples |
| `agent-workflow` | Graph runtime, 7 node types, checkpoints; durable execution: RunStore/Lease/Ledger/CheckpointStore (in-memory + JDBC backends, plain ANSI SQL), `DurableRunManager` (heartbeat lease renewal + row watching), `DistributedRunControl` cross-instance Cancel/Resume guards/Approval Callback; planning layer (Stage 9 `plan` package): `Plan` (DAG validation: unknown/forward/self-loop/duplicate dependencies all rejected + `planVersion` + `readySteps` frontier), `PlanExecutor` (compiles to a topologically ordered linear chain reusing GraphRuntime — the single-cursor runtime does not allow fan-out with unconditional edges), `MultiAgentPlanner` (subtask state isolation + `deriveChild` budget/Trace propagation + identical-output dedup), `EventReplayer` (read-only replay of event history; tools are not re-executed; gap-closure: `replayPrefix` prefix folding — interactive time travel, mid-run worlds keep partial history, `doneWithinPrefix` decided within the Done window, broken-recording anomalies retained, out-of-bounds fails loud); plan-level recovery (gap-closure): `StepExecutor` exceptions go straight through + `Plan.rebuildWith` version evolution + `RunManager` `DEFINITION_VERSION_MISMATCH` guard; recovery granularity is last pause, not last node | `agent-scheduler`, `agent-product`, `agent-enterprise`, `agent-trace-export` |
| `agent-scheduler` | Timer / event wake-ups + task queue (in-memory + `JdbcTaskQueue` persistent task table, orphan recheck via `requeueOrphaned`) | `agent-channel`, scheduler examples |
| `agent-memory` | Working / session / long-term memory + `MemoryScope`. Packages: root wiring surface + `extract/` `store/` `context/` `session/` `tools/` | `agent-channel`, `agent-enterprise`, `agent-tavern`, `agent-chat` (`MemorySource`) |
| `agent-security` | Permissions / approvals / sanitizer / audit | `agent-mcp`, `agent-coding`, enterprise / tavern / channel |
| `agent-mcp` | MCP client (stdio + HTTP/SSE transport, server trust levels / allowlist / host auth adapters / schema validation) + bidirectional A2A: `HttpA2AClient` (spec dialects: card discovery / `message/send` / `tasks/get` / `message/stream` SSE / webhook push / input-required resume), `HttpA2AServer` (wraps an Agent as an endpoint: inbound sanitization + optional bearer gate + HMAC-signed pushes + replay window + pluggable `A2ATaskStore`). No PKI card signing; interop verification is loopback + JDK HttpServer dialect simulation | `agent-orchestrator`, MCP / A2A examples |
| `agent-orchestrator` | Supervisor / Worker / parallel dispatch | Multi-agent examples |
| `agent-channel` | Identity, shared sessions, task handoff, ambient | `agent-product`, channel examples |
| `agent-product` | YAML agent definitions, templates, prompt versioning, webhooks, DAG | Declarative / webhook examples |
| `agent-trace-export` | S-A-O-R-D trajectories, JSONL, DPO preferences | `agent-observability`, trajectory examples |
| `agent-enterprise` | Tenants / RAG / cost ledger / business tasks | Enterprise assistant examples |
| `agent-tavern` | Roleplay profile: characters / world / turn engine | Tavern examples |
| `agent-chat` | Room conversation engine: speaker selection / context assembly / streaming / notifications. Optional `MemorySource` / `LoreSource` / `RelationSource`; optional `ConsistencyGuard` (default no-op); group chat via `RoundRobinSpeaker`; `PersonaRenderer` hook. **Not** a tavern game | Moonlit / SillyTavern-style apps; `ChatRoomExample` |
| `agent-coding` | Workspace / patches / command allowlist / fix loop | Coding agent examples |
| `agent-observability` | Metrics (Prometheus text / JSONL sinks), five-dimension budgets, routing (`RiskAwareRouter` gap-closure: `SideEffectLevel` risk signal — DESTRUCTIVE/SIDE_EFFECT/UNKNOWN exposure raises the premium, NONE/READ_ONLY route cheap, unclassified assembly = no signal, skipped without fabricating risk; pre-call proxy signals are directionally safe), evaluation (golden sets + five online metrics / sampling / version comparison / drift alerts / unified reports), version triplets, ops event bus | Observability examples |
| `agent-otel-export` | Thin RunEvent → OTel span shell (`agent.run`/`agent.step`/`agent.model`/`agent.tool`); the SDK lives only in the test scope, never in core | OTel examples |
| `agent-spring-boot-starter` | **Optional** Spring Boot autoconfiguration: `ModelClient` + profile-aware `AgentFactory` (Stage 8.2: `agent4j.profile` defaults to secure — governance assembly wired automatically; the test profile auto-allows on the same stack; unsafe is explicit bare-metal) + startup high-risk config checks (SECURE contradictions fail startup) + six-facet health checks + graceful shutdown coordinator + config versioning. **The only module depending on Spring**. Does not auto-depend on `agent-chat` | Spring Boot 3.2 apps (e.g. Moonlit) |
| `examples` | Runnable examples (see `examples/README.md`) | None (consumes the modules above) |

Enterprise / tavern / coding are **three domain profiles on the same runtime**, not three frameworks.

Minimal adoption: `agent-core` + `agent-model`. Add graph, governance, or memory as needed. Spring Boot apps can additionally add `agent-spring-boot-starter` (core / model still have no Spring).

**JDBC storage driver convention**: `JdbcRunStore` / `JdbcRunLeases` / `JdbcSideEffectLedger` / `JdbcCheckpointStore` / `JdbcTaskQueue` / `JdbcApprovalStore` program only against `java.sql` interfaces — the framework binds to no database. Production deployments bring their own JDBC driver (e.g. PostgreSQL) and wire a connection factory; switching databases requires no code changes. `com.h2database:h2` exists only with `test` scope in three modules' test JVMs, for distributed-storage contract tests; it never enters any consumer's transitive dependencies.

## Integration test profiles (Stage 8.3)

The default `./mvnw verify` runs the full single-JVM suite (no external services assumed). Transport-level / database-level ITs are opt-in via JUnit 5 tags; CI enables them with `-Dagent4j.surefire.excludedGroups= -Dgroups=<tag>`:

| Tag | Coverage | Prerequisites | Quick local run |
|-----|----------|---------------|-----------------|
| `postgres` | `PostgresIT` (full-chain durable workflow verification against a real database) | Local PostgreSQL; probes enabled by `AGENT4J_IT_PG_URL` etc. env vars | `-Dgroups=postgres` + export `AGENT4J_IT_PG_*` |
| `mcp-it` | `McpStdioIT` (full chain with a real Python subprocess MCP server: handshake / tool discovery / invocation / crash-recovery restart-retry / protocol errors don't trigger spurious restarts) | `python3` on PATH (otherwise an auditable explicit skip) | `-Dgroups=mcp-it` |
| `a2a-it` | `HttpA2ARoundTripTest` / `HttpA2AServerProtocolTest` / `HttpA2AServerSecurityTest` (full chain over real loopback HTTP: round trips / wire protocol / auth / signed pushes / task survival across restarts) | None (local sockets) | `-Dgroups=a2a-it` |
| `sandbox-escape` | `SandboxEscapeTest` + `ProcessSandboxRedTeamTest` + `DockerDaemonProbeIT` (24 escape regressions + container daemon probe + DOCKER placeholder loud-fail contract) | Nothing external required (without docker, the daemon test takes an auditable skip) | Runs by default |

Two additional release gates: `-Papi-compat` (japicmp binary-compatibility assertions against the 0.1.3 baseline; modules without a baseline declare an explicit `japicmp.skip` in their own pom) and SBOM / license checks (`target/bom.json` CycloneDX 1.5 + `THIRD-PARTY.txt`) generated on every `package`/`verify`. CI runs all gates on `main`/PRs (see `.github/workflows/ci.yml`).

## Character engine wiring (Moonlit / SillyTavern-style apps)

| Module | Responsibility |
|--------|----------------|
| `agent-chat` | `ChatRoom` / `ChatEngine`: speaker selection, context assembly, streaming, `ChatListener`; `PersonaRenderer`; optional `ConsistencyGuard` |
| `agent-memory` | Store / Extractor / Retriever; optional `MemorySource` mounted on the chat side |

Boundaries: `MemorySource` only handles **reading into the prompt**; what gets written, when to extract, and when to remind live in Moonlit (Listener + Job). `LoreSource` only injects after a keyword/regex hit on the current turn; the vocabulary lives in the product. `RelationSource` only injects the snapshot; it does not score. `ConsistencyGuard` defaults to no-op; warnings never rewrite. `DirectorSpeaker` picks speakers with an independent ModelClient + business prompt and can be combined with Mention. Character-oriented eval: `CharacterEvalTest`. Details in `notes/architecture-agent-chat.md` §9 and `notes/architecture-character-engine.md` (Waves 1–4 + T28 done; T23 skipped by default).
