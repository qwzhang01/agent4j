# agent4j

[![CI](https://github.com/qwzhang01/agent4j/actions/workflows/ci.yml/badge.svg)](https://github.com/qwzhang01/agent4j/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://adoptium.net/)
[![Maven](https://img.shields.io/badge/Maven-0.1.0--SNAPSHOT-lightgrey.svg)](https://github.com/qwzhang01/agent4j)

> A persistent, observable, governable, hot-pluggable Java Agent Runtime.

可持久化、可观测、可治理、可热插拔的 Java Agent Runtime。JDK 17，**不依赖 Spring**。当前版本 `0.1.0-SNAPSHOT`，**尚未发布到 Maven Central**——请从源码构建。

这不是 LangChain4j / Spring AI 的替代品。它强调：断点恢复、工具治理、沙箱、轨迹导出，以及企业 / 酒馆 / 编码三个领域 Profile 共用同一套 Runtime。

## 5 分钟上手

```bash
git clone https://github.com/qwzhang01/agent4j.git
cd agent4j
./mvnw -B verify          # 1155 tests
./mvnw install -DskipTests
./mvnw -pl examples compile exec:java \
  -Dexec.mainClass=io.github.qwzhang01.agent.examples.MockAgentExample
```

不需要 API Key。最小代码：

```java
MockModelClient model = MockModelClient.scripted()
        .respondToolCalls(ToolCall.of("call_1", "get_current_time",
                new ObjectMapper().createObjectNode()))
        .respondText("The current time has been retrieved.");

InMemoryToolRegistry tools = new InMemoryToolRegistry();
tools.register(new CurrentTimeTool());

Agent agent = new SimpleAgent(new AgentConfig(
        "demo", "Use tools when needed.", model, tools, 10));

System.out.println(agent.run("What time is it?"));
```

换成真实模型：把 `MockModelClient` 换成 `OpenAiModelClient` 或 `AnthropicModelClient`，不必改 `SimpleAgent` / `Tool` / Loop。构造参数见 `agent-model` 的 javadoc。

完整步骤与依赖坐标：[docs/getting-started.md](docs/getting-started.md)

## 文档

| 文档 | 内容 |
|------|------|
| [快速开始](docs/getting-started.md) | 构建、Mock 跑通、换真模型、BOM |
| [核心概念](docs/concepts.md) | Agent / Tool / Loop / Workflow / Memory / 治理 |
| [模块一览](docs/modules.md) | 18 个库模块 + BOM 怎么引 |
| [对比](docs/comparison.md) | vs LangChain4j / Spring AI / AgentScope |
| [v1 边界](docs/limitations.md) | 有意不做的能力 |
| [示例](examples/README.md) | 32 个可运行 `main`，从 `MockAgentExample` 开始 |
| [贡献](CONTRIBUTING.md) | 怎么跑测试、PR 约定 |
| [安全](SECURITY.md) | 漏洞请走 GitHub Security Advisory |
| [发布](RELEASING.md) | 打 tag 与 Central（凭证未接） |

`notes/` 是 18 周学习笔记与设计蓝图，**不是 API 合同**。归档首页：[notes/v1-development-log.md](notes/v1-development-log.md)。

## 模块

```
seven-agent-bom      BOM，对齐全部库模块版本
agent-core           接口与数据（ChatMessage / ModelClient / Tool / Agent）
agent-model          Mock · OpenAI-compatible · Anthropic · 装饰器
agent-plugin         Java SPI 热插拔（无 JAR ClassLoader 多版本）
agent-sandbox        ClassLoader + 进程隔离（无 Docker / WASM）
agent-workflow       图引擎 · 7 种节点 · Checkpoint
agent-scheduler      定时 / 事件恢复 · 任务队列
agent-memory         工作 / 会话 / 长期记忆 + MemoryScope
agent-security       权限 · 审批 · 注入净化 · 审计
agent-mcp            MCP stdio + 进程内 A2A
agent-orchestrator   Supervisor / Worker 并行派发
agent-channel        身份 · 共享会话 · 接力 · Ambient
agent-product        YAML 定义 · 模板 · Prompt 版本 · Webhook
agent-trace-export   轨迹 S-A-O-R-D · JSONL · DPO
agent-enterprise     租户 · RAG · 成本账本 · 业务任务
agent-tavern         角色 · 世界 · 回合（游戏 Profile）
agent-coding         工作区 · 补丁 · 命令白名单 · 修复环
agent-observability  指标 · 五维预算 · 路由 · 评估 · 版本三元组
agent-spring-boot-starter  可选 Spring Boot 自动配置（唯一依赖 Spring 的模块）
examples             可运行示例（不发布）
```

最小接入：`agent-core` + `agent-model`。其余按需加。企业 / 酒馆 / 编码是同一 Runtime 上的三个 Profile，不是三套框架。

`0.1.0` 上 Central 之后：

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
```

现在请先 `./mvnw install`，再用 `0.1.0-SNAPSHOT`。

## 现状

| 项 | 事实 |
|----|------|
| 版本 | `0.1.0-SNAPSHOT`，SemVer，见 [CHANGELOG.md](CHANGELOG.md) |
| 测试 | 1155，`./mvnw -B test` 全绿 |
| CI | GitHub Actions，JDK 17 + 21 |
| 许可证 | [Apache-2.0](LICENSE) |
| 运行时依赖 | Jackson + SLF4J；**无 Spring** |
| 未做（有意） | JAR 多版本插件、Docker/WASM 沙箱、MCP SSE、真 Git、OTel、Mini VERL、LLM-as-judge |

## 贡献

欢迎 Issue 与 PR。请先读 [CONTRIBUTING.md](CONTRIBUTING.md)。一次 PR 只做一件事；行为变更必须带测试；不要削弱沙箱 / 权限 / 注入防御的 fail-closed 用例。

安全漏洞请走 [SECURITY.md](SECURITY.md)，不要开公开 Issue。

## License

Copyright 2026 qwzhang01. Licensed under the [Apache License 2.0](LICENSE).
