# 核心概念

本页只讲运行时怎么转。模块清单见 [modules.md](modules.md)。`notes/` 里的阶段笔记是学习材料，不是契约。

## Agent

`Agent` 是调用入口，故意很薄：`run(userInput)` 一次跑完，`run(userInput, state)` 带着已有 `AgentState` 续跑。流式输出走 `stream(userInput, listener)`，边生成边回调 `AgentEvent`。多模态用户消息走 `run(ChatMessage)` / `stream(ChatMessage, listener)`。

默认实现是 `SimpleAgent`。静态蓝图是 `AgentConfig`：名字、system prompt、`ModelClient`、`ToolRegistry`、`maxSteps`，以及可选的 `ContextBuilder`（记忆注入；`null` 则透传）。

模块：`agent-core`。示例：`MockAgentExample` / `StreamingAgentExample`。

## Tool

`Tool` 是模型可调用的动作。注册进 `ToolRegistry`（默认 `InMemoryToolRegistry`），由 `ToolExecutor` 执行。模型返回 `ToolCall`，Loop 调工具，把结果写回消息列表，再问模型。

工具可以是本地 Java（`CurrentTimeTool`）、SPI 插件、MCP 适配器、或沙箱里编译执行的代码。治理层（权限 / 审批 / 净化 / 审计）包在执行器外面，不改 `Tool` 接口。

模块：接口在 `agent-core`；插件 `agent-plugin`；治理 `agent-security`；MCP `agent-mcp`。

## Loop

`AgentLoop` 是 ReAct 循环：在 `maxSteps` 内反复「组请求 → `ModelClient.chat` → 有 tool call 就执行 → 否则结束」。`stream` 走同一循环，但调用 `ModelClient.stream`，把 token / 工具起止推给 `AgentEvent` sink。Loop 是函数，不是线程：吃 `AgentConfig` + 可变 `AgentState`，返回更新后的 state。可测、可续跑。

默认实现是 `ReActAgentLoop`。`SimpleAgent` 把 `run` / `stream` 委托给它。

模块：`agent-core`。

## Workflow

单次 `Agent.run` 解决不了分支、人工审批、并行和长等待时，上图引擎。`Workflow` 是不可变图；`GraphRuntime` 解释执行；状态写在黑板 `WorkflowState` 上。

v1 有 7 种节点（`ActionNode` / `AgentNode` / `ToolNode` / `RouterNode` / `HumanApprovalNode` / `ParallelNode`，以及并行汇合用的 `JoinPolicy`）。`Agent` 可以作为图上的一个节点，不必另造一套。

模块：`agent-workflow`。示例：`WorkflowSupportFlowExample`。

## Memory

三层记忆 + 一条 scope：

| 层 | 是什么 |
|----|--------|
| Working | 当前 `AgentState` 里的对话 |
| Session | 一轮会话的连续上下文 |
| Long-term | `MemoryStore` 里可检索的条目 |

`MemoryScope`（agent / user / session / task / channel）决定「谁能看见」。共享记忆不是第二套系统，只是 scope 取值不同。写入经 `MemoryExtractor`（`extract.KeywordMemoryExtractor` 或 `extract.LlmMemoryExtractor`）+ `MemoryPolicy`；读出经 `MemoryRetriever`（`recallForContext` 按 importance 再 recency 取 topN）+ `context.MemoryContextBuilder`。房间引擎走可选的 `MemorySource`（`ChatRoom.Builder.source`，默认不挂）。`dueAt` 是可选时间戳，查询可按区间过滤；框架不调度、不解释含义。抽取指令与 subject 词表由调用方决定。超预算时 `context.ContextCompressor` 做压缩。

包按流水线切，仍是一个 Maven 模块：根包是接线面（Store / Entry / Query / Scope / Extractor / Retriever / Policy / Admin）；`extract/` 写、`store/` 存、`context/` 读与压缩、`session/` 会话层、`tools/` 模型自管记忆。

模块：`agent-memory`。示例：`MemoryExample` / `CompressionExample` / `ChannelMemoryExample`。

## Governance

工具默认不可信。治理四件套挂在 `GovernedToolExecutor` 上（装饰 `DefaultToolExecutor`，向后兼容）：

1. **Permission** — `AUTO` / `REQUIRES_APPROVAL` / `DENY`
2. **Approval** — 人工或自动审批
3. **Sanitizer** — 工具回包注入防御（替换 / 截断 / 阻断）
4. **Audit** — 允许、拒绝、执行、失败、净化都记

MCP 工具注册后自动走同一套，不用再写一遍。

模块：`agent-security`。示例：`SecurityExample` / `InjectionDefenseExample`。

## Checkpoint

长流程会停：等人批、等事件、等定时。停下来要把图状态和 `AgentState` 存住，恢复时从断点继续，而不是重跑。

`Checkpoint` / `CheckpointStore`（内存或文件）属于 workflow runtime。调度器（`agent-scheduler`）在定时或事件到达后 `resume`。企业任务审批、频道接力也复用同一套「停 → 存 → 续」。

模块：`agent-workflow`（存储）+ `agent-scheduler`（唤醒）。示例：`CheckpointExample` / `SchedulerExample`。
