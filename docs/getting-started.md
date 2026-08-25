# 快速开始

一句话：一个**可持久化、可观测、可治理、可热插拔**的 Java Agent Runtime。

仓库：[github.com/qwzhang01/agent4j](https://github.com/qwzhang01/agent4j)  
坐标：`io.github.qwzhang01` / `seven-agent` / `0.1.0-SNAPSHOT`  
当前**未上 Maven Central**，请从源码构建。

`notes/` 是学习笔记，**不是用户契约**。对外行为以本目录文档、公开 API 与测试为准。

## 前置条件

- **JDK 17+**
- Maven 3.9+，或直接用仓库自带的 `./mvnw`
- 运行时模块（`agent-core` / `agent-model` 等）**不依赖 Spring Framework**（独立 Maven parent，不是 `spring-boot-starter-parent`）。可选模块 `agent-spring-boot-starter` 是唯一依赖 Spring 的地方。

不需要真实 LLM 即可跑通第一个例子。

## 从源码构建

```bash
git clone https://github.com/qwzhang01/agent4j.git
cd agent4j

# 全量编译 + 1186 个测试
./mvnw -B verify
# 或
mvn -B verify
```

首次跑示例前，先把模块装进本地 Maven 仓库：

```bash
mvn install -DskipTests
mvn -pl examples compile exec:java \
  -Dexec.mainClass=io.github.qwzhang01.agent.examples.MockAgentExample
```

IDE 里直接运行 `MockAgentExample.main` 也可以。

## 最小可运行代码

下面这段与 `examples` 里的 `MockAgentExample` 一致：用脚本化 `MockModelClient` 驱动一次 tool call，再给出最终文本。

```java
MockModelClient modelClient = MockModelClient.scripted()
    .respondToolCalls(ToolCall.of("call_1", "get_current_time",
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()))
    .respondText("...");

InMemoryToolRegistry registry = new InMemoryToolRegistry();
registry.register(new CurrentTimeTool());
registry.register(new EchoTool());

AgentConfig config = new AgentConfig(
    "mock-agent-v1",
    "You are a helpful assistant. Use tools when needed to answer questions.",
    modelClient,
    registry,
    10
);
Agent agent = new SimpleAgent(config);
String response = agent.run("What time is it now?");
```

包名：

| 类型 | 包 |
|------|-----|
| `Agent` / `AgentConfig` / `SimpleAgent` | `io.github.qwzhang01.agent.core.agent` |
| `InMemoryToolRegistry` | `io.github.qwzhang01.agent.core.tool` |
| `ToolCall` | `io.github.qwzhang01.agent.core.model` |
| `MockModelClient` / `CurrentTimeTool` / `EchoTool` | `io.github.qwzhang01.agent.model.mock` |

`AgentConfig` 的第五个参数是 `maxSteps`（安全上限，防止无限 tool 循环）。

## 换成真实模型（概念上）

`AgentConfig` 只认 `ModelClient`。把 `MockModelClient` 换成 `OpenAiModelClient`（或 `AnthropicModelClient`）即可，**不必改 `SimpleAgent` / `Tool` / Loop**。

`OpenAiModelClient` 走 Java `HttpClient`，兼容 OpenAI、Azure OpenAI、Ollama 等 OpenAI-compatible 端点，以及火山方舟的兼容接口。构造参数（`apiKey` / `baseUrl` / `defaultModel` / timeout）以 `agent-model` 里 `OpenAiModelClient` 的 javadoc 与测试为准，这里不展开以免过期。

生产环境通常还会叠装饰器：`RetryModelClient`、`TimeoutModelClient`、`FallbackModelClient`、`StructuredOutputModelClient`。见 `DecoratedModelClientExample`。

## 接到你自己的项目

当前版本是 `0.1.0-SNAPSHOT`，**尚未发布到 Maven Central**。在本仓库执行 `mvn install` 后，下游项目用相同 `groupId` / `version` 引用模块。

`0.1.0` 上 Central 之后，用 BOM 对齐版本：

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.qwzhang01</groupId>
      <artifactId>seven-agent-bom</artifactId>
      <version>0.1.0</version>
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

按需再加 `agent-workflow`、`agent-security` 等，见 [modules.md](modules.md)。

## Spring Boot

可选 starter，**不**把 Spring 引进 core。Moonlit 这类 Spring Boot 3.2 / Java 17 应用加依赖即可：

```xml
<dependency>
  <groupId>io.github.qwzhang01</groupId>
  <artifactId>agent-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

先在本仓库执行 `./mvnw install`，再编译下游。

`application.yml`：

```yaml
agent4j:
  enabled: true
  model:
    provider: openai   # openai | mock
    api-key: ${OPENAI_API_KEY:}
    base-url: https://api.openai.com/v1
    name: gpt-4o-mini
    timeout: 60s
  retry:
    enabled: false
    max-attempts: 3
  call-timeout:
    enabled: false
    duration: 30s
```

注入 `ModelClient` 与 `AgentFactory`。**不要**指望有一个全局 `Agent` bean——角色各有 system prompt，用工厂按角色创建：

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

Bean 名：`modelClient`、`agentFactory`。已有 `ModelClient` bean 时 starter 不会覆盖。`agent4j.enabled=false` 关闭自动配置。

## 下一步

| 想看什么 | 去哪 |
|----------|------|
| Agent / Loop / Memory / 治理 | [concepts.md](concepts.md) |
| 模块怎么拆、依赖谁 | [modules.md](modules.md) |
| 和 LangChain4j / Spring AI 的差异 | [comparison.md](comparison.md) |
| v1 明确不做的事 | [limitations.md](limitations.md) |
| 33 个可运行示例 | [../examples/README.md](../examples/README.md) |
