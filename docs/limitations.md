# v1 boundaries and known limitations

Version: `0.1.5` (released 2026-09-17; deploy executed manually). Below are capabilities that are **intentionally not built** or **not yet available** — to prevent misjudging delivery status from `notes/` or the repo's size.

## Maven Central

The coordinates are `io.github.qwzhang01:seven-agent` (latest on Central: `0.1.5`). Releases go through `./mvnw -DskipTests deploy`; `examples` is not part of the bundle. Reference directly from Central; to track the development version, clone the repo → `./mvnw -B verify` or `mvn install`, then reference from your local repository.

Usage: [getting-started.md](getting-started.md).

## No Spring at runtime

`agent-core` / `agent-model` and other runtime modules do not pull in Spring Framework. The parent POM is a standalone aggregate project, not `spring-boot-starter-parent`.

The optional module `agent-spring-boot-starter` is the **only** module depending on Spring: it reads `agent4j.*` config, creates a `ModelClient`, and provides an `AgentFactory`. Core stays Spring-free. No Actuator integration.

## Intentionally not built in v1

| Capability | What v1 actually has |
|-----------|----------------------|
| Plugin multi-version coexistence / module-layer confinement | SPI plugin load / unload / reload; external JAR loading (`PluginJarLoader`: SHA-256 checksum gate + one framework-first URLClassLoader per jar + SPI registration-domain isolation + `PluginManifest` host-side permission declarations; only the tools permission has an executable registration surface); SPI plugins run with full in-process JVM permissions (the framework isolates the registration surface, not a code-execution sandbox) |
| Docker / WASM sandbox | `ClassLoaderSandbox` + `ProcessSandbox` |
| MCP Streamable-HTTP, full OAuth client flows, resources/prompts capabilities | MCP client: stdio + HTTP/SSE transport (2024-11-05 SSE dialect); three server trust levels (TRUSTED/RESTRICTED/UNTRUSTED, deny-on-absence) + allowlist + host auth adapters (bearer/staticToken/refreshable) + structural schema validation at entry; can connect to the official filesystem server |
| A2A card signing, external third-party peer interop | A2A **HTTP** bidirectional: `HttpA2AClient` (`message/send` / `tasks/get` / card discovery / `message/stream` SSE / webhook push / input-required resume) + `HttpA2AServer` (wraps an Agent as an endpoint: inbound sanitization + optional bearer gate (constant-time comparison) + HMAC-SHA256-signed webhook pushes + 5-minute time window + nonce replay cache); task storage uses the pluggable `A2ATaskStore` interface (default in-memory, swappable for a persistent store); shared-store task survival across server restarts is tested; lease semantics support cross-instance claiming; interop verification = own-client loopback + JDK HttpServer simulating third-party dialects, no external implementations |
| Real Git | `agent-coding` is workspace + patches + command allowlist + a bounded fix loop; it does not wrap Git |
| OpenTelemetry SDK in core | The thin `agent-otel-export` module (Stage 7.1) provides RunEvent/Metrics → OTel span translation (opentelemetry-api at compile scope, SDK only in test scope for verification); `agent-observability` keeps its own metrics / budgets / routing / evaluation / version triplets; the SDK never enters any core module (D9); the span inventory reached twelve kinds in the 2026-09-17 batch 7 — adding agent.mcp/agent.a2a (emitter wiring at the McpToolAdapter/InProcessA2AClient boundaries); the HttpA2AClient emitter, cross-server trace-context propagation, and a Micrometer adapter are not done |
| Mini VERL training | `agent-trace-export` exports trajectory JSONL and DPO preferences; the training loop is not in the library |
| LLM-as-judge | Evaluation uses rules / failure-sample regression sets, not a model as judge |

These are v1 non-goals, not "missed for the next phase".

## Sandbox security boundaries (Stage 4)

v1 implements up to the PROCESS tier. Do not treat `ProcessSandbox` as a security sandbox against malicious code: it defends against **accidents and low-strength attacks** — all eight red-team attacks are blocked — but its boundary is a guest-side SecurityManager that can in theory be bypassed via JDK internals.

### Security level per tier (weak to strong)

| Tier | Status | Defends against | Does not defend against |
|------|--------|-----------------|-------------------------|
| CLASSLOADER | ✅ `ClassLoaderSandbox` | Accidental calls into dangerous APIs | Reflection, `Unsafe`, JNI escapes |
| PROCESS | ✅ `ProcessSandbox` (guest guard) | All of the above + guest-side file/network/process/output truncation | The guard itself being bypassed via JDK internals; host OS user identity is not isolated |
| DOCKER | ❌ awaiting Linux CI | All of the above + namespace/cgroup structural isolation | Kernel exploits |
| gVisor / MICROVMM | ❌ not implemented | All of the above + kernel attack-surface reduction | Side channels |
| WASM | ❌ not implemented | All of the above + no syscalls | WASM runtime bugs |

