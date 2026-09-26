# Boundaries of 0.1.5

Released 2026-09-17. This page lists what the release does not do, and what the sandbox actually stops. `notes/` and the size of the repository are not a feature list.

Coordinates: `io.github.qwzhang01:seven-agent:0.1.5` on Maven Central. `examples` is not in the published bundle. Usage: [getting-started.md](getting-started.md).

## In short

- Runtime modules do not depend on Spring. Only `agent-spring-boot-starter` does.
- Plugins are in-process. They are not a multi-version or module-layer sandbox.
- The sandbox stops at a process with an in-guest guard. Docker, gVisor, and WASM are not implemented. Do not run hostile code on a high-risk path and call that isolation.
- MCP is stdio and HTTP/SSE. Streamable HTTP is not implemented. HTTP agent-to-agent exists. Card signing with PKI does not.
- There is no Git client, no training loop, and no LLM-as-judge.
- OpenTelemetry spans are an optional module. The SDK never enters the core.
- Secret masking, memory governance, and masked trajectory export are opt-in. The default path does not turn them on.

## No Spring on the runtime classpath

`agent-core`, `agent-model`, and the other runtime modules do not pull in Spring Framework. The parent POM is a standalone aggregator.

`agent-spring-boot-starter` is the only Spring module. It reads `agent4j.*`, creates a `ModelClient`, and provides an `AgentFactory`. There is no Actuator integration.

## Not in this release

These are non-goals for `0.1.x`, not a backlog that the README forgot to mention.

| You might expect | What you actually get |
|------------------|------------------------|
| Several versions of one plugin, isolated by module layer | SPI load, unload, and reload. An external JAR can be loaded with a SHA-256 check, its own class loader, and a manifest of host permissions. Plugins still run with ordinary in-process JVM rights. Registration is isolated. Code execution is not |
| Docker, gVisor, or WASM | `ClassLoaderSandbox` and `ProcessSandbox`. The Docker tier is a placeholder that reports zero guarantees even when a daemon is present |
| MCP Streamable HTTP, a full OAuth client, resources and prompts | An MCP client for stdio and for the 2024-11-05 HTTP/SSE dialect. Three trust levels (`TRUSTED`, `RESTRICTED`, `UNTRUSTED`), default deny, an allowlist, host auth adapters, and schema checks at the boundary |
| Signed agent cards, certified interop with someone else's A2A server | Bidirectional HTTP A2A: client and server, task polling, SSE, webhooks, resume on input required. Inbound sanitizing, an optional bearer check, HMAC-SHA256 on webhook pushes, a five-minute window, and a nonce cache. Task storage is pluggable and in-memory by default. Interop tests are loopback plus a JDK HTTP server playing the other dialect. No PKI |
| Git | `agent-coding` is a workspace, patches, a command allowlist, and a bounded fix loop |
| OpenTelemetry inside the core | `agent-otel-export` maps run events to spans. The API is a compile dependency of that module. The SDK is test-scoped. `agent-observability` keeps its own metrics, budgets, routing, and evaluation. Trace context is not propagated across HTTP A2A servers, and there is no Micrometer adapter |
| A training loop (VERL or otherwise) | `agent-trace-export` writes trajectory JSONL and DPO pairs. Training stays outside the library |
| LLM-as-judge | Evaluation is rules and failure-sample regression sets |

## Sandbox

The strongest tier that runs is `PROCESS`. `ProcessSandbox` is a defense against accidents and low-effort attacks. The in-guest guard has blocked the red-team cases in the suite. It is a `SecurityManager` inside the guest, and JDK internals can in theory bypass that. It is not a security boundary against a determined attacker.

### By tier

| Tier | Status | Stops | Does not stop |
|------|--------|-------|----------------|
| `CLASSLOADER` | Shipped (`ClassLoaderSandbox`) | Accidental calls into dangerous APIs | Reflection, `Unsafe`, JNI |
| `PROCESS` | Shipped (`ProcessSandbox`, guest guard) | The above, plus guest file, network, and process access, and output truncation | A bypass of the guard through JDK internals. The host OS user is not isolated |
| `DOCKER` | Not shipped | Would add namespaces and cgroups | Kernel exploits |
| gVisor / microVM | Not shipped | Would shrink the kernel attack surface | Side channels |
| `WASM` | Not shipped | Would remove syscalls | Bugs in a WASM runtime |

