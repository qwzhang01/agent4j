# Modules

Parent: `io.github.qwzhang01:seven-agent:0.1.5` (`packaging=pom`). Depend on the library modules you use. Do not put the parent POM on an application classpath as a jar.

Import `seven-agent-bom` (`type=pom`, `scope=import`) so versions stay aligned, then declare each `artifactId` without a version. `examples` is not published.

## Library modules

| Artifact | Responsibility |
|----------|----------------|
| `agent-core` | `ChatMessage`, `ModelClient`, `Tool`, `Agent`, `AgentLoop`. Also bounded reflection (`ReflectiveAgent`), optional parallel tool execution, and agent events |
| `agent-model` | `MockModelClient`, OpenAI-compatible and Anthropic clients, retry / timeout / fallback / structured-output decorators |
| `agent-plugin` | Load, unload, and reload SPI plugins, including an external JAR behind a checksum. One class loader per JAR. No multi-version isolation |
| `agent-sandbox` | ClassLoader sandbox and process sandbox. No Docker or WASM runtime |
| `agent-workflow` | Graph runtime, six node types, checkpoints, and durable run storage (in-memory and JDBC) |
| `agent-scheduler` | Timer and event wake-ups, in-memory and JDBC task queues |
| `agent-memory` | Working, session, and long-term memory, plus `MemoryScope` |
| `agent-security` | Permissions, approvals, sanitizer, audit |
| `agent-mcp` | MCP client (stdio and HTTP/SSE) and bidirectional HTTP agent-to-agent (A2A) |
| `agent-orchestrator` | Supervisor / worker dispatch |
| `agent-channel` | Identity, shared sessions, task handoff, proactive push |
| `agent-product` | YAML agent definitions, templates, prompt versions, webhooks |
| `agent-trace-export` | Trajectory records (state, action, observation, reward, done), JSONL, DPO preference pairs |
| `agent-enterprise` | Tenants, RAG, cost ledger, business tasks |
| `agent-tavern` | Roleplay profile: characters, world, turn engine |
| `agent-chat` | Room engine: speaker selection, context assembly, streaming, listeners. Not a game |
| `agent-coding` | Workspace, patches, command allowlist, bounded fix loop |
| `agent-observability` | Metrics, budgets, model routing, evaluation, version labels |
| `agent-otel-export` | Run events translated to OpenTelemetry spans. The SDK is not shipped inside core modules |
| `agent-spring-boot-starter` | Optional autoconfiguration: `ModelClient`, `AgentFactory`, health checks. The only module that depends on Spring. It does not pull in `agent-chat` |

Enterprise, roleplay (`agent-tavern`), and coding are three profiles on one runtime.

The smallest useful set is `agent-core` plus `agent-model`. Add governance, a graph, or memory when the loop needs them. A Spring Boot application can add `agent-spring-boot-starter` without making core depend on Spring.

## What depends on what

Most modules depend on `agent-core`. The links below are the ones that surprise people:

| Module | Typically pulled in by |
|--------|------------------------|
| `agent-core` | Almost everything |
| `agent-model` | Any module or example that calls a model |
| `agent-workflow` | Scheduler, product, enterprise, trace export |
| `agent-memory` | Channel, enterprise, tavern, and `agent-chat` (for the optional `MemorySource` only) |
| `agent-security` | MCP, coding, enterprise, tavern, channel |
| `agent-mcp` | Orchestrator, when you want remote tools or HTTP A2A |

## JDBC

`JdbcRunStore`, `JdbcRunLeases`, `JdbcSideEffectLedger`, `JdbcCheckpointStore`, `JdbcTaskQueue`, and `JdbcApprovalStore` program to `java.sql` only. The framework does not bind a database and does not ship a connection pool.

Bring your own JDBC driver and a connection factory. H2 is a `test`-scoped dependency in a few modules. It is not transitive for your application.

## Character conversations

| Module | Responsibility |
|--------|----------------|
| `agent-chat` | The room: who speaks, how the prompt is built, streaming, listeners |
| `agent-memory` | Store, extract, retrieve. Optional `MemorySource` mounted by the room |
| `agent-tavern` | A higher-level roleplay profile (characters, world, turns) on the same runtime |

`MemorySource` only reads into the prompt. Your application decides what is written, when extraction runs, and when a reminder fires. `LoreSource` injects text after a keyword or regex hit on the current turn. The word list is yours. `RelationSource` injects a snapshot and does not score it. `ConsistencyGuard` does nothing until you install one, and a warning never rewrites the reply.

## Tests that need extra services

`./mvnw verify` is the single-JVM suite. It does not assume PostgreSQL, Python, or Docker. Heavier tests are JUnit 5 tags. Turn one on with `-Dgroups=<tag>` (and, on CI, by clearing the default excluded groups).

| Tag | What it covers | What you need |
|-----|----------------|---------------|
| `postgres` | Durable workflow against a real database (`PostgresIT`) | PostgreSQL, and `AGENT4J_IT_PG_*` environment variables |
| `mcp-it` | A real Python MCP subprocess: handshake, tools, crash and retry | `python3` on `PATH`. The test skips, and says so, if it is missing |
| `a2a-it` | HTTP A2A over loopback: protocol, auth, signed pushes, restart | Nothing beyond the JDK |
| `sandbox-escape` | Escape regressions and the Docker placeholder contract | Runs in the default suite. Without a Docker daemon, the daemon probe skips |

Two more release checks live in CI: `-Papi-compat` (binary compatibility against the `0.1.3` baseline) and the CycloneDX SBOM plus `THIRD-PARTY.txt`, produced on `package` and `verify`. See `.github/workflows/ci.yml`.
