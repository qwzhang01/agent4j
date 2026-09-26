# How it compares

agent4j is not a replacement for LangChain4j, Spring AI, or AgentScope Java.

Ask one question before you choose. Do you need to attach a model to a Java application, or do you need a run that can pause, be audited, and be replayed?

This project spends its effort on durable execution, tool governance, sandboxing, trajectory export, and three profiles on one runtime: an enterprise assistant, roleplay, and a coding loop. It is also a way to learn that architecture by reading a Java implementation. Connector count is not the goal.

## LangChain4j

LangChain4j covers model adapters, RAG, memory, tool binding, and Spring integration, with a larger community and more documentation. Use it when you want chat, retrieval, and tools in an existing service quickly.

agent4j does not try to win on connectors. `ModelClient` in this release is a mock, an OpenAI-compatible client, and an Anthropic client. The difference is the execution layer: checkpoint resume, three permission levels plus approval and audit, ClassLoader and process sandboxes, trajectory and DPO export, and the three profiles above.

If LangChain4j already runs your application, stay there. Look at this runtime when one run has to be a governable workflow.

## Spring AI

Spring AI belongs to the Spring ecosystem: autoconfiguration, advisors, and a short path to observability and evaluation. If the system is already Spring Boot and the job is "call a model", Spring AI is the default.

Core, model, and runtime modules here do not depend on Spring. `Agent`, `Tool`, and the loop are plain Java. The optional `agent-spring-boot-starter` binds `application.yml`, exposes a `ModelClient` bean, and exposes an `AgentFactory`. It does not create a single `Agent` bean, and it does not ship Actuator. OpenTelemetry is a separate module (`agent-otel-export`). The SDK is not a dependency of the core.

Choose Spring AI to attach models inside Spring. Choose agent4j when Spring is optional and governance plus checkpoint resume are the point.

## AgentScope Java

AgentScope, including the Java port, is built around multi-agent messages, sessions, and research-style orchestration. It is closer to a conversation system and to multi-agent experiments.

`agent-orchestrator` is supervisor / worker dispatch plus in-process agent-to-agent calls. It is not a general message bus. `agent-mcp` adds HTTP A2A (card discovery, `message/send`, task status, server-sent events, webhooks). The channel layer handles shared identity, shared sessions, task handoff, and proactive push. MCP transport in this release is stdio and HTTP/SSE, not Streamable HTTP.

Both projects can run more than one agent. This one puts that on a governed runtime. It is not organized as a conversation framework first.

## Skip this project when

- You only need a chat-completion wrapper, or Spring autoconfiguration and nothing else.
- You need a ready-made catalog of vector stores, RAG pipelines, and evaluation products.
- You need a Docker or WASM sandbox, MCP Streamable HTTP, a real Git client, a training loop, or an LLM-as-judge. Those are [out of scope for this release](limitations.md).

`0.1.5` is on Maven Central. You do not have to build from source to try the library.

## It is worth a try when

- Tool calls must pass permission, approval, and audit, and a denial has to leave a record.
- A flow pauses for a person, an event, or a timer, and must resume with the same state.
- You need trajectory JSONL or DPO pairs, not only access logs.
- The same loop and the same governance have to serve an assistant, a game turn, and a coding fix loop.
- You want to read an Agent runtime in Java, including the parts it refuses to claim.