`DockerDaemonProbeIT` locks the Docker row. The suite can see whether a daemon exists. Even when one does, `SandboxReport` still promises nothing for that tier. The missing adapter fails loudly. It is not a feature you can turn on by accident.

### What the process tier enforces

- An in-guest `SandboxGuard` (`SecurityManager`, installed in two phases, with no calls in guest source). Reads, writes, and deletes outside the workspace are denied. `java.home` is read-only. Process execution is denied. Socket permissions are denied. `exitVM` and installing a second `SecurityManager` follow an allowlist.
- Environment allowlist. By default only `PATH`, `TMPDIR`, `LANG`, `TZ`, `LC_ALL`, and `LC_CTYPE` are inherited. `HOME` is not. `ENV_INHERIT_ALL=["*"]` opts back in.
- Each output stream is truncated at 1 MB by default, and marked truncated. A timed-out process tree is killed. Temp directories are removed in `finally`.
- `UNRESTRICTED` installs no guard. It is for trusted debugging. Tests record that case as not blocked.

### macOS and Linux

On a developer Mac, the guest guard is the whole boundary. The process runs as your user. There is no UID or GID isolation. On Linux the process tier is the same guard. Namespaces, cgroups, and a separate UID wait on a Docker adapter that does not exist yet.

Do not run adversarial code on a high-risk production path and treat the process tier as isolation. `TierLimits.forRisk(risk, multiTenant)` returns the limits for a risk tier. `requiresApproval=true` on the adversarial tier means the path needs a person, or a stronger sandbox.

## Sensitive data

Masking, audit, and identity binding exist. The default assembly does not enable them. Turn them on before you connect production data.

What you can wire:

- `SecretMasker` — regex rules. Built-in presets cover API keys, AWS keys, JWTs, bearer tokens, email, phone numbers, and bank cards, plus rules you add. The placeholder is `[REDACTED:<rule-name>]`. `mask` does not throw.
- `RedactionPolicy` — four flags (`RAW`, `SUMMARY`, `HASH`, `MASKED`) and four factories: raw only (legacy), masked only, raw plus masked, hash only.
- `MemoryGovernance` — a scope allowlist taken from `RunContext` (the caller cannot forge it), purpose checks, read and write audit, and deletion that can be followed.
- `TrajectoryCodec(masker)` — masking on export. A null masker keeps the old byte-for-byte behavior.

Honest limits:

- Nothing is masked until you opt in. That is the compatible path for a single-tenant local run. A multi-tenant deployment has to wire the masker, memory governance, and a masked codec.
- Encryption at rest is yours. There is no encrypted store in the library. Use the database or the disk. Export can avoid plaintext past the host boundary once a masker is installed.
- `metadata.last_error` is exported unmasked. The text is the exception class and message. Masking it would hide the failure. That is a known gap.
- Retention is a hard delete plus a TTL (`expireAt`). There is no anonymization path.
- Provenance has no separate model-version field. It rides on the actor string.
- Deleting memory does not, by itself, delete copies already written into trace and trajectory exports.

## Other limits

- **Models.** Mock, OpenAI-compatible, Anthropic. No catalog of vendor SDKs.
- **Memory on Postgres.** `PgMemoryStore` takes a `DataSource` you supply. No pool, no ORM, no migrations managed by the framework. The schema is created idempotently. `InMemoryMemoryStore` is for one machine and for tests.
- **Checkpoints.** In-memory, file, or JDBC. `JdbcCheckpointStore` uses the same codec and can share the blackboard across instances. There is no hosted workflow product.
- **More than one process.** Cancel, resume, and approval callbacks can cross instances by watching database rows, with heartbeat renewal. The JDBC queue has no capacity cap and no tenant column, so backpressure and tenant isolation are not there. A PostgreSQL integration test is opt-in (tag `postgres`). Lease contention gives up after three retries. It is not starvation-free. Redis and RabbitMQ are not integrated.
- **Tests.** A green `./mvnw -B verify` across all 22 reactor modules is the regression contract. CI is the source of truth. Essays in `notes/` are not.
- **A learning project.** Part of the point is to see the edges of a runtime. Read this page and [comparison.md](comparison.md) before you depend on it in production.

## Related

- [Getting started](getting-started.md)
- [Modules](modules.md)
- [Comparison](comparison.md)
