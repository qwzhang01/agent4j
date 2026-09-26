# How it compares to other Java agent libraries

This project is **not** a replacement for LangChain4j, Spring AI, or AgentScope Java. Before choosing, ask yourself: do you want to "attach an LLM to a Java app", or do you want "an Agent Runtime that can pause, be audited, and be replayed"?

One-line difference: this project spends its effort on **durable execution, tool governance, sandboxing, trajectory export, and multiple profiles on one runtime** (enterprise / roleplay / coding). The Java ecosystem is typically thinner in durable execution and tool governance. The other half of the goal is **learning architecture by building a runtime**.

## LangChain4j

LangChain4j has broad coverage: model adapters, RAG components, memory, tool binding, and Spring integration are all mature, with a better community and docs. It fits the goal of wiring chat / RAG / tools into an existing service quickly.

This project does not compete on "who has more connectors". `ModelClient` in v1 has only Mock, OpenAI-compatible, and Anthropic. The difference is the execution layer: checkpoint resume, three-level permissions + approvals + audit, ClassLoader/process sandboxes, S-A-O-R-D trajectory / DPO export, and the enterprise / roleplay / coding profiles.

If you already run LangChain4j, there is no reason to switch. Evaluate this runtime when you need to treat a single run as a governable, resumable workflow.

## Spring AI

Spring AI is bound to the Spring ecosystem: autoconfiguration, Advisors, and low-friction observability and evaluation. If your system is already Spring Boot, it is the default choice.

This library's **core / model / runtime modules do not depend on Spring Framework**. `Agent` / `Tool` / the loop are plain Java. The optional `agent-spring-boot-starter` provides `application.yml` binding, a `ModelClient` bean, and an `AgentFactory` (it does not auto-create a single `Agent` bean, and ships no Actuator). v1 also has no OpenTelemetry SDK.

Choose Spring AI to "attach models quickly inside Spring". Look here when "Spring is optional, but governance and checkpoint resume are first-class".

## AgentScope Java

AgentScope (including its Java implementation) emphasizes multi-agent message passing, sessions, and research-oriented orchestration — closer to "building a conversation system / multi-agent experiments".

This project's orchestration (`agent-orchestrator`) is Supervisor / Worker parallel dispatch + in-process A2A, not a general message bus. The channel layer solves identity intersection, shared sessions, task handoff, and ambient push. MCP in v1 is stdio + HTTP/SSE transport.

Both "can run multiple agents", but this project puts multi-agent on top of a governable runtime rather than centering on a conversation framework.

## When you should not use this project

- You only need a chat-completion wrapper, or just Spring autoconfiguration.
- You need rich ready-made RAG / vector stores / evaluation platforms and don't want to assemble your own.
- You need Docker/WASM sandboxes, MCP SSE, HTTP A2A, real Git, OTel, a training loop — these are [v1 non-goals](limitations.md).
- Before the portal publish completes, you can only `mvn install` from source; there is no stable Central release.

## When it is worth a try

- Tools must pass permissions / approvals / audit, and failures must leave a trace.
- Flows pause (humans, events, timers) and must resume with the original state.
- You need trajectory JSONL / DPO, not just access logs.
- The same loop + governance must serve an enterprise assistant, game turns, and a coding fix loop.
- You want to walk the boundaries of an Agent Runtime in Java (this repo is also a learning project).
