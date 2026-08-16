# Java Agent Framework

> A persistent, observable, governable, hot-pluggable Java Agent Runtime.
>
> **Learning project**: 通过构建一个 Java Agent Runtime，掌握 Agent 架构设计的全貌。

## 当前阶段：Stage 4 ✅ 已完成

### 已完成

- [x] Maven 多模块项目骨架（agent-core / agent-model / agent-plugin / agent-sandbox / examples）
- [x] 核心数据结构：`ChatMessage` / `ModelRequest` / `ModelResponse` / `ToolCall` / `StreamEvent`
- [x] 核心接口：`ModelClient` / `Tool` / `ToolRegistry` / `ToolExecutor` / `Agent` / `AgentLoop`
- [x] 默认实现：`InMemoryToolRegistry` / `DefaultToolExecutor` / `ReActAgentLoop` / `SimpleAgent`
- [x] Mock 实现：`MockModelClient`（脚本模式 + 规则模式）/ `EchoTool` / `CurrentTimeTool`
- [x] **OpenAiModelClient**：Java HttpClient + SSE 流式，兼容 OpenAI / Azure / Ollama / 火山方舟
- [x] **AnthropicModelClient**：Claude Messages API 适配器
- [x] **RetryModelClient** 装饰器：指数退避 + 错误码分类
- [x] **TimeoutModelClient** 装饰器：CompletableFuture.orTimeout
- [x] **FallbackModelClient** 装饰器：多级链式降级
- [x] **StructuredOutputModelClient** 装饰器：JSON schema 强制 + 验证重试
- [x] **插件系统**：`Plugin` / `ToolPlugin` / `PluginRegistry` / `PluginManager`
  - Java SPI 发现机制（ServiceLoader）
  - 插件生命周期：load -> unload -> reload
  - 故障隔离：一个插件失败不影响其他
- [x] **Agent 自进化**：4 个插件管理 Tool（inspect / list / load / unload）
  - 模型在对话中自我管理能力
- [x] **沙箱系统**：`Sandbox` / `ClassLoaderSandbox` / `ProcessSandbox`
  - 内存编译（Java Compiler API，零磁盘 IO）
  - ClassLoader 隔离（拦截 File/Runtime/ProcessBuilder/Network/反射）
  - 进程隔离（ProcessBuilder + 超时 + 工作目录限制）
  - 超时自动终止（死循环 2 秒被 kill）
- [x] 单元测试：55 个（15 Agent/装饰器 + 29 插件 + 11 沙箱），全绿
- [x] 示例：`MockAgentExample` / `PluginExample` / `PluginSelfModificationExample` / `SandboxExample`

### 下一步

- [ ] 写第一篇文章草稿（基于已有 Stage 1-4 的代码和研究素材）
- [ ] 阶段 5：Workflow 和 Graph Runtime

## 模块结构

```
java-agent-framework/
├── agent-core/          # 核心接口与数据结构（零依赖）
├── agent-model/          # 模型适配器（Mock, OpenAI, Anthropic）
├── agent-plugin/         # 插件系统（SPI 发现 + 热加载/卸载）
├── agent-sandbox/        # 沙箱系统（ClassLoader + 进程隔离）
├── examples/            # 示例代码
├── notes/               # 学习笔记（按阶段组织）
└── pom.xml              # 父 POM
```

### 后续模块（按 18 周路线逐步创建）

```
agent-sandbox/           # 阶段 4：沙箱与隔离执行
agent-workflow/          # 阶段 5：工作流图引擎
agent-memory/            # 阶段 8：记忆与上下文
agent-runtime/           # 阶段 6：运行时与 Checkpoint
agent-scheduler/         # 阶段 7：异步任务调度器
agent-security/          # 阶段 9：安全与审计
agent-mcp/               # 阶段 10：MCP 集成
agent-channel/           # 阶段 12：频道级共享 Agent
agent-trace-export/      # 阶段 14：RL 轨迹导出
agent-product/           # 阶段 13：声明式产品层
agent-observability/     # 阶段 18：可观测性
```

## 快速开始

```bash
# 编译
cd projects/java-agent-framework
mvn clean compile

# 运行示例
mvn exec:java -pl examples -Dexec.mainClass=com.seven.agent.examples.MockAgentExample

# 运行测试
mvn test
```

## 核心接口速览

```
ModelClient            # 统一模型调用入口（sync + streaming）
  └─ MockModelClient   # 不依赖真实 LLM 的测试实现

Tool                   # 工具接口（name + schema + execute）
ToolRegistry           # 工具注册表（in-memory 实现）
ToolExecutor           # 工具执行器（错误包装 + 日志）

Agent                  # Agent 入口
AgentConfig            # Agent 静态配置（prompt + model + tools）
AgentState             # Agent 运行时状态（messages + steps + status）
AgentLoop              # ReAct 循环（核心执行逻辑）
  └─ ReActAgentLoop    # 默认实现
```

## 设计决策

### 为什么不直接用 LangChain4j / Spring AI？

> 不是重复造轮子，是通过造轮子理解轮子。
>
> Java 生态三大框架（LangChain4j / Spring AI / AgentScope Java）在故障恢复和持久化执行上均有空白。
> 自己实现一遍是理解 Agent 架构全貌的最佳路径。

### 为什么 ToolRegistry 和 ToolExecutor 分开？

- Registry 是元数据（"什么工具存在"）
- Executor 是行为（"如何安全执行"）
- 后续 Executor 会增加 timeout、policy check、audit log、sandbox

### 为什么 AgentState 是 mutable？

- 简化 stage 1-2 的实现
- Stage 6 会增加 snapshot/checkpoint 机制
- Stage 14 会增加 trajectory 导出

## 学习路线

对应 [18 周学习规划](../seven-we-meida/01-inbox/2026-08-11_Java-Agent-Framework学习规划.md)：

| 阶段 | 模块 | 状态 |
|------|------|------|
| 1. 模型调用层 | agent-core / agent-model | ✅ 进行中 |
| 2. 最小 Agent Loop | agent-core | ✅ 进行中 |
| 3. 插件化与热插拔 | agent-plugin | ⬜ |
| 4. 沙箱与隔离执行 | agent-sandbox | ⬜ |
| 5. Workflow Graph | agent-workflow | ⬜ |
| ... | ... | ⬜ |
