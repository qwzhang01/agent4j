# Stage 1-2 学习笔记：模型调用层 + 最小 Agent Loop

> 时间：2026-08-12 起
> 对应学习规划：阶段 1 + 阶段 2
> 状态：阶段 1 ✅ 已完成 / 阶段 2 ✅ 已完成

---

## 阶段 1：模型调用层 ✅

### 核心设计决策

#### 1. 为什么 ModelClient 是接口而不是具体类？

Agent 代码依赖接口，不依赖具体模型 SDK。切换 OpenAI -> 本地模型不改 Agent 逻辑。

```
Agent --depends on--> ModelClient (interface)
                         ↑
            MockModelClient | OpenAiModelClient | OllamaModelClient
```

#### 2. 装饰器模式为什么是阶段 1 的核心？

生产级 ModelClient 需要四个能力：重试、超时、降级、结构化输出验证。
如果把这些直接写在 ModelClient 实现里，每个 provider 都要重写一遍。

装饰器方案：
```
StructuredOutput   <- 外层：验证 JSON
  └─ Fallback       <- 主模型挂了切备用
     └─ Timeout     <- 强制超时
        └─ Retry    <- 重试瞬时故障
           └─ Real Client (OpenAI / Mock / ...)
```

每个装饰器只做一件事，可以自由组合，新增 provider 不需要改装饰器。

#### 3. Retry 的错误码分类策略

| 错误码 | 重试 | 理由 |
|--------|------|------|
| TIMEOUT | ✅ | 网络抖动，重试可能恢复 |
| RATE_LIMITED | ✅ | 等待后限流解除 |
| NETWORK_ERROR | ✅ | 瞬时网络故障 |
| MODEL_ERROR | ✅ | 服务端错误，可能恢复 |
| AUTH_ERROR | ❌ | 不会自己变好 |
| INVALID_REQUEST | ❌ | 客户端错误，重试无用 |

#### 4. Structured Output 的两层保障

- **请求级**：设置 `responseFormat` 让 provider 原生强制 JSON 输出
- **验证级**：收到响应后验证 JSON 有效性，失败则追加修正提示重试

#### 5. StreamEvent 为什么用 sealed interface？

Java 21 的 sealed interface 让事件类型封闭：
- 只有 ContentDelta / ToolCallEvent / Done / Error 四种
- 模式匹配时编译器保证穷尽性
- 比 sealed class 更符合"事件"的语义

#### 6. OpenAiModelClient 的 SSE 流式解析

OpenAI 流式响应格式（SSE）：
```
data: {"choices":[{"delta":{"content":"Hello"}}]}
data: {"choices":[{"delta":{"content":" world"}}]}
data: [DONE]
```

Java HttpClient 的 `BodyHandlers.ofLines()` 返回 `Stream<String>`，
配合 `takeWhile(!"[DONE]")` 可以优雅地解析。

### 验收对照

| 阶段 1 验收要求 | 状态 | 实现 |
|----------------|------|------|
| Chat Completion | ✅ | `ModelClient.chat()` + `OpenAiModelClient` |
| Streaming | ✅ | `ModelClient.stream()` + SSE 解析 |
| Tool Calling | ✅ | `ModelRequest.tools` + `ModelResponse.toolCalls` |
| Structured Output | ✅ | `ResponseFormat` + `StructuredOutputModelClient` |
| Timeout | ✅ | `TimeoutModelClient` 装饰器 |
| Retry | ✅ | `RetryModelClient` 装饰器（指数退避 + 错误码分类） |
| Fallback | ✅ | `FallbackModelClient` 装饰器（多级链式降级） |
| Model Provider 抽象 | ✅ | `MockModelClient` + `OpenAiModelClient` 两种实现可切换 |

**阶段 1 验收标准达成**：✅ 能够通过统一接口切换至少两种模型调用方式，并处理超时、重试和结构化输出失败。

---

## 阶段 2：最小 Agent Loop ✅

### ReAct 循环流程

```
User Input
    ↓
┌─-> Build ModelRequest (messages + tools)
│       ↓
│   ModelClient.chat(request)
│       ↓
│   ├── hasToolCalls? -> YES -> execute tools -> add results -> continue loop
│   │
│   └── isFinished?   -> YES -> DONE -> return final answer
│
└── max steps exceeded? -> STOP with warning
```

### 核心设计决策

#### 1. 为什么 ToolRegistry 和 ToolExecutor 分开？

- **Registry**：管理工具元数据（名字、描述、参数 schema）
- **Executor**：负责安全执行（错误包装、超时、审计、沙箱）

