# Getting started

agent4j is a Java 17 agent runtime. A run can pause, call tools under a permission policy, and resume from a checkpoint. Repository: [github.com/qwzhang01/agent4j](https://github.com/qwzhang01/agent4j). Current release: `io.github.qwzhang01` / `seven-agent` / `0.1.5` on Maven Central.

You can finish the first example with a scripted model. No API key.

## Requirements

- JDK 17 or newer
- Maven 3.9+, or the wrapper in this repo (`./mvnw`)
- Jackson on the runtime classpath (pulled in by the modules)

`agent-core`, `agent-model`, and the other runtime modules do not depend on Spring. The parent POM is a standalone aggregator, not `spring-boot-starter-parent`. Spring appears only in the optional `agent-spring-boot-starter`.

## Add it to a project

Import the BOM so every module stays on `0.1.5`, then declare the artifacts you use.

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

Add `agent-workflow`, `agent-memory`, and the rest as you need them. The map is in [modules.md](modules.md).

## Minimal program

This is the secure path, the one the quick start uses. Unknown tools are rejected. A tool with side effects is denied unless an approval policy says otherwise.

```java
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.model.mock.CurrentTimeTool;
import io.github.qwzhang01.agent.model.mock.EchoTool;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.security.SecureAgentBuilder;

MockModelClient modelClient = MockModelClient.scripted()
        .respondToolCalls(ToolCall.of("call_1", "get_current_time",
                new ObjectMapper().createObjectNode()))
        .respondText("The current time has been retrieved.");

InMemoryToolRegistry registry = new InMemoryToolRegistry();
registry.register(new CurrentTimeTool());
registry.register(new EchoTool());

Agent agent = SecureAgentBuilder.secure("mock-agent-v1", modelClient, registry)
        .systemPrompt("You are a helpful assistant. Use tools when needed.")
        .maxSteps(10)
        .build();

String response = agent.run("What time is it now?");
```

| Type | Package |
|------|---------|
| `Agent`, `AgentConfig`, `SimpleAgent` | `io.github.qwzhang01.agent.core.agent` |
| `SecureAgentBuilder` | `io.github.qwzhang01.agent.security` |
| `InMemoryToolRegistry` | `io.github.qwzhang01.agent.core.tool` |
| `ToolCall` | `io.github.qwzhang01.agent.core.model` |
| `MockModelClient`, `CurrentTimeTool`, `EchoTool` | `io.github.qwzhang01.agent.model.mock` |

`maxSteps` stops a tool loop from running forever. The count is accumulated for the conversation, not reset on each `run`. Tools marked `NONE` or `READ_ONLY` execute on their own. `SIDE_EFFECT`, `DESTRUCTIVE`, and `UNKNOWN` require approval and are rejected when you have not configured one.

To skip governance you must say so: `UnsafeAgentBuilder`, or `new SimpleAgent(config)`.

## Use a real model

`SecureAgentBuilder` depends on the `ModelClient` interface. Replace `MockModelClient` with `OpenAiModelClient` or `AnthropicModelClient`. Tools and the loop stay as they are.

```java
import io.github.qwzhang01.agent.model.openai.OpenAiModelClient;

OpenAiModelClient model = new OpenAiModelClient(System.getenv("OPENAI_API_KEY"));
```

That constructor calls `https://api.openai.com/v1` with model `gpt-4o-mini`. For another OpenAI-compatible server:

```java
import java.time.Duration;

import io.github.qwzhang01.agent.model.openai.OpenAiModelClient;

OpenAiModelClient model = new OpenAiModelClient(
        "https://your-host/v1",
        apiKey,
        "your-model",
        Duration.ofSeconds(60));
```

The client uses `java.net.http.HttpClient`. Timeouts and extra constructors are documented on `OpenAiModelClient`.

### Reasoning models

Some models return a chain of thought beside the answer (`reasoning_content`, `reasoning`, or `thinking` — the field name depends on the vendor). The client keeps that channel out of the text you show the user.

Two separate controls:

| Control | What it does | Where |
|---------|----------------|-------|
| `ReasoningConfig` | Cross-vendor intent: `auto`, `enabled`, or `disabled`, plus an optional `effort` | `ModelRequest.reasoning()`, or a client default |
| `extraBody` | Vendor-specific JSON, merged into the request body as-is | Client constructor, or `agent4j.model.extra-body` in Spring |

The framework does not keep a list of vendors. Responses are read tolerantly. Request fields the core model does not know go through `extraBody`. If a server ignores a reasoning toggle, the client logs a warning. It does not drop the setting quietly.

Production clients are usually a stack of decorators: `RetryModelClient`, `TimeoutModelClient`, `FallbackModelClient`, `StructuredOutputModelClient`. See `DecoratedModelClientExample`.

## Spring Boot

The starter does not pull Spring into `agent-core`. A Spring Boot 3.2 application on Java 17 adds:

```xml
<dependency>
  <groupId>io.github.qwzhang01</groupId>
  <artifactId>agent-spring-boot-starter</artifactId>
  <version>0.1.5</version>
</dependency>
```

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
    reasoning:          # cross-vendor intent only
      mode: disabled    # auto | enabled | disabled
      effort: medium    # optional: low | medium | high
    extra-body:         # vendor-specific fields, merged as-is
      thinking:
        budget_tokens: 8000
  retry:
    enabled: false
    max-attempts: 3
  call-timeout:
    enabled: false
    duration: 30s
```

`reasoning` is only the shared intent. Vendor fields such as Anthropic `budget_tokens` belong in `extra-body`. On a name clash, the standard field wins. `extra-body` cannot overwrite the protocol.

Inject `ModelClient` and `AgentFactory`. The starter does not publish one global `Agent` bean, because each persona has its own system prompt. Build an agent per role:

```java
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.spring.AgentFactory;

import java.util.function.Consumer;

import org.springframework.stereotype.Service;

@Service
public class ChatService {
    private final AgentFactory agentFactory;

    public ChatService(AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    public String reply(String characterPrompt, String userInput) {
        Agent agent = agentFactory.create("story-character", characterPrompt);
        return agent.run(userInput);
    }

    public void replyStream(String characterPrompt, String userInput,
                            Consumer<AgentEvent> listener) {
        Agent agent = agentFactory.create("story-character", characterPrompt);
        agent.stream(userInput, listener);
    }
}
```

Bean names are `modelClient` and `agentFactory`. An existing `ModelClient` bean is left alone. `agent4j.enabled=false` turns autoconfiguration off. The default profile is the secure assembly. The starter does not depend on `agent-chat`.

## Build from source

```bash
git clone https://github.com/qwzhang01/agent4j.git
cd agent4j
./mvnw -B verify
```

Run the mock example without installing into the local repository first. `-am` builds the modules it depends on:

```bash
./mvnw -pl examples -am compile exec:java \
  -Dexec.mainClass=io.github.qwzhang01.agent.examples.MockAgentExample
```

You can also run `MockAgentExample.main` from an IDE after a reactor import. To consume a development build from another project, `./mvnw install` and depend on the installed version.

## Next

| You want | Go to |
|----------|--------|
| What Agent, Loop, Memory, and governance mean | [concepts.md](concepts.md) |
| Which module depends on which | [modules.md](modules.md) |
| LangChain4j, Spring AI, AgentScope | [comparison.md](comparison.md) |
| What this release refuses to claim | [limitations.md](limitations.md) |
| A `main` for each feature | [../examples/README.md](../examples/README.md) |