The v1 position of the DOCKER tier is locked in by tests (`DockerDaemonProbeIT`, Stage 8.3): daemon presence is detectable and recordable, but even with a daemon present, `SandboxReport` still claims zero guarantees for the DOCKER tier — the fact "no adapter" is itself a loud-fail contract, preventing anyone from selling the placeholder as a capability later.

### What the PROCESS tier enforces

- Guest-side `SandboxGuard` (SecurityManager installed in two phases, zero awareness in guest source code): all reads/writes/deletes outside the workspace denied; `java.home` read-only; process execution fully denied; Socket/Net permissions fully denied; exitVM and installing a second SecurityManager pass per whitelist
- Environment variable allowlist: by default only `PATH/TMPDIR/LANG/TZ/LC_ALL/LC_CTYPE` are inherited (`HOME` excluded); `ENV_INHERIT_ALL=["*"]` explicitly opts back in
- Output truncated at 1MB per stream by default (marked `truncated by sandbox`); timed-out process trees are killed wholesale; temp directories cleaned in `finally`
- `UNRESTRICTED` mode injects no guard, is restricted to TRUSTED debugging, and tests honestly record NOT-BLOCKED

### macOS local vs Linux production

Local macOS development: the guest-side guard enforcement is the entire boundary; processes run as your OS user with no UID/GID isolation. Linux production: the PROCESS tier likewise has only the guard boundary; structural isolation (namespace/cgroup/UID) waits for the DOCKER adapter. **Do not** run ADVERSARIAL code on high-risk production paths before the adapter lands. `TierLimits.forRisk(risk, multiTenant)` returns the actual limits per risk tier; `requiresApproval=true` (ADVERSARIAL) means the path needs human approval or a stronger sandbox.

## Data protection boundaries (Stage 5)

Sensitive-data governance is **opt-in, not default**: the governance capabilities (masking / audit / identity binding) are all in place, but the default assembly paths do not force them on. Hosts must wire them explicitly before connecting production data.

### Governance pieces in place

- `SecretMasker`: regex rule engine (seven default presets: api-key / AWS key / JWT / bearer / email / phone / bank card + tenant-defined rules), placeholder `[REDACTED:<rule-name>]`; `mask` never throws
- `RedactionPolicy`: four flags (RAW/SUMMARY/HASH/MASKED) + four stance factories (rawOnly = legacy behavior / maskedOnly = no plaintext in exports / rawPlusMasked = memory-ledger stance / hashOnly = logging stance)
- `MemoryGovernance`: RunContext-derived scope whitelist (unforgeable) + purpose enforcement + read/write audit (MemoryAccessAuditRecord) + verifiable deletion propagation (DeletionPropagation)
- `TrajectoryCodec(masker)`: parameterized masking on the export surface; default null = byte-for-byte legacy behavior

### Honest boundaries

- No masking by default: `SecretMasker` / `MemoryGovernance` / masked `TrajectoryCodec` are all explicit opt-ins; without wiring, the legacy behavior (raw passthrough) applies. This is the compatibility choice for single-tenant local runs; production multi-tenant setups must wire them
- Encryption is the host's job: v1 has no built-in encrypted storage adapter; encryption at rest is provided by the host at the PG/disk layer; the framework guarantees no plaintext beyond the host boundary on the export surface
- `metadata.last_error` is exported unmasked: exception text is structural information (class name + message); masking would break debugging; recorded as a known v1 gap
- Retention is hard delete + TTL (`expireAt`) only; no anonymization path
- MemoryProvenance has no dedicated model-version field (carried through the actor string)
- Deletion propagation for Trace/Trajectory exports (beyond Memory) is left to Stage 8

## Other honest boundaries

- **Narrow model coverage**: Mock, OpenAI-compatible, Anthropic. No vendor-portfolio connectors.
- **Memory persistence is bare JDBC**: `PgMemoryStore` uses a host-provided `DataSource` (no connection pool, no ORM, no framework-managed migrations); the schema is created idempotently; `InMemoryMemoryStore` suits single-machine and test use.
- **Checkpoints**: three stores (in-memory / file / JDBC; `JdbcCheckpointStore` reuses the same codec and shares the blackboard across instances); no managed workflow backend.
- **Distributed execution boundaries (Stage 8.1)**: cross-instance Cancel/Resume/Approval Callback semantics have landed (rows as control channels + heartbeat row watching), but the JDBC queue has **no capacity bound and no tenant column** (backpressure and tenant isolation not done); the PostgreSQL integration profile is wired into CI (8.3, `postgres` tag + PG16 service, adding a real-database matrix beyond local H2-dialect verification); lease contention yields boundedly after 3 retries (CONTENDED) but is not starvation-free; external queues (Redis/RabbitMQ) are not integrated.
- **Test baseline**: all 22 modules green across the repo is the regression contract (CI is the source of truth); stage narratives in `notes/` and public articles are **not** user contracts.
- **Learning project**: learning architecture by building a runtime. Read this page and [comparison.md](comparison.md) before production use.

## Related docs

- Getting started: [getting-started.md](getting-started.md)
- Modules: [modules.md](modules.md)
- Other libraries: [comparison.md](comparison.md)
