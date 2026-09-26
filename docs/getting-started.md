# Getting Started

In one sentence: a **durable, observable, governable, hot-pluggable** Java Agent Runtime.

Repository: [github.com/qwzhang01/agent4j](https://github.com/qwzhang01/agent4j)
Coordinates: `io.github.qwzhang01` / `seven-agent` / `0.1.5` (latest on Central)
Releases are published via `./mvnw -DskipTests deploy`.

`notes/` contains learning notes — **not a user contract**. External behavior is defined by the docs in this directory, the public API, and the tests.

## Prerequisites

- **JDK 17+**
- Maven 3.9+, or the bundled wrapper `./mvnw`
- Runtime modules (`agent-core` / `agent-model`, etc.) **do not depend on Spring Framework** (standalone Maven parent, not `spring-boot-starter-parent`). The optional module `agent-spring-boot-starter` is the only place Spring appears.

You can run the first example without a real LLM.

## Build from source

```bash
git clone https://github.com/qwzhang01/agent4j.git
cd agent4j

# Full build + all tests
./mvnw -B verify
# or
mvn -B verify
```

Before running examples for the first time, install the modules into your local Maven repository:

```bash
mvn install -DskipTests
mvn -pl examples compile exec:java \
  -Dexec.mainClass=io.github.qwzhang01.agent.examples.MockAgentExample
```

You can also run `MockAgentExample.main` directly from your IDE.

## Minimal runnable code

The snippet below matches `MockAgentExample` in `examples`: a scripted `MockModelClient` drives one tool call, then produces the final text. **The 5-minute quick start uses the secure path** (unknown tools rejected, side-effect tools denied on absence).

```java
MockModelClient modelClient = MockModelClient.scripted()
    .respondToolCalls(ToolCall.of("call_1", "get_current_time",
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()))
    .respondText("...");

InMemoryToolRegistry registry = new InMemoryToolRegistry();
registry.register(new CurrentTimeTool());
registry.register(new EchoTool());

Agent agent = SecureAgentBuilder.secure("mock-agent-v1", modelClient, registry)
    .systemPrompt("You are a helpful assistant. Use tools when needed to answer questions.")
    .maxSteps(10)
    .build();
String response = agent.run("What time is it now?");
```

Packages:

| Type | Package |
|------|---------|
| `Agent` / `AgentConfig` / `SimpleAgent` | `io.github.qwzhang01.agent.core.agent` |
| `SecureAgentBuilder` | `io.github.qwzhang01.agent.security` |
| `InMemoryToolRegistry` | `io.github.qwzhang01.agent.core.tool` |
| `ToolCall` | `io.github.qwzhang01.agent.core.model` |
| `MockModelClient` / `CurrentTimeTool` / `EchoTool` | `io.github.qwzhang01.agent.model.mock` |

`maxSteps` is a safety bound that prevents infinite tool loops. Read-only tools (`NONE` / `READ_ONLY`) execute automatically; `SIDE_EFFECT` / `DESTRUCTIVE` / `UNKNOWN` require approval by default and are rejected with zero configuration. The ungoverned path requires `UnsafeAgentBuilder` or a direct `new SimpleAgent(config)`.

## Switching to a real model (conceptually)

`SecureAgentBuilder` / `AgentConfig` only know the `ModelClient` interface. Replace `MockModelClient` with `OpenAiModelClient` (or `AnthropicModelClient`) — **your `Tool` definitions and the loop do not change**.

`OpenAiModelClient` uses the Java `HttpClient` and works with OpenAI, Azure OpenAI, Ollama, and any OpenAI-compatible endpoint, including Volcano Ark's compatible API. Constructor parameters (`apiKey` / `baseUrl` / `defaultModel` / timeout) are documented in the `OpenAiModelClient` javadoc and tests in `agent-model` — deliberately not repeated here to avoid staleness.

### Reasoning models

Reasoning models such as Volcano Ark `doubao-seed-*`, DeepSeek-R1, and Qwen3 thinking mode place their chain of thought in a field separate from `content` (`reasoning_content` / `reasoning` / `thinking` — vendors differ). The client **always** keeps this channel out of your answer.

Two orthogonal knobs on the request side:

| Knob | Purpose | Where |
|---|---|---|
| `ReasoningConfig` | Cross-vendor intent: `auto` / `enabled` / `disabled` + `effort` | `ModelRequest.reasoning()` or a client default |
| `extraBody` | Vendor-specific fields, merged verbatim into the request body | Client constructor / `agent4j.model.extra-body` |

The framework does not enumerate vendors: the response side relies on tolerant reading (see `ChatDelta`), the request side on the escape hatch. This means a new compatible endpoint requires no framework changes. If a vendor does not support a requested toggle, a warning is logged rather than silently dropping your intent.

Production setups typically stack decorators: `RetryModelClient`, `TimeoutModelClient`, `FallbackModelClient`, `StructuredOutputModelClient`. See `DecoratedModelClientExample`.

## Using it in your own project

The latest version on Central is `0.1.5` — reference it directly.

Align versions with the BOM:

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
</dependencies>
```

Add `agent-workflow`, `agent-security`, etc. as needed — see [modules.md](modules.md).

## Spring Boot

The optional starter does **not** pull Spring into core. Spring Boot 3.2 / Java 17 apps like Moonlit just add the dependency:

```xml
<dependency>
  <groupId>io.github.qwzhang01</groupId>
  <artifactId>agent-spring-boot-starter</artifactId>
  <version>0.1.5</version>
</dependency>
```

`0.1.5` is on Central — reference it directly.

`application.yml`:

```yaml
agent4j:
  enabled: true
  model:
    provider: openai   # openai | mock
    api-key: ${OPENAI_API_KEY:}
    base-url: https://api.openai.com/v1
    name: gpt-4o-mini
    timeout: 60s
    reasoning:          # reasoning models: express "intent" only; wire formats are translated per vendor
      mode: disabled    # auto | enabled | disabled
      effort: medium    # optional: low | medium | high (only some vendors support it)
    extra-body:         # escape hatch: vendor-specific fields, merged verbatim into the request body
      thinking:
        budget_tokens: 8000
  retry:
    enabled: false
    max-attempts: 3
  call-timeout:
    enabled: false
    duration: 30s
```

`reasoning` holds only **cross-vendor** intent. Vendor-specific knobs (Anthropic's `budget_tokens`, OpenAI's `include`, etc.) go through `extra-body` — don't stuff them into the core model, so the next new vendor requires no framework changes. On conflict with a standard field, the standard field wins; `extra-body` cannot break the protocol.

Inject `ModelClient` and `AgentFactory`. **Do not** expect a global `Agent` bean — each character has its own system prompt; create agents per role with the factory:

```java
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.spring.AgentFactory;

import java.util.function.Consumer;

@Service
public class ChatService {
    private final AgentFactory agentFactory;

    public ChatService(AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    public String reply(String characterPrompt, String userInput) {
        Agent agent = agentFactory.create("moonlit-character", characterPrompt);
        return agent.run(userInput);
    }

    public void replyStream(String characterPrompt, String userInput,
                            Consumer<AgentEvent> listener) {
        Agent agent = agentFactory.create("moonlit-character", characterPrompt);
        agent.stream(userInput, listener);
    }
}
```

Bean names: `modelClient`, `agentFactory`. The starter does not override an existing `ModelClient` bean. `agent4j.enabled=false` disables the autoconfiguration.

## Next steps

| What you want | Where |
|---------------|-------|
| Agent / Loop / Memory / Governance | [concepts.md](concepts.md) |
| How modules split and depend on each other | [modules.md](modules.md) |
| Differences vs LangChain4j / Spring AI | [comparison.md](comparison.md) |
| What v1 explicitly does not do | [limitations.md](limitations.md) |
| 33 runnable examples | [../examples/README.md](../examples/README.md) |
