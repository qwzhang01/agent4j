# agent4j

[![CI](https://github.com/qwzhang01/agent4j/actions/workflows/ci.yml/badge.svg)](https://github.com/qwzhang01/agent4j/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.qwzhang01/seven-agent-bom?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.qwzhang01/seven-agent-bom)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net/)

A Java agent runtime for runs that pause, call tools with side effects, and have to survive a restart.

agent4j is not a chat-completion client and not a drop-in replacement for [LangChain4j](https://github.com/langchain4j/langchain4j) or [Spring AI](https://docs.spring.io/spring-ai/reference/). Those libraries are the right choice when you want to attach a model to an application. This library is for the next problem: a run that waits on a person, an event, or a timer, then continues from the same point, with every tool call permissioned and audited.

The core modules need JDK 17 and Jackson. They do not depend on Spring. Latest release: `0.1.5` on Maven Central. This is a `0.1.x` library. Read [v1 boundaries](docs/limitations.md) before you put it in production.

**中文**: [README.zh-CN.md](README.zh-CN.md)

## Install

Import the BOM, then add the modules you need. `agent-core` and `agent-model` are enough for a loop. Add `agent-security` for the governed path shown below.

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

<dependencies>
  <dependency>
    <groupId>io.github.qwzhang01</groupId>
    <artifactId>agent-core</artifactId>
  </dependency>
  <dependency>
    <groupId>io.github.qwzhang01</groupId>
    <artifactId>agent-model</artifactId>
  </dependency>
  <dependency>
    <groupId>io.github.qwzhang01</groupId>
    <artifactId>agent-security</artifactId>
  </dependency>
</dependencies>
```

Parent coordinates, if you need them: `io.github.qwzhang01:seven-agent:0.1.5`. Do not depend on the parent POM as a jar.

## Five-minute example

No API key. The model is scripted. `CurrentTimeTool` declares no side effects, so the secure builder lets it run. A tool that writes, deletes, or does not declare a side-effect level is rejected until you attach an approval policy. There is no silent allow.

```java
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.model.mock.CurrentTimeTool;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.security.SecureAgentBuilder;

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

To run the same example from a clone:

```bash
git clone https://github.com/qwzhang01/agent4j.git
cd agent4j
./mvnw -pl examples -am compile exec:java \
  -Dexec.mainClass=io.github.qwzhang01.agent.examples.MockAgentExample
```

`./mvnw -pl examples -am` builds the modules that example needs. `./mvnw -B verify` runs the full suite (JDK 17 and 21 on CI).

Swap the mock for a real endpoint without changing tools or the loop. `OpenAiModelClient` speaks the OpenAI HTTP API, including Azure OpenAI, Ollama, and other compatible servers.

```java
import io.github.qwzhang01.agent.model.openai.OpenAiModelClient;

OpenAiModelClient model = new OpenAiModelClient(System.getenv("OPENAI_API_KEY"));
// OpenAiModelClient model = new OpenAiModelClient(baseUrl, apiKey, modelName);
```

The ungoverned loop exists, and you have to ask for it by name: `UnsafeAgentBuilder` or `new SimpleAgent(...)`. Full setup, Spring Boot, and reasoning-model options: [Getting started](docs/getting-started.md).

## What it is built for

- **Pause and resume.** A run can stop for approval, an event, or a timer, persist graph state, and continue from that breakpoint. Completed steps are not run again.
- **Tools are governed.** Read-only tools run. Side-effect tools need an approval policy. With no policy, they are rejected. Allow, deny, execute, and sanitize decisions are audited.
- **A sandbox with a written threat model.** ClassLoader and process isolation for model-generated code. Each tier says what it stops and what it does not. See [limitations](docs/limitations.md).
- **Trajectories you can train on.** Each step is recorded as state, action, observation, reward, and done (S-A-O-R-D), exported as JSONL, with DPO preference pairs. That is an evaluation artifact, not an access log.
- **One runtime, three profiles.** An enterprise assistant, a roleplay character (`agent-tavern`), and a coding fix loop are configurations of the same loop and governance stack.

## How a run is put together

```mermaid
flowchart LR
  app[Your code] --> agent[Agent]
  agent --> loop[ReAct loop]
  loop --> model[ModelClient]
  loop --> gov[Permission, approval, sanitizer, audit]
  gov --> tools[Local tools, MCP, or sandbox]
  agent --> memory[Memory]
  graph[Workflow graph] --> agent
  graph --> ckpt[Checkpoint]
```

Start with `Agent.run`. Move to the workflow graph when one call is not enough: branches, human approval, parallel nodes, or a wait that outlives the process.

## Modules

Twenty library modules, plus a BOM and `examples` (22 reactor modules). `examples` is not published.

| Module | Use it for |
|--------|------------|
| `agent-core` | Messages, tools, the agent, the ReAct loop |
| `agent-model` | Mock, OpenAI-compatible, and Anthropic clients, plus retry / timeout / fallback decorators |
| `agent-security` | Permissions, approvals, injection sanitizer, audit |
| `agent-workflow` | Graph runtime, checkpoints, durable runs |
| `agent-scheduler` | Timer and event wake-ups, task queue |
| `agent-memory` | Working, session, and long-term memory |
| `agent-sandbox` | ClassLoader and process isolation |
| `agent-plugin` | SPI plugins and external JARs |
| `agent-mcp` | MCP over stdio and HTTP/SSE, plus HTTP agent-to-agent (A2A) |
| `agent-orchestrator` | Supervisor / worker dispatch |
| `agent-channel` | Identity, shared sessions, task handoff, proactive push |
| `agent-chat` | Multi-speaker rooms: who talks, what context they see, streaming |
| `agent-enterprise` | Tenants, RAG, cost ledger, business tasks |
| `agent-tavern` | Roleplay profile: characters, world, turns |
| `agent-coding` | Workspace, patches, command allowlist, bounded fix loop |
| `agent-product` | YAML agent definitions, templates, prompt versions, webhooks |
| `agent-trace-export` | Trajectory JSONL and DPO pairs |
| `agent-observability` | Metrics, budgets, routing, evaluation |
| `agent-otel-export` | Run events as OpenTelemetry spans. The SDK is not a core dependency |
| `agent-spring-boot-starter` | Optional Spring Boot autoconfiguration. The only module that depends on Spring |

`agent-tavern` is the roleplay profile. It is not a game you ship by itself. `agent-chat` is the room engine underneath a character conversation. Details and dependency direction: [Modules](docs/modules.md).

## Documentation

| Guide | Read it when |
|-------|----------------|
| [Getting started](docs/getting-started.md) | You are adding the dependency, switching to a real model, or using Spring Boot |
| [Concepts](docs/concepts.md) | You need the meaning of Agent, Loop, Memory, governance, and checkpoint |
| [Modules](docs/modules.md) | You are choosing artifacts |
| [Comparison](docs/comparison.md) | You are deciding against LangChain4j, Spring AI, or AgentScope |
| [Limitations](docs/limitations.md) | You need the security boundary and the list of things this release does not do |
| [Examples](examples/README.md) | You want a `main` to run. Start with `MockAgentExample` |
| [Contributing](CONTRIBUTING.md) | You are sending a pull request |
| [Security](SECURITY.md) | You found a sandbox escape, injection bug, or permission bypass |
| [Releasing](RELEASING.md) | You are cutting a version |

`notes/` is a design journal, mostly in Chinese. It is not an API contract. The contract is this README, `docs/`, the public API, and the tests.

## Status

| | |
|--|--|
| Version | `0.1.5` on Maven Central. SemVer. See [CHANGELOG.md](CHANGELOG.md) |
| Tests | `./mvnw -B verify` on JDK 17 and 21. CI is the source of truth |
| Runtime dependencies | Jackson and SLF4J. No Spring in the core |
| License | [Apache-2.0](LICENSE) |
| Not in this release | Docker and WASM sandboxes, plugin multi-version isolation, MCP Streamable HTTP, a real Git wrapper, a training loop, LLM-as-judge |

## Contributing

Issues and pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) first. One concern per pull request. Behavior changes need tests. Do not weaken the fail-closed tests for the sandbox, permissions, or injection defense.

Report vulnerabilities through [GitHub Security Advisories](https://github.com/qwzhang01/agent4j/security/advisories/new). Do not open a public issue for an exploitable bypass. See [SECURITY.md](SECURITY.md).

## License

Copyright 2026 qwzhang01. Licensed under the [Apache License 2.0](LICENSE).