后续 Executor 会变成 Pipeline：
```
ToolCall -> Policy Check -> Timeout -> Sandbox -> Execute -> Audit Log
```

如果合在一起，Policy/Sandbox/Audit 就很难插进去。

#### 2. 为什么 AgentState 是 mutable 而不是 immutable？

Stage 1-2 优先简洁。AgentState 内部就是一个 List<ChatMessage>，
每步追加消息。Stage 6 会加 snapshot() 方法用于 Checkpoint。

#### 3. 为什么 AgentLoop 是函数不是线程？

```
AgentState execute(AgentConfig, AgentState)
```

- 可测试（纯函数，输入 -> 输出）
- 可组合（多个 Loop 可以串联）
- 可暂停（Stage 6 在任意步骤 checkpoint）
- 不是 Thread/Runnable -> 不绑定线程模型

### Agent 面试要点

> "Agent 是不确定性决策节点。AgentLoop 负责把'模型决策'和'工具执行'
> 串成一个循环，每次循环检查：模型是要调工具，还是已经给出最终答案。
> 最大步数是安全阀--防止模型陷入循环。"

### 验收对照

| 阶段 2 验收要求 | 状态 | 实现 |
|----------------|------|------|
| 接收用户问题 | ✅ | `SimpleAgent.run(userInput)` |
| 选择工具 | ✅ | 模型返回 `toolCalls`，AgentLoop 处理 |
| 执行工具 | ✅ | `DefaultToolExecutor.execute()` |
| 结果交回模型 | ✅ | `ChatMessage.tool()` 加入 messages |
| 生成最终答案 | ✅ | `finishReason="stop"` 时退出 |
| 错误时安全退出 | ✅ | `AgentState.Status.ERROR` + `lastError` |
| 超限时安全退出 | ✅ | `AgentState.Status.MAX_STEPS_EXCEEDED` |

**阶段 2 验收标准达成**：✅

---

## 每日产出记录

### 2026-08-12（Day 1）

**完成**：
- Maven 多模块项目骨架
- 核心接口定义：ModelClient / Tool / Agent / AgentLoop / AgentState
- 默认实现：ReActAgentLoop / SimpleAgent / InMemoryToolRegistry / DefaultToolExecutor
- MockModelClient（脚本 + 规则两种模式）
- 单元测试 3 个（文本响应 / 工具调用 / 最大步数）
- 示例 MockAgentExample

### 2026-08-13（Day 2）

**完成**：
- **RetryModelClient** 装饰器：指数退避 + 错误码分类（AUTH/INVALID 不重试，TIMEOUT/NETWORK/MODEL_ERROR 重试）
- **TimeoutModelClient** 装饰器：CompletableFuture.orTimeout 强制超时
- **FallbackModelClient** 装饰器：多级链式降级（主模型 -> 备用1 -> 备用2）
- **StructuredOutputModelClient** 装饰器：请求级 responseFormat + 验证级 JSON 校验重试
- **ModelRequest 增强**：新增 `responseFormat` 字段和 `ResponseFormat` record
- **OpenAiModelClient**：Java 21 HttpClient 调用 OpenAI 兼容 API，支持 SSE 流式解析
- 装饰器测试 12 个（4 组：Retry / Timeout / Fallback / StructuredOutput）
- 装饰器组合示例 DecoratedModelClientExample
- 总测试 15 个全绿

**学到**：
- 装饰器模式是 ModelClient 生产化的最佳方案：每个装饰器只做一件事，可自由组合
- Retry 的关键不是"重试几次"，而是"哪些错误值得重试"——错误码分类是核心
- Structured Output 需要两层保障：provider 原生支持 + 应用层验证兜底
- Java HttpClient + `BodyHandlers.ofLines()` 可以优雅处理 SSE 流式响应
- TimeoutModelClient 的 CompletableFuture.orTimeout 需要 cancel(true) 防止后台任务泄漏

**面试要点**：
> "我的 ModelClient 用装饰器模式实现了生产级的重试、超时、降级和结构化输出验证。
> 重试策略基于错误码分类——AUTH 和 INVALID_REQUEST 不重试，TIMEOUT 和 NETWORK_ERROR 重试。
> Structured Output 两层保障：请求级用 response_format 让 provider 原生强制 JSON，
> 验证级在收到响应后校验，失败则追加修正提示重试。"

**下一步**：
- 阶段 3：插件化与热插拔系统
- 或：写第一篇文章草稿（基于已有研究素材）
