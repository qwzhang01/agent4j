# agent4j

[![CI](https://github.com/qwzhang01/agent4j/actions/workflows/ci.yml/badge.svg)](https://github.com/qwzhang01/agent4j/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://adoptium.net/)
[![Maven Central](https://img.shields.io/badge/Central-0.1.5-blue.svg)](https://central.sonatype.com/artifact/io.github.qwzhang01/seven-agent)

> A durable, observable, governable, hot-pluggable Java Agent Runtime.

A Java Agent Runtime built around **checkpoint-resume execution, tool governance, sandboxing, and trajectory export**, with enterprise / roleplay / coding running as profiles on top of one shared runtime. JDK 17, **zero Spring dependency** in core modules. Latest release on Maven Central: `0.1.5`.

This is **not** a replacement for LangChain4j or Spring AI. It invests in what those libraries treat as an afterthought: pausing a run at a human-approval breakpoint and resuming it later, running every tool call through permission / approval / sanitizer / audit gates, isolating untrusted code, and exporting full trajectories for evaluation and DPO training data. See [How it compares](docs/comparison.md).

**中文文档**: [README.zh-CN.md](README.zh-CN.md)

## Why another agent framework?

Most Java LLM libraries answer "how do I attach a model to my app?" agent4j answers a different question: **what happens when the agent runs for hours, calls tools with side effects, and must survive restarts?**

- **Durable by design.** A run can pause (waiting for human approval, an event, or a timer), persist its graph state, and resume from the exact breakpoint. No re-execution of completed steps.
- **Governed by default.** Read-only tools execute automatically; side-effect tools require approval — with zero configuration, they are rejected, not silently executed. Every allow / deny / execute / sanitize decision is audited.
- **Sandboxed execution.** ClassLoader and process-level sandboxes for running model-generated code, with an honest, documented threat model per tier.
- **Trajectory export.** Every step is captured as S-A-O-R-D trajectories (JSONL) and DPO preference pairs — evaluation inputs, not just access logs.
- **One runtime, multiple profiles.** Enterprise assistant, roleplay character engine, and coding agent share the same loop and governance stack. They are configurations, not forks.

## Quick start (5 minutes, no API key needed)

```bash
git clone https://github.com/qwzhang01/agent4j.git
cd agent4j
./mvnw -B verify          # full build + all tests
./mvnw install -DskipTests
./mvnw -pl examples compile exec:java \
  -Dexec.mainClass=io.github.qwzhang01.agent.examples.MockAgentExample
```

Minimal code using the secure assembly (deny-on-absence):

```java
MockModelClient model = MockModelClient.scripted()
        .respondToolCalls(ToolCall.of("call_1", "get_current_time",
                new ObjectMapper().createObjectNode()))
        .respondText("The current time has been retrieved.");

InMemoryToolRegistry tools = new InMemoryToolRegistry();
tools.register(new CurrentTimeTool());

Agent agent = SecureAgentBuilder.secure("demo", model, tools)
        .systemPrompt("Use tools when needed.")
        .maxSteps(10)
        .build();

System.out.println(agent.run("What time is it?"));
```

`CurrentTimeTool` declares `SideEffectLevel.NONE`, so the secure assembly lets it run automatically. Destructive tools are rejected by default (`autoReject`) — they will not execute with zero configuration. If you really want the ungoverned path, you must name it explicitly: `UnsafeAgentBuilder` or `new SimpleAgent(...)`.

Swapping in a real model means replacing `MockModelClient` with `OpenAiModelClient` or `AnthropicModelClient`. Your `Tool` definitions and the loop do not change. See the `agent-model` javadoc for constructor parameters.

Full walkthrough and Maven coordinates: [docs/getting-started.md](docs/getting-started.md)

## Documentation

| Doc | Contents |
|-----|----------|
| [Getting started](docs/getting-started.md) | Build, run with mocks, switch to a real model, BOM usage |
| [Core concepts](docs/concepts.md) | Agent / Tool / Loop / Workflow / Memory / Governance |
| [Modules](docs/modules.md) | The 19 library modules + BOM |
| [Comparison](docs/comparison.md) | vs LangChain4j / Spring AI / AgentScope |
| [v1 boundaries](docs/limitations.md) | What is intentionally not built |
| [Examples](examples/README.md) | 33 runnable `main` classes — start with `MockAgentExample` |
| [Contributing](CONTRIBUTING.md) | Running tests, PR conventions |
| [Security](SECURITY.md) | Report vulnerabilities via GitHub Security Advisory |
| [Releasing](RELEASING.md) | Tagging; `./mvnw -DskipTests deploy` to Central |

`notes/` contains 18 weeks of design notes and learning material — it is **not** an API contract. Start at [notes/README.md](notes/README.md) if you want the design rationale.

## Modules

```
seven-agent-bom      BOM aligning versions across all library modules
agent-core           Interfaces & data (ChatMessage / ModelClient / Tool / Agent)
agent-model          Mock · OpenAI-compatible · Anthropic clients + decorators
agent-plugin         Hot-pluggable Java SPI plugins (no multi-version JAR isolation)
agent-sandbox        ClassLoader + process isolation (no Docker / WASM)
agent-workflow       Graph engine · 7 node types · checkpoints
agent-scheduler      Timer / event wakeups · task queue
agent-memory         Working / session / long-term memory + MemoryScope
agent-security       Permissions · approvals · injection sanitizer · audit
agent-mcp            MCP stdio + A2A (in-process and HTTP, bidirectional)
agent-orchestrator   Supervisor / Worker parallel dispatch
agent-channel        Identity · shared sessions · task handoff · ambient push
agent-product        YAML agent definitions · templates · prompt versioning · webhooks
agent-trace-export   S-A-O-R-D trajectories · JSONL · DPO pairs
agent-enterprise     Tenants · RAG · cost ledger · business tasks
agent-tavern         Characters · world · turn engine (roleplay profile)
agent-chat           Room conversations: speaker selection · context · streaming
agent-coding         Workspace · patches · command allowlist · fix loop
agent-observability  Metrics · five-dimension budgets · routing · evaluation · versioning
agent-otel-export    RunEvent → OTel spans, thin shell (SDK stays out of core)
agent-spring-boot-starter  Optional Spring Boot autoconfiguration (the only Spring-dependent module)
examples             Runnable examples (not published)
```

Minimal adoption: `agent-core` + `agent-model`. Add the rest as needed. Enterprise / roleplay / coding are three profiles on the same runtime, not three frameworks.

Published on Maven Central (latest `0.1.5`) — align versions with the BOM:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.qwzhang01</groupId>
      <artifactId>seven-agent-bom</artifactId>
      <version>0.1.5</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

To track the development version: run `./mvnw install` first, then depend on the installed artifacts.

## Status

| Item | Fact |
|------|------|
| Version | Latest on Central: `0.1.5`, SemVer — see [CHANGELOG.md](CHANGELOG.md) |
| Tests | All 22 modules green (CI is the source of truth): `./mvnw -B test` |
| CI | GitHub Actions, JDK 17 + 21 |
| License | [Apache-2.0](LICENSE) |
| Runtime deps | Jackson + SLF4J; **no Spring** |
| Intentionally not built | Multi-version plugin isolation, Docker/WASM sandboxes (beyond adapter skeleton), MCP Streamable-HTTP, real Git, Mini VERL, LLM-as-judge. OTel lives in the separate `agent-otel-export` module (SDK never enters core) — that is a design decision, not a gap |

## Contributing

Issues and PRs are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md) first. One concern per PR; behavior changes require tests; never weaken the fail-closed cases for sandbox / permission / injection defense.

For security vulnerabilities, follow [SECURITY.md](SECURITY.md) — do not open a public issue.

## License

Copyright 2026 qwzhang01. Licensed under the [Apache License 2.0](LICENSE).
